package com.solosu.mtforum.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.TextUtils

import com.solosu.mtforum.network.ForumParser
import com.solosu.mtforum.network.HttpClient

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自动签到管理器。
 * 流程:1.检查登录态 2.检查设置开关 3.本地签到记录 4.网络请求签到状态 5.本地判断后执行签到。
 */
object AutoSignInManager {
    private const val PREF_NAME = "app_settings"
    private const val KEY_AUTO_SIGN_IN = "auto_sign_in_enabled"
    private val CHECKING = AtomicBoolean(false)
    private val MAIN_HANDLER = Handler(Looper.getMainLooper())

    interface Callback {
        fun onFinished(success: Boolean, performed: Boolean, message: String)
    }

    @JvmStatic
    fun isEnabled(context: Context?): Boolean {
        if (context == null) return false
        return context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_SIGN_IN, false)
    }

    @JvmStatic
    fun setEnabled(context: Context?, enabled: Boolean) {
        if (context == null) return
        context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_SIGN_IN, enabled).apply()
    }

    /**
     * 自动签到流程:1.检查登录态 2.检查设置开关 3.本地签到记录 4.网络请求签到状态 5.本地判断后执行签到。
     * 每一步都顺序执行,并发锁防止重复请求。
     */
    @JvmStatic
    fun checkAndSignIn(context: Context?, callback: Callback?) {
        if (context == null) return
        val appContext = context.getApplicationContext()
        val client = HttpClient.getInstance()
        if (!CHECKING.compareAndSet(false, true)) return

        // 第一步:检查登录状态
        if (!client.isLoggedIn()) {
            CHECKING.set(false)
            if (callback != null) {
                MAIN_HANDLER.post { callback.onFinished(false, false, "请先登录") }
            }
            return
        }

        // 第二步:检查是否开启自动签到
        if (!isEnabled(context)) {
            CHECKING.set(false)
            return
        }

        // 第三步:检查本地签到记录(今天已签到则跳过,防止重复签到)
        if (UserSessionManager.getInstance().isSignedInToday(appContext)) {
            CHECKING.set(false)
            if (callback != null) {
                MAIN_HANDLER.post { callback.onFinished(true, false, "今日已签到") }
            }
            return
        }

        java.lang.Thread {
            var success = false
            var performed = false
            var message = ""
            try {
                // 第四步:网络请求获取签到状态
                val html = client.get(ForumParser.getForumlistMobileUrl())
                if (TextUtils.isEmpty(html) || ForumParser.isLoginPage(html)) {
                    success = false
                    message = "登录状态已失效"
                } else {
                    val data = ForumParser.parseCommunityPage(html)
                    val signText = data.getSignInText()

                    // 第五步:本地判断是否已签到
                    // 优先检查页面文本中是否包含"已签到"关键词(兜底:按钮文本 + 页面全文)
                    val pageShowsSignedIn = isAlreadySigned(signText)
                            || data.isAlreadySignedIn()
                            || html.contains("今日已签")
                            || html.contains("已签到")
                    if (pageShowsSignedIn) {
                        success = true
                        message = "今日已签到"
                        // 同步本地签到记录,防止下次重复请求
                        UserSessionManager.getInstance().saveSignInDate(appContext)
                    } else if (data.isLoginRequired()) {
                        message = "请先登录"
                    } else if (!isNotSigned(signText)) {
                        message = "无法确认当前签到状态"
                    } else {
                        // 未签到 -> 执行自动签到
                        var formhash: String? = data.getFormhash()
                        if (TextUtils.isEmpty(formhash)) formhash = ForumParser.parseFormhash(html)
                        if (TextUtils.isEmpty(formhash)) {
                            message = "无法获取签到凭证"
                        } else {
                            val params = HashMap<String, String>()
                            params.put("formhash", formhash!!)
                            // 注意：Kotlin 中 `BASE_URL` 是 Java 静态常量，
                            // 不要把 `+` 拆到下一行行首，否则解析失败。
                            val signUrl = HttpClient.BASE_URL +
                                    "plugin.php?id=k_misign:sign&operation=qiandao&format=text"
                            val result = client.post(signUrl, params)
                            message = cleanMessage(result)
                            success = isSignSuccess(message)
                            performed = true
                            if (success) {
                                UserSessionManager.getInstance().saveSignInDate(appContext)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                message = e.message ?: "自动签到失败"
            } finally {
                CHECKING.set(false)
            }

            if (callback != null) {
                val finalSuccess = success
                val finalPerformed = performed
                val finalMessage = message
                MAIN_HANDLER.post { callback.onFinished(finalSuccess, finalPerformed, finalMessage) }
            }
        }.start()
    }

    private fun isAlreadySigned(text: String?): Boolean {
        if (TextUtils.isEmpty(text)) return false
        val t = text!!
        return t.contains("今日已签") || t.contains("已签到") || t.contains("已签")
    }

    private fun isNotSigned(text: String?): Boolean {
        if (TextUtils.isEmpty(text)) return false
        val t = text!!
        return t.contains("签到") && !isAlreadySigned(t)
    }

    private fun isSignSuccess(text: String?): Boolean {
        if (TextUtils.isEmpty(text)) return false
        val t = text!!
        if (t.contains("失败") || t.contains("错误") || t.contains("请先登录")
                || t.contains("没有权限") || t.contains("非法操作")) return false
        return t.contains("签到成功") || t.contains("今日已签")
                || t.contains("已签到") || t.contains("成功")
                || t.lowercase().contains("success")
                || t.lowercase().contains("succeed")
    }

    private fun cleanMessage(raw: String?): String {
        if (TextUtils.isEmpty(raw)) return ""
        val r = raw!!
        // 原 Java：replaceAll("<!\\[CDATA\\[(.*?)\\]\\]>", "$1") —— 去掉 CDATA 包裹
        val text = Regex("<!\\[CDATA\\[(.*?)\\]\\]>")
                .replace(r) { m -> m.groupValues[1] }
                .replace(Regex("<[^>]+>"), "")
                .trim()
        return if (text.isEmpty()) r.trim() else text
    }
}
