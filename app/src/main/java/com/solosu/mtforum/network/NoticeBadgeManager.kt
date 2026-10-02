package com.solosu.mtforum.network

import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils

import com.solosu.mtforum.model.ChatMessage
import com.solosu.mtforum.model.Message

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap
import java.util.HashSet
import java.util.TreeMap

/**
 * 消息角标快照管理器。
 *
 * Comiis 页面不提供可靠的 unread 标记，因此不再依赖 Message.isRead()。
 * 角标含义为：当前服务器列表相对于用户上次进入该分类时，新增的内容数量。
 *
 * PM 使用“会话内消息总数”计算差值；粉丝使用新增 UID 数量；
 * 其它通知使用通知内容指纹计算新增条目数量。
 */
object NoticeBadgeManager {
    private const val PREF_NAME = "notice_badge_snapshot_v2"
    private const val KEY_BASELINE_PREFIX = "baseline_"
    private const val KEY_CURRENT_PREFIX = "current_"
    private const val KEY_PENDING_PREFIX = "pending_"

    /** build83: 分类被查看时回调. MainActivity 注册后可在本地即时清零红点, 无需等 60s 轮询, 零额外请求 */
    interface OnViewedListener {
        fun onViewed(viewType: String?)
    }

    @Volatile
    private var sViewedListener: OnViewedListener? = null

    @JvmStatic
    fun setOnViewedListener(listener: OnViewedListener?) {
        sViewedListener = listener
    }

