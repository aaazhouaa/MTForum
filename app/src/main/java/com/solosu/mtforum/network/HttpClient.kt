package com.solosu.mtforum.network

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.webkit.CookieManager
import android.webkit.CookieSyncManager

import org.json.JSONArray
import org.json.JSONObject

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.FormBody
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody

import com.solosu.mtforum.ai.AiLog

import java.util.ArrayList
import java.util.HashMap
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * 网络请求管理器
 * 基于 OkHttp 封装，支持会话保持（Cookie 持久化）
 */
class HttpClient private constructor() {
    private var client: OkHttpClient
    private val cookieStore: MutableMap<String, MutableList<Cookie>>

    /** 保存 Application 上下文，用于在每次请求后自动持久化 Cookie */
    private var appContext: Context? = null

    // ==================== WAF JS 挑战处理 ====================

    /**
     * 处理阿里云 ESA WAF 的 JS 挑战。
     *
     * <p>站点对 forum.php / home.php / search.php 等下发约 4KB 的 JS 挑战页，
     * 浏览器执行后会写入 acw_sc__v2 Cookie 再刷新，WAF 见到该 Cookie 才放行。
     * 原生 HTTP 客户端没有 JS 引擎，原本会永远拿到挑战页、解析出空列表。
     *
     * <p>这里直接本地算出该 Cookie 并注入 CookieJar，让调用方重放一次即可。
     *
     * @return true 表示已成功获取并写入挑战 Cookie，调用方应重试请求
     */
    private fun solveWafChallenge(url: String, body: String?): Boolean {
        if (!WafChallenge.looksLikeChallenge(body)) return false
        val value = WafChallenge.solveFromPage(body)
        if (value == null || value.isEmpty()) {
            if (DEBUG_WAF) {
                AiLog.e("waf", "命中挑战页但求解失败（算法可能已失配）url=$url")
            }
            return false
        }
        val httpUrl = url.toHttpUrlOrNull() ?: return false
        try {
            val cookie = Cookie.Builder()
                .name(WafChallenge.COOKIE_NAME)
                .value(value)
                .domain(httpUrl.host)
                .path("/")
                .expiresAt(Long.MAX_VALUE)
                .build()
            // 走 CookieJar 写入，保持与其它 Cookie 同一存储与持久化路径
            client.cookieJar.saveFromResponse(
                httpUrl,
                Collections.singletonList(cookie)
            )
            if (appContext != null) commitCookieStore(appContext!!)
            if (DEBUG_WAF) {
                AiLog.i("waf", "已求解挑战 Cookie，重试请求 url=$url")
            }
            return true
        } catch (e: Exception) {
            if (DEBUG_WAF) AiLog.e("waf", "写入挑战 Cookie 失败: " + e.message)
            return false
        }
    }

    /**
     * 执行一次 GET 并在必要时处理 WAF 挑战（重试一次）。
     */
    @Throws(IOException::class)
    private fun executeGetWithChallenge(request: Request): String {
        val body = executeBody(request)
        if (!WafChallenge.looksLikeChallenge(body)) return body
        if (RateLimiter.isCircuitHost(request.url.toString())) {
            RateLimiter.reportBlocked("WAF 挑战页")
        }
        // 挑战页：求解后重放同一请求
        val url = request.url.toString()
        if (!solveWafChallenge(url, body)) return body
        val retry = request.newBuilder().build()
        return executeBody(retry)
    }

    /** 执行请求并读取响应体（统一出口，便于挑战处理复用） */
    @Throws(IOException::class)
    private fun executeBody(request: Request): String {
        client.newCall(request).execute().use { response ->
            val body = if (response.body != null) response.body!!.string() else ""
            if (appContext != null) commitCookieStore(appContext!!)
            return body
        }
    }

    /** 飞行中请求去重：相同 URL 的请求未完成时复用同一结果，避免重复发送 */
    private val pendingGets = ConcurrentHashMap<String, CompletableFuture<String>>()
    private val pendingPosts = ConcurrentHashMap<String, CompletableFuture<String>>()

