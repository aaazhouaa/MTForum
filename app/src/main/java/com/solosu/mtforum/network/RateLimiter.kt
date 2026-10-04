package com.solosu.mtforum.network

import com.solosu.mtforum.ai.AiLog

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 全局出站请求节流器（挂在 HttpClient 的 OkHttpClient 上，覆盖所有请求）。
 *
 * 站点对同源请求过频会下发 WAF 挑战页并升级为 IP 级封禁；而客户端此前既无限速、
 * 也无退避，被拦后轮询照旧，导致封禁持续续期。这里补三道闸：
 *
 *  1) 全局最小请求间隔 + 全局/每 host 并发上限，压住突发；
 *  2) 窗口内被拦截次数达到阈值即打开熔断，期间全部请求快速失败；
 *  3) 每 5 分钟输出一条统计日志，便于核对实际速率。
 *
 * observeOnly = true 时只统计、不限流不熔断，可先取基线再决定力度。
 */
object RateLimiter {

    /** 观测模式：true 时只统计，不限流、不熔断。 */
    @JvmStatic
    var observeOnly: Boolean = false

    /** 相邻两次请求发起的最小间隔（毫秒）。 */
    @JvmStatic
    var minIntervalMs: Long = 250L

    /** 全局在途请求上限。 */
    @JvmStatic
    var maxConcurrent: Int = 3

    /** 单个 host 在途请求上限。 */
    @JvmStatic
    var maxConcurrentPerHost: Int = 2

    /** 拦截计数窗口内达到该次数即熔断。 */
    @JvmStatic
    var circuitThreshold: Int = 5

    /** 拦截计数窗口时长（毫秒）。 */
    @JvmStatic
    var blockWindowMs: Long = 2 * 60 * 1000L

    /** 熔断窗口时长（毫秒）。 */
    @JvmStatic
    var circuitOpenMs: Long = 5 * 60 * 1000L

    /** 连续熔断时的封顶时长（毫秒）。每次重开按 2 倍退避，避免刚恢复又被封的死循环。 */
    @JvmStatic
    var maxCircuitOpenMs: Long = 30 * 60 * 1000L

    private const val STATS_WINDOW_MS = 5 * 60 * 1000L

    private val globalConcurrent = AtomicInteger(0)
    private val hostConcurrent = ConcurrentHashMap<String, AtomicInteger>()

    private val lastStartAt = AtomicLong(0L)
    private val startLock = Any()

    private val circuitOpenUntil = AtomicLong(0L)
    private val blockLock = Any()
    private val blockedTimes = ArrayDeque<Long>()

    /** 连续熔断次数：用于指数退避，目标 host 健康放行后清零。 */
    private val consecutiveOpens = AtomicInteger(0)

    /** 半开探测闸门：冷却结束的恢复期只放行一个目标 host 请求试探，其余快速失败。 */
    private val probeGate = AtomicBoolean(false)

    private val statsLock = Any()
    private var windowStartAt = 0L
    private var windowTotal = 0L
    private var windowBlocked = 0L

    private fun counterFor(host: String): AtomicInteger {
        var c = hostConcurrent[host]
        if (c == null) {
            c = AtomicInteger(0)
            val prev = hostConcurrent.putIfAbsent(host, c)
            if (prev != null) c = prev
        }
        return c
    }

    /** 参与熔断统计的站点主域；图片/CDN 等其它 host 只限流、不熔断。 */
    private val circuitHost: String by lazy {
        HttpClient.BASE_URL.toHttpUrlOrNull()?.host ?: ""
    }

    /** 该 URL 是否属于站点主域（即参与熔断的目标）。 */
    @JvmStatic
    fun isCircuitHost(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host ?: return false
        return host == circuitHost
    }

    /** 熔断剩余毫秒，未熔断返回 0。 */
    @JvmStatic
    fun circuitRemainingMs(): Long {
        val remain = circuitOpenUntil.get() - System.currentTimeMillis()
        return if (remain > 0) remain else 0L
    }

