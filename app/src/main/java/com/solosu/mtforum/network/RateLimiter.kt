package com.solosu.mtforum.network

import com.solosu.mtforum.ai.AiLog

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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

    private const val STATS_WINDOW_MS = 5 * 60 * 1000L

    private val globalConcurrent = AtomicInteger(0)
    private val hostConcurrent = ConcurrentHashMap<String, AtomicInteger>()

    private val lastStartAt = AtomicLong(0L)
    private val startLock = Any()

    private val circuitOpenUntil = AtomicLong(0L)
    private val blockLock = Any()
    private val blockedTimes = ArrayDeque<Long>()

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
        synchronized(blockLock) {
            val now = System.currentTimeMillis()
            blockedTimes.addLast(now)
            while (blockedTimes.isNotEmpty() && now - blockedTimes.first() > blockWindowMs) {
                blockedTimes.removeFirst()
            }
            if (blockedTimes.size >= circuitThreshold) {
                blockedTimes.clear()
                openMs = circuitOpenMs
                circuitOpenUntil.set(now + circuitOpenMs)
            }
        }
        if (openMs > 0) {
            AiLog.e(
                "ratelimit",
                "拦截信号频繁（" + reason + "），熔断 " + (openMs / 1000) + " 秒内暂停全部出站请求"
            )
        } else {
            AiLog.e("ratelimit", "疑似被拦截（" + reason + "）")
        }
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

        if (observeOnly) {
            val response = chain.proceed(request)
            recordStats(response.code)
            return@Interceptor response
        }

        val remain = circuitRemainingMs()
        if (remain > 0) {
            throw IOException("站点风控熔断中，暂停请求 " + (remain / 1000 + 1) + " 秒")
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
            if (response.code == 403 || response.code == 429 || response.code == 503) {
                reportBlocked("HTTP " + response.code)
            }
            return@Interceptor response
        } finally {
            if (hostAcquired && hostCounter != null) hostCounter.decrementAndGet()
            globalConcurrent.decrementAndGet()
        }
    }
}
