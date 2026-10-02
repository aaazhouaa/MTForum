package com.solosu.mtforum.session

import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils

import com.solosu.mtforum.network.ForumParser
import com.solosu.mtforum.network.HttpClient

import java.util.HashSet

/**
 * 全局关注状态管理器。
 *
 * 关注状态以当前登录 UID 隔离保存，避免切换账号后串号；网络成功后才写入本地，
 * 所有页面通过这里读取同一份状态。
 */
object FollowStateManager {
    private const val PREF_NAME = "sqapp_follow_state"
    private const val KEY_FOLLOWING_PREFIX = "following_"
    private const val KEY_KNOWN_PREFIX = "known_"

    private fun accountKey(context: Context): String {
        val uid = UserSessionManager.getInstance().getUid(context.getApplicationContext())
        return if (TextUtils.isEmpty(uid)) "guest" else uid!!
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private fun readSet(context: Context, key: String): MutableSet<String> {
        return HashSet(prefs(context).getStringSet(key, HashSet())!!)
    }

    @JvmStatic
    @Synchronized
    fun hasLocalState(context: Context, targetUid: String?): Boolean {
        if (TextUtils.isEmpty(targetUid)) return false
        return readSet(context, KEY_KNOWN_PREFIX + accountKey(context)).contains(targetUid)
    }

    @JvmStatic
    @Synchronized
    fun isFollowed(context: Context, targetUid: String?): Boolean {
        if (TextUtils.isEmpty(targetUid)) return false
        return readSet(context, KEY_FOLLOWING_PREFIX + accountKey(context)).contains(targetUid)
    }
    /**
     * 关注状态解析规则：
     * 1. 服务端明确返回“已关注”时立即同步为 true，顺便修复旧版本缓存的错误 false；
     * 2. 服务端返回 false 且本地有记录时，保留本地成功操作结果；
     * 3. 两边都没有明确的已关注证据时，不把 false 写入 known_，避免污染缓存。
     */
    @JvmStatic
    @Synchronized
    fun resolve(context: Context, targetUid: String?, serverState: Boolean): Boolean {
        if (TextUtils.isEmpty(targetUid)) return serverState

        // “已关注”是服务端的明确正向证据，优先用于修复旧缓存或跨页面状态。
        if (serverState) {
            setState(context, targetUid, true)
            return true
        }

        // 服务端 false 可能只是页面未提供状态；只有此前存在明确本地状态时才读取本地。
        if (hasLocalState(context, targetUid)) return isFollowed(context, targetUid)

        // 未知状态：返回 false 给当前 UI，但不缓存为“明确未关注”。
        return false
    }


    @JvmStatic
    @Synchronized
    fun setState(context: Context, targetUid: String?, followed: Boolean) {
        if (TextUtils.isEmpty(targetUid)) return
        // TextUtils.isEmpty 是 Java 方法，编译器无法据此推断非空，这里显式绑定非空引用
        val uid = targetUid!!
        val account = accountKey(context)
        val followKey = KEY_FOLLOWING_PREFIX + account
        val knownKey = KEY_KNOWN_PREFIX + account
        val following = readSet(context, followKey)
        val known = readSet(context, knownKey)
        if (followed) following.add(uid) else following.remove(uid)
        known.add(uid)
        prefs(context).edit().putStringSet(followKey, following).putStringSet(knownKey, known).apply()
    }

    @JvmStatic
    @Synchronized
    fun getFollowedCount(context: Context): Int {
        return readSet(context, KEY_FOLLOWING_PREFIX + accountKey(context)).size
    }

    @JvmStatic
    fun isLoggedIn(context: Context): Boolean {
        return HttpClient.getInstance().isLoggedIn()
                && UserSessionManager.getInstance().isLoggedIn(context.getApplicationContext())
    }
    /**
     * Read the complete following list from the server.
     * @return a UID set, or null when the response cannot be trusted
     */
    @JvmStatic
    fun queryServerFollowingUids(context: Context?): MutableSet<String>? {
        if (context == null) return null
        try {
            val client = HttpClient.getInstance()
            client.syncFromCookieManager()
            if (!client.isLoggedIn()) return null

            val currentUid = UserSessionManager.getInstance().getUid(context.getApplicationContext())
            if (TextUtils.isEmpty(currentUid)) return null

            val result = HashSet<String>()
            for (page in 1..50) {
                val url = HttpClient.BASE_URL
                        .toString() + "home.php?mod=follow&do=following&uid=" + currentUid +
                        "&mobile=2&page=" + page +
                        "&_ts=" + System.currentTimeMillis()
                val html = client.get(url)
                if (TextUtils.isEmpty(html) || ForumParser.isLoginPage(html)
                        || !ForumParser.isFollowingListPage(html)) {
                    return null
                }
                result.addAll(ForumParser.extractFollowingUids(html))
                if (!ForumParser.hasUserListPageAfter(html, page)) break
            }
            return result
        } catch (ignored: Exception) {
            return null
        }
    }

    /**
     * Directly confirm whether the current account follows a target user.
     * @return 1 followed, 0 not followed, -1 unknown
     */
    @JvmStatic
    fun queryServerFollowState(context: Context?, targetUid: String): Int {
        if (context == null || TextUtils.isEmpty(targetUid)) return -1
        val following = queryServerFollowingUids(context)
        if (following == null) return -1
        return if (following.contains(targetUid)) 1 else 0
    }



    /**
     * 调用 Comiis/Discuz! 实际使用的关注接口。必须在后台线程调用。
     *
     * 关注：home.php?mod=spacecp&ac=follow&op=add&hash={formhash}&fuid={uid}&inajax=1
     * 取消：home.php?mod=spacecp&ac=follow&op=del&fuid={uid}&inajax=1
     */
    @JvmStatic
    fun syncFollow(context: Context, targetUid: String?, follow: Boolean): Boolean {
        if (TextUtils.isEmpty(targetUid)) return false
        try {
            val client = HttpClient.getInstance()

            // 原生登录可能来自 WebView。只有 OkHttp 没有登录 Cookie 时才同步，
            // 避免用空的 WebView Cookie 覆盖已经有效的 OkHttp 会话。
            if (!client.isLoggedIn()) {
                client.syncFromCookieManager()
            }
            if (!client.isLoggedIn()) return false

            val currentUid = UserSessionManager.getInstance()
                    .getUid(context.getApplicationContext())
            val pageUrl = HttpClient.BASE_URL.toString() + "home.php?mod=space&do=profile" +
                    (if (TextUtils.isEmpty(currentUid)) "" else "&uid=" + currentUid) +
                    "&mobile=2"
            val pageHtml = client.get(pageUrl)
            var formhash = ForumParser.parseFormhash(pageHtml)

            // 移动空间页没有 hash 时，用移动版论坛首页补取；再用桌面版兜底。
            if (TextUtils.isEmpty(formhash)) {
                formhash = ForumParser.parseFormhash(
                        client.get(HttpClient.BASE_URL.toString() + "forum.php?mobile=2"))
            }
            if (TextUtils.isEmpty(formhash)) {
                formhash = ForumParser.parseFormhash(
                        client.getDesktop(HttpClient.BASE_URL.toString() + "forum.php"))
            }
            if (TextUtils.isEmpty(formhash)) return false

            val url: String
            if (follow) {
                // 注意参数名必须是 hash，目标用户参数必须是 fuid。
                url = HttpClient.BASE_URL.toString() + "home.php?mod=spacecp&ac=follow&op=add" +
                        "&hash=" + formhash + "&fuid=" + targetUid + "&inajax=1"
            } else {
                url = HttpClient.BASE_URL.toString() + "home.php?mod=spacecp&ac=follow&op=del" +
                        "&fuid=" + targetUid + "&inajax=1"
            }

            val result = client.get(url)
            if (ForumParser.isLoginPage(result)) return false

            val text = if (result == null) "" else result.lowercase()
            val operationTypeReturned = if (follow)
                text.contains("'type':'add'") || text.contains("\"type\":\"add\"") ||
                        text.contains("type=add")
            else
                text.contains("'type':'del'") || text.contains("\"type\":\"del\"") ||
                        text.contains("type=del")

            // Discuz 在重复操作时也可能直接返回提示文字；这两类结果应视为目标状态已达成。
            val alreadyInTargetState = if (follow)
                text.contains("已关注") || text.contains("已经关注")
            else
                text.contains("已取消关注") || text.contains("未关注") ||
                        text.contains("不存在")

            val failed = text.contains("formhash错误") || text.contains("请先登录") ||
                    text.contains("没有权限") || text.contains("操作失败") ||
                    text.contains("发生错误") || text.contains("失败") ||
                    text.contains("error")
            if (failed && !alreadyInTargetState) return false
            if (!operationTypeReturned && !alreadyInTargetState) return false

            setState(context, targetUid, follow)
            return true
        } catch (ignored: Exception) {
            return false
        }
    }
}