    /**
     * 站点返回风控拦截信号时调用（HTTP 403/429/503 或 WAF 挑战页）。
     * 计数窗口内累计达到阈值即打开熔断。用窗口计数而非“连续”，是因为并发轮询里
     * 健康响应会把“连续”清零，导致熔断永远不触发。
     */
    @JvmStatic
    fun reportBlocked(reason: String) {
        var openMs = 0L
        var opens = 0
        synchronized(blockLock) {
            val now = System.currentTimeMillis()
            blockedTimes.addLast(now)
            while (blockedTimes.isNotEmpty() && now - blockedTimes.first() > blockWindowMs) {
                blockedTimes.removeFirst()
            }
            // 恢复期的探测请求只要再被拦一次就直接重开：刚放行就被拒，说明 IP 仍未解封，
            // 没必要再等凑满阈值（否则会退化成逐个慢速试探）。
            val stillBlocked = consecutiveOpens.get() > 0
            if (blockedTimes.size >= circuitThreshold || stillBlocked) {
                blockedTimes.clear()
                opens = consecutiveOpens.incrementAndGet()
                var window = circuitOpenMs
                var step = 1
                while (step < opens && window < maxCircuitOpenMs) {
                    window = window shl 1
                    step++
                }
                if (window > maxCircuitOpenMs) window = maxCircuitOpenMs
                circuitOpenUntil.set(now + window)
                openMs = window
            }
        }
        if (openMs > 0) {
            AiLog.e(
                "ratelimit",
                "拦截信号频繁（" + reason + "），熔断 " + (openMs / 1000) + " 秒内暂停站点出站请求（第 " +
                        opens + " 次）"
            )
        } else {
            AiLog.e("ratelimit", "疑似被拦截（" + reason + "）")
        }
    }

    /** 目标 host 健康放行：退避计数复位。 */
    private fun onHealthyResponse() {
        if (consecutiveOpens.get() != 0) consecutiveOpens.set(0)
    }

    private fun throttle() {
        synchronized(startLock) {
            val wait = lastStartAt.get() + minIntervalMs - System.currentTimeMillis()
            if (wait > 0) {
                try {
                    Thread.sleep(wait)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            lastStartAt.set(System.currentTimeMillis())
        }
    }

    private fun acquire(counter: AtomicInteger, max: Int) {
        while (true) {
            val cur = counter.get()
            if (cur < max && counter.compareAndSet(cur, cur + 1)) return
            try {
                Thread.sleep(15)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("请求排队被中断")
            }
        }
    }

    private fun recordStats(code: Int) {
        val blocked = code == 403 || code == 429 || code == 503
        synchronized(statsLock) {
            val now = System.currentTimeMillis()
            if (windowStartAt == 0L) windowStartAt = now
            windowTotal++
            if (blocked) windowBlocked++
            if (now - windowStartAt >= STATS_WINDOW_MS) {
                AiLog.i(
                    "ratelimit",
                    "近 " + ((now - windowStartAt) / 1000) + " 秒出站请求 " + windowTotal +
                            " 次，被拦截 " + windowBlocked + " 次"
                )
                windowStartAt = now
                windowTotal = 0
                windowBlocked = 0
            }
        }
    }

    /** 供 HttpClient 挂载的拦截器。 */
    @JvmStatic
    val interceptor: Interceptor = Interceptor { chain ->
        val request = chain.request()
        val host = request.url.host
        val isTargetHost = host == circuitHost

        if (observeOnly) {
            val response = chain.proceed(request)
            recordStats(response.code)
            return@Interceptor response
        }

        // 熔断只拦站点主域；图片/CDN 等其它 host 照常放行，避免熔断期间页面整片空白
        var probing = false
        if (isTargetHost) {
            val remain = circuitRemainingMs()
            if (remain > 0) {
                throw IOException("站点风控冷却中，约 " + (remain / 1000 + 1) + " 秒后自动恢复")
            }
            // 冷却结束仍处于退避状态时，只放行一个探测请求，避免并发洪峰立刻再触发风控
            if (consecutiveOpens.get() > 0) {
                if (!probeGate.compareAndSet(false, true)) {
                    throw IOException("风控冷却中，正在试探恢复站点连接")
                }
                probing = true
            }
        }

        acquire(globalConcurrent, maxConcurrent)
        var hostCounter: AtomicInteger? = null
        var hostAcquired = false
        try {
            hostCounter = counterFor(host)
            acquire(hostCounter, maxConcurrentPerHost)
            hostAcquired = true
            throttle()
            val response: Response = chain.proceed(request)
            recordStats(response.code)
            if (isTargetHost) {
                if (response.code == 403 || response.code == 429 || response.code == 503) {
                    reportBlocked("HTTP " + response.code)
                } else {
                    onHealthyResponse()
                }
            }
            return@Interceptor response
        } finally {
            if (probing) probeGate.set(false)
            if (hostAcquired && hostCounter != null) hostCounter.decrementAndGet()
            globalConcurrent.decrementAndGet()
        }
    }
}