    private fun fireViewed(viewType: String?) {
        val listener = sViewedListener ?: return
        try {
            listener.onViewed(viewType)
        } catch (ignored: Exception) {
        }
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 根据当前 HTML 建立规范化快照。
     * PM 会继续请求每个会话的详情页，统计当前可见聊天消息总数。
     */
    @JvmStatic
    fun buildSnapshot(viewType: String?, html: String?, client: HttpClient?): String {
        if (TextUtils.isEmpty(html)) return ""

        var items: MutableList<Message>
        if ("pm" == viewType) items = ForumParser.parsePmList(html)
        else if ("follower" == viewType) items = ForumParser.parseFollowerList(html)
        else items = ForumParser.parseNoticeList(html)

        if ("pm" == viewType) return buildPmSnapshot(items, client)
        if ("follower" == viewType) return buildFollowerSnapshot(items)
        return buildNoticeSnapshot(items)
    }

    private fun buildPmSnapshot(items: MutableList<Message>, client: HttpClient?): String {
        val totals: MutableMap<String, Int> = TreeMap()
        val seen = HashSet<String>()
        for (item in items) {
            val uid = firstNonEmpty(item.authorUid, item.pmid, item.author)
            if (TextUtils.isEmpty(uid)) continue
            val key = sha256(uid)
            if (!seen.add(key)) continue

            // 列表页通常只有会话预览，详情页才包含实际消息条数。
            var total = 1
            if (client != null && !TextUtils.isEmpty(item.pmid)) {
                try {
                    val url = HttpClient.BASE_URL +
                            "home.php?mod=space&do=pm&subop=view&touid=" +
                            item.pmid + "&mobile=2&_badge_ts=" +
                            System.currentTimeMillis()
                    val detail = client.get(url)
                    val chat = ForumParser.parseChatMessages(
                        detail, "", item.avatarUrl
                    )
                    if (chat != null && chat.isNotEmpty()) {
                        var incoming = 0
                        for (message in chat) {
                            if (!message.outgoing) incoming++
                        }
                        // 只统计对方发来的消息；用户自己的回复不应增加未读角标。
                        total = incoming
                    }
                } catch (ignored: Exception) {
                    // 详情请求失败时保留列表中的一条会话消息，避免将该会话丢失。
                }
            }
            totals[key] = total
        }

        val result = StringBuilder()
        for ((key, value) in totals) {
            result.append(key).append('=').append(value).append(';')
        }
        return result.toString()
    }

    private fun buildFollowerSnapshot(items: MutableList<Message>): String {
        val ids = HashSet<String>()
        for (item in items) {
            val uid = firstNonEmpty(item.authorUid, item.author, item.title)
            if (!TextUtils.isEmpty(uid)) ids.add(sha256(uid))
        }
        val sorted = ArrayList(ids)
        Collections.sort(sorted)
        return join(sorted)
    }

    private fun buildNoticeSnapshot(items: MutableList<Message>): String {
        val ids = HashSet<String>()
        for (item in items) {
            ids.add(sha256(stableNoticeKey(item)))
        }
        val sorted = ArrayList(ids)
        Collections.sort(sorted)
        return join(sorted)
    }

    private fun stableNoticeKey(item: Message): String {
        val id = firstNonEmpty(item.pmid, "")
        if (!TextUtils.isEmpty(id)) return "id|" + id
        return "content|" + firstNonEmpty(item.authorUid, "") + "|" +
                firstNonEmpty(item.author, "") + "|" +
                firstNonEmpty(item.title, "") + "|" +
                firstNonEmpty(item.summary, "")
    }

    /**
     * 保存当前快照并返回相对于上次查看基线的新增数量。
     * 首次运行只建立基线，返回 0。
     */
    @JvmStatic
    @Synchronized
    fun saveCurrentAndGetNewCount(context: Context?, viewType: String?, snapshot: String?): Int {
        if (context == null || TextUtils.isEmpty(viewType)) return 0
        val p = prefs(context)
        val baselineKey = KEY_BASELINE_PREFIX + viewType
        val currentKey = KEY_CURRENT_PREFIX + viewType
        val pendingKey = KEY_PENDING_PREFIX + viewType
        val oldBaseline = p.getString(baselineKey, null)

        if (p.getBoolean(pendingKey, false) || oldBaseline == null) {
            p.edit().putString(baselineKey, if (snapshot == null) "" else snapshot)
                .putString(currentKey, if (snapshot == null) "" else snapshot)
                .putBoolean(pendingKey, false)
                .apply()
            return 0
        }

        val count = calculateNewCount(viewType, oldBaseline, snapshot)
        p.edit().putString(currentKey, if (snapshot == null) "" else snapshot).apply()
        return count
    }

    /** 点击进入某分类时，将该分类当前内容标记为已查看。 */
    @JvmStatic
    @Synchronized
    fun markViewed(context: Context?, viewType: String?) {
        if (context == null || TextUtils.isEmpty(viewType)) return
        val p = prefs(context)
        val current = p.getString(KEY_CURRENT_PREFIX + viewType, null)
        val editor = p.edit()
        if (current == null) {
            editor.putBoolean(KEY_PENDING_PREFIX + viewType, true)
        } else {
            editor.putString(KEY_BASELINE_PREFIX + viewType, current)
                .putBoolean(KEY_PENDING_PREFIX + viewType, false)
        }
        editor.apply()
        fireViewed(viewType)
    }

    /** 手动“全部已读”时同步重置所有分类基线。 */
    @JvmStatic
    @Synchronized
    fun markAllViewed(context: Context?) {
        if (context == null) return
        val types = arrayOf("pm", "follower", "mypost", "interactive", "system", "app")
        for (type in types) markViewed(context, type)
        fireViewed("*")
    }

    private fun calculateNewCount(viewType: String?, baseline: String?, current: String?): Int {
        if (TextUtils.isEmpty(current)) return 0
        if ("pm" == viewType) return calculatePmDelta(baseline, current)

        val oldSet = parseSet(baseline)
        var count = 0
        for (key in parseSet(current)) if (!oldSet.contains(key)) count++
        return count
    }

    private fun calculatePmDelta(baseline: String?, current: String?): Int {
        val oldMap = parsePmMap(baseline)
        val currentMap = parsePmMap(current)
        var count = 0
        for ((key, value) in currentMap) {
            val old = if (oldMap.containsKey(key)) oldMap[key]!! else 0
            if (value > old) count += value - old
        }
        return count
    }

    private fun parseSet(value: String?): MutableSet<String> {
        val result = HashSet<String>()
        if (TextUtils.isEmpty(value)) return result
        for (item in value!!.split(";")) {
            if (!TextUtils.isEmpty(item)) result.add(item)
        }
        return result
    }

    private fun parsePmMap(value: String?): MutableMap<String, Int> {
        val result = HashMap<String, Int>()
        if (TextUtils.isEmpty(value)) return result
        for (item in value!!.split(";")) {
            val index = item.lastIndexOf('=')
            if (index <= 0) continue
            try {
                result[item.substring(0, index)] = item.substring(index + 1).toInt()
            } catch (ignored: Exception) {
            }
        }
        return result
    }

    private fun join(values: MutableList<String>): String {
        val result = StringBuilder()
        for (value in values) result.append(value).append(';')
        return result.toString()
    }

    private fun firstNonEmpty(vararg values: String?): String {
        for (value in values) if (!TextUtils.isEmpty(value)) return value!!
        return ""
    }

    private fun sha256(value: String?): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(
                (if (value == null) "" else value)
                    .toByteArray(StandardCharsets.UTF_8)
            )
            val result = StringBuilder()
            for (b in bytes) result.append(String.format("%02x", b.toInt() and 0xff))
            result.toString()
        } catch (ignored: Exception) {
            (if (value == null) "" else value).hashCode().toString()
        }
    }
}