    init {
        cookieStore = HashMap()
        client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            // 图片在后台先被规范化，移动网络上传时仍可能超过普通页面请求时长。
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            // 全局节流/熔断：压住突发请求，命中风控后自动退避，避免 IP 被封
            .addInterceptor(RateLimiter.interceptor)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    val host = url.host
                    var existing = cookieStore[host]
                    if (existing == null) {
                        existing = ArrayList()
                        cookieStore[host] = existing
                    }
                    for (cookie in cookies) {
                        var found = false
                        for (i in existing.indices) {
                            if (existing[i].name == cookie.name) {
                                existing[i] = cookie
                                found = true
                                break
                            }
                        }
                        if (!found) {
                            existing.add(cookie)
                        }
                    }
                }

                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    val host = url.host
                    val cookies = cookieStore[host]
                    return cookies ?: Collections.emptyList()
                }
            })
            .build()
    }

    /**
     * GET 请求
     */
    @Throws(Exception::class)
    fun get(url: String): String {
        // 去重：相同 URL 正在请求则等待已有结果，避免重复发送
        val existing = pendingGets[url]
        if (existing != null) {
            try {
                return existing.get()
            } catch (e: Exception) {
                /* 上次请求失败，继续发起新请求 */
            }
        }
        val future = CompletableFuture<String>()
        val prev = pendingGets.putIfAbsent(url, future)
        if (prev != null) {
            try {
                return prev.get()
            } catch (e: Exception) {
                /* 上同 */
            }
        }
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .get()
                .build()
            val body = executeGetWithChallenge(request)
            future.complete(body)
            return body
        } catch (e: Exception) {
            future.completeExceptionally(e)
            throw e
        } finally {
            pendingGets.remove(url, future)
        }
    }

    /**
     * GET 请求返回 byte[]（用于下载图片等二进制内容）
     */
    @Throws(Exception::class)
    fun getBytes(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            return if (response.body != null) response.body!!.bytes() else ByteArray(0)
        }
    }

    /**
     * GET 请求（强制使用桌面端 UA，用于获取桌面版页面中的 hash 等仅桌面版存在的字段）
     */
    @Throws(Exception::class)
    fun getDesktop(url: String): String {
        // 去重：相同 URL 正在请求则等待已有结果，避免重复发送
        val existing = pendingGets[url]
        if (existing != null) {
            try {
                return existing.get()
            } catch (e: Exception) {
                /* 上次请求失败，继续发起新请求 */
            }
        }
        val future = CompletableFuture<String>()
        val prev = pendingGets.putIfAbsent(url, future)
        if (prev != null) {
            try {
                return prev.get()
            } catch (e: Exception) {
                /* 上同 */
            }
        }
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .get()
                .build()
            val body = executeGetWithChallenge(request)
            future.complete(body)
            return body
        } catch (e: Exception) {
            future.completeExceptionally(e)
            throw e
        } finally {
            pendingGets.remove(url, future)
        }
    }

    /**
     * POST 请求（表单提交）
     */
    @Throws(Exception::class)
    fun post(url: String, params: Map<String, String>?): String {
        // POST 去重：URL + 参数的摘要作为 key
        val key = url + (if (params != null) params.toString() else "")
        val existing = pendingPosts[key]
        if (existing != null) {
            try {
                return existing.get()
            } catch (e: Exception) {
                /* 上次请求失败，继续发起新请求 */
            }
        }
        val future = CompletableFuture<String>()
        val prev = pendingPosts.putIfAbsent(key, future)
        if (prev != null) {
            try {
                return prev.get()
            } catch (e: Exception) {
                /* 上同 */
            }
        }
        try {
            val formBuilder = FormBody.Builder()
            if (params != null) {
                for ((k, v) in params) {
                    formBuilder.add(k, v)
                }
            }
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(formBuilder.build())
                .build()
            val body = executeBody(request)
            // POST 是写操作，不自动重放（避免重复提交）；但若命中挑战页，
            // 本地求解并存入 Cookie，让后续请求（含本次失败后的重试）能通过。
            if (WafChallenge.looksLikeChallenge(body)) {
                if (RateLimiter.isCircuitHost(url)) {
                    RateLimiter.reportBlocked("WAF 挑战页(POST)")
                }
                if (solveWafChallenge(url, body) && DEBUG_WAF) {
                    AiLog.e("waf", "POST 命中挑战页，已存 Cookie 但未重放（防重复提交）url=$url")
                }
            }
            future.complete(body)
            return body
        } catch (e: Exception) {
            future.completeExceptionally(e)
            throw e
        } finally {
            pendingPosts.remove(key, future)
        }
    }

    /**
     * POST 请求（表单提交），带 Referer。
     * Discuz 的回复/发帖接口会校验来源页，缺失 Referer 时可能被判为非法操作。
     */
    @Throws(Exception::class)
    fun postWithReferer(url: String, params: Map<String, String>?, referer: String?): String {
        val key = url + "@ref@" + (if (params != null) params.toString() else "")
        val existing = pendingPosts[key]
        if (existing != null) {
            try {
                return existing.get()
            } catch (e: Exception) {
                /* 上次请求失败，继续发起新请求 */
            }
        }
        val future = CompletableFuture<String>()
        val prev = pendingPosts.putIfAbsent(key, future)
        if (prev != null) {
            try {
                return prev.get()
            } catch (e: Exception) {
                /* 上同 */
            }
        }
        try {
            val formBuilder = FormBody.Builder()
            if (params != null) {
                for ((k, v) in params) {
                    formBuilder.add(k, v)
                }
            }
            val rb = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Content-Type", "application/x-www-form-urlencoded")
            if (!android.text.TextUtils.isEmpty(referer)) rb.header("Referer", referer!!)
            val request = rb.post(formBuilder.build()).build()
            val body = executeBody(request)
            // 同 post()：写操作不自动重放，仅求解并留存 Cookie
            if (WafChallenge.looksLikeChallenge(body)) {
                if (RateLimiter.isCircuitHost(url)) {
                    RateLimiter.reportBlocked("WAF 挑战页(POST)")
                }
                if (solveWafChallenge(url, body) && DEBUG_WAF) {
                    AiLog.e("waf", "POST(带Referer) 命中挑战页，已存 Cookie 但未重放 url=$url")
                }
            }
            future.complete(body)
            return body
        } catch (e: Exception) {
            future.completeExceptionally(e)
            throw e
        } finally {
            pendingPosts.remove(key, future)
        }
    }

    /**
     * 上传文件（multipart/form-data）
     * 用于 Discuz! 附件上传接口 forum.php?mod=ajax&action=upload&mobile=2
     */
    @Throws(IOException::class)
    fun uploadFile(url: String, file: File, fieldName: String, extraFields: Map<String, String>?): String {
        return uploadFileWithUserAgent(url, file, fieldName, extraFields, DESKTOP_USER_AGENT)
    }

    /**
     * multipart 上传并显式指定 UA/Cookie 头。Discuz Comiis 的 swfupload
     * 令牌由桌面版页面生成，上传请求也使用桌面 UA 更稳定。
     */
    @Throws(IOException::class)
    fun uploadFileWithUserAgent(
        url: String, file: File, fieldName: String,
        extraFields: Map<String, String>?, userAgent: String?
    ): String {
        return uploadFileWithUserAgent(
            url, file, fieldName, extraFields, userAgent,
            guessContentType(file)
        )
    }

    /**
     * multipart 上传，允许调用方保留系统相册提供的真实 MIME 类型。
     * 部分 Discuz/Comiis 站点会同时校验文件名扩展名和 Content-Type；把 PNG、WebP、HEIC
     * 一律伪装为 jpg/octet-stream 会导致网页端能传、原生端被拒绝。
     */
    @Throws(IOException::class)
    fun uploadFileWithUserAgent(
        url: String, file: File, fieldName: String,
        extraFields: Map<String, String>?, userAgent: String?,
        contentType: String?
    ): String {
        if (!file.isFile || file.length() <= 0) {
            throw IOException("上传文件不存在或为空")
        }

        // 登录可能发生在 WebView。仅当原生会话缺失时才拉取，避免空/旧 WebView Cookie
        // 覆盖仍然有效的 OkHttp 登录态；随后仍显式把当前 Cookie 写入上传请求。
        if (!isLoggedIn()) syncFromCookieManager()
        val cookieHeader = getCookieHeader()

        val mediaType = (if (contentType == null || contentType.trim().isEmpty())
            "application/octet-stream" else contentType).toMediaTypeOrNull()
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(fieldName, file.name, file.asRequestBody(mediaType))

        if (extraFields != null) {
            for ((k, v) in extraFields) {
                if (k != null && v != null) {
                    builder.addFormDataPart(k, v)
                }
            }
        }

        val requestBody = builder.build()
        val requestBuilder = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                if (userAgent == null || userAgent.isEmpty()) DESKTOP_USER_AGENT else userAgent
            )
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Referer", BASE_URL)
            .post(requestBody)
        // 手动设置 Cookie 时 OkHttp 不会重复由 CookieJar 注入同名 Header。
        if (cookieHeader.isNotEmpty()) {
            requestBuilder.header("Cookie", cookieHeader)
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            val body = if (response.body != null) response.body!!.string() else ""
            if (appContext != null) {
                commitCookieStore(appContext!!)
            }
            if (!response.isSuccessful) {
                throw IOException("上传请求失败（HTTP " + response.code + "）")
            }
            return body
        }
    }

    /**
     * ★ 使用 HttpClient 的 OkHttpClient（含 Cookie）执行自定义 Request
     * 用于头像上传等需要保持会话的外部请求
     * 返回 okhttp3.Response（调用方记得关闭 response.body()）
     */
    @Throws(Exception::class)
    fun executeDirect(request: okhttp3.Request): okhttp3.Response {
        val response = client.newCall(request).execute()
        // 请求完成后自动持久化 Cookie
        if (appContext != null) {
            commitCookieStore(appContext!!)
        }
        return response
    }

    /**
     * 获取当前 cookie 值
     */
    fun getCookieValue(host: String, name: String): String? {
        val cookies = cookieStore[host]
        if (cookies != null) {
            for (c in cookies) {
                if (c.name == name) {
                    return c.value
                }
            }
        }
        return null
    }

    /**
     * 获取指定 URL 域名的所有 Cookie 字符串（用于同步到 WebView）
     * 格式: "name1=value1; name2=value2"
     */
    fun getCookieString(): String {
        val host = BASE_URL.toHttpUrlOrNull()!!.host
        val cookies = cookieStore[host]
        if (cookies == null || cookies.isEmpty()) return ""

        val sb = StringBuilder()
        for (c in cookies) {
            if (sb.length > 0) sb.append("; ")
            sb.append(c.name).append("=").append(c.value)
        }
        return sb.toString()
    }

    /**
     * 判断是否已登录（检查是否有 Discuz 的 auth cookie）
     * 遍历所有 cookie，查找名称以 _auth 结尾的 cookie
     * 兼容不同论坛实例的不同 cookiepre 前缀
     */
    fun isLoggedIn(): Boolean {
        val host = BASE_URL.toHttpUrlOrNull()!!.host
        val cookies = cookieStore[host]
        if (cookies != null) {
            for (c in cookies) {
                if (c.name.endsWith("_auth") && !c.value.isEmpty()) {
                    return true
                }
            }
        }
        return false
    }

    /** 返回当前论坛域名的 Cookie Header，供需要显式携带会话的 multipart 请求使用。 */
    @Synchronized
    fun getCookieHeader(): String {
        return getCookieString()
    }

    /**
     * 从 WebView CookieManager 同步 Cookie。不同 Android/Chromium 版本对
     * BASE_URL、host URL 和末尾斜杠的处理不同，因此依次读取多个等价地址。
     */
    @Synchronized
    fun syncFromCookieManager() {
        try {
            val manager = CookieManager.getInstance()
            val urls = arrayOf(
                BASE_URL,
                "https://bbs.binmt.cc",
                "https://bbs.binmt.cc/",
                "http://bbs.binmt.cc/"
            )
            val merged = StringBuilder()
            for (url in urls) {
                val value = manager.getCookie(url)
                if (value == null || value.trim().isEmpty()) continue
                if (merged.length > 0) merged.append("; ")
                merged.append(value)
            }
            if (merged.length == 0) return

            val host = BASE_URL.toHttpUrlOrNull()!!.host
            var existing = cookieStore[host]
            if (existing == null) {
                existing = ArrayList()
                cookieStore[host] = existing
            }
            val pairs = merged.toString().split(";\\s*".toRegex(), 0).toTypedArray()
            for (pair in pairs) {
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                val name = pair.substring(0, eq).trim()
                val value = pair.substring(eq + 1).trim()
                if (name.isEmpty() || value.isEmpty()) continue
                var old: Cookie? = null
                for (c in existing) {
                    if (c.name == name) {
                        old = c
                        break
                    }
                }
                val b = Cookie.Builder().name(name).value(value)
                    .domain(host).path("/").expiresAt(Long.MAX_VALUE)
                if (old != null && old.secure) b.secure()
                var replaced = false
                for (i in existing.indices) {
                    if (existing[i].name == name) {
                        existing[i] = b.build()
                        replaced = true
                        break
                    }
                }
                if (!replaced) existing.add(b.build())
            }
            if (appContext != null) commitCookieStore(appContext!!)
        } catch (ignored: Exception) {
        }
    }

    /**
     * 将 HttpClient CookieJar 中的 Cookie 同步到 Android WebView CookieManager。
     * 用于登录由原生 OkHttp 完成、随后需要在 WebView 或混合页面中继续使用登录态的场景。
     */
    @Synchronized
    fun syncToCookieManager() {
        try {
            val webViewCookieMgr = CookieManager.getInstance()
            val cookies = getCookieString()
            if (cookies == null || cookies.isEmpty()) return
            val pairs = cookies.split(";\\s*".toRegex(), 0).toTypedArray()
            for (pair in pairs) {
                if (pair == null || pair.trim().isEmpty()) continue
                webViewCookieMgr.setCookie(BASE_URL, pair.trim())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                webViewCookieMgr.flush()
            } else {
                CookieSyncManager.getInstance().sync()
            }
        } catch (ignored: Exception) {
            // Cookie 同步失败不应阻断原生请求流程
        }
    }

    /**
     * 初始化 — 从磁盘恢复持久化的 Cookie
     * 在 Application.onCreate() 或首次使用前调用一次
     * 初始化后自动从 WebView CookieManager 拉取一次 Cookie，确保双向同步
     */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        this.appContext = context
        restoreCookieStore(context)
        // ★ 初始化后立即从 WebView CookieManager 拉取 Cookie，确保双向同步
        syncFromCookieManager()
    }

    /**
     * 将当前内存中的 Cookie 持久化到 SharedPreferences（JSON 序列化）
     * 在登录成功后调用
     */
    @Synchronized
    fun commitCookieStore(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val cookiesArray = JSONArray()

            for ((host, value) in cookieStore) {
                for (cookie in value) {
                    val obj = JSONObject()
                    obj.put("host", host)
                    obj.put("name", cookie.name)
                    obj.put("value", cookie.value)
                    obj.put("domain", cookie.domain)
                    obj.put("path", cookie.path)
                    obj.put("expiresAt", cookie.expiresAt)
                    obj.put("secure", cookie.secure)
                    obj.put("httpOnly", cookie.httpOnly)
                    obj.put("persistent", cookie.persistent)
                    if (cookie.persistent) {
                        // 只持久化 persistent cookie（登录态 cookie 通常是 persistent 的）
                        cookiesArray.put(obj)
                    } else {
                        // session cookie 也存一下，避免某些情况丢失
                        cookiesArray.put(obj)
                    }
                }
            }

            prefs.edit().putString(KEY_COOKIES, cookiesArray.toString()).apply()
        } catch (ignored: Exception) {
            // 序列化失败不抛出
        }
    }

    /**
     * 从 SharedPreferences 恢复 Cookie 到内存
     * 在应用启动时调用
     */
    @Synchronized
    fun restoreCookieStore(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_COOKIES, null)
            if (json == null || json.isEmpty()) return

            val cookiesArray = JSONArray(json)
            cookieStore.clear()

            for (i in 0 until cookiesArray.length()) {
                val obj = cookiesArray.getJSONObject(i)
                val host = obj.optString("host", BASE_URL.toHttpUrlOrNull()!!.host)

                val builder = Cookie.Builder()
                    .name(obj.getString("name"))
                    .value(obj.getString("value"))
                    .domain(obj.optString("domain", BASE_URL.toHttpUrlOrNull()!!.host))
                    .path(obj.optString("path", "/"))
                    .expiresAt(obj.optLong("expiresAt", Long.MAX_VALUE))

                if (obj.optBoolean("secure")) {
                    builder.secure()
                }
                if (obj.optBoolean("httpOnly")) {
                    // Cookie.Builder 没有 httpOnly() 方法，跳过
                }

                val cookie = builder.build()

                var cookies = cookieStore[host]
                if (cookies == null) {
                    cookies = ArrayList()
                    cookieStore[host] = cookies
                }
                cookies.add(cookie)
            }
        } catch (ignored: Exception) {
            // 反序列化失败时使用空 cookieStore（ignored）
        }
    }

    /**
     * 清除所有 Cookie（内存 + 磁盘）
     */
    /**
     * 从原始 Cookie 字符串设置 Cookie(用于 Cookie 登录)。
     * 格式: "key1=value1; key2=value2; ..."
     * 设置后可通过 isLoggedIn() 检查 _auth cookie 是否有效。
     */
    @Synchronized
    fun applyCookiesFromString(cookieString: String?) {
        if (cookieString == null || cookieString.trim().isEmpty()) return
        val host = BASE_URL.toHttpUrlOrNull()!!.host
        var existing = cookieStore[host]
        if (existing == null) {
            existing = ArrayList()
            cookieStore[host] = existing
        }
        val pairs = cookieString.split(";\\s*".toRegex(), 0).toTypedArray()
        for (pair in pairs) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim()
            if (name.isEmpty()) continue
            val b = Cookie.Builder().name(name).value(value)
                .domain(host).path("/").expiresAt(Long.MAX_VALUE)
            var replaced = false
            for (i in existing.indices) {
                if (existing[i].name == name) {
                    existing[i] = b.build()
                    replaced = true
                    break
                }
            }
            if (!replaced) existing.add(b.build())
        }
    }

    fun clearCookies() {
        cookieStore.clear()
    }

    /**
     * 清除所有 Cookie（内存 + 磁盘 + WebView）
     * 在登出时调用
     */
    fun clearCookies(context: Context) {
        cookieStore.clear()
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_COOKIES).apply()
        } catch (ignored: Exception) {
        }
        // build59: WebView CookieManager 也要清, 不然下次启动 syncFromCookieManager 又拉回来
        try {
            val cm = android.webkit.CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        } catch (ignored: Exception) {
        }
    }

    companion object {
        @JvmField
        val BASE_URL = "https://bbs.binmt.cc/"
        @JvmField
        val MOBILE_SUFFIX = "&mobile=2"
        @JvmField
        val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        @JvmField
        val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        @Volatile
        private var instance: HttpClient? = null

        private const val PREF_NAME = "sqapp_cookies"
        private const val KEY_COOKIES = "cookies_json"

        @Volatile
        private var initialized = false

        /** 是否输出 WAF 挑战处理日志（排查站点改版时打开） */
        private const val DEBUG_WAF = true

        @JvmStatic
        fun getInstance(): HttpClient {
            val i = instance
            if (i == null) {
                synchronized(HttpClient::class.java) {
                    if (instance == null) {
                        instance = HttpClient()
                    }
                }
            }
            return instance!!
        }

        private fun guessContentType(file: File?): String {
            val name = if (file == null) "" else file.name.lowercase(java.util.Locale.ROOT)
            if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg"
            if (name.endsWith(".png")) return "image/png"
            if (name.endsWith(".gif")) return "image/gif"
            if (name.endsWith(".webp")) return "image/webp"
            if (name.endsWith(".bmp")) return "image/bmp"
            if (name.endsWith(".heic") || name.endsWith(".heif")) return "image/heic"
            return "application/octet-stream"
        }
    }
}
