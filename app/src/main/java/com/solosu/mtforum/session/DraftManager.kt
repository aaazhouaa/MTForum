package com.solosu.mtforum.session

import android.content.Context
import android.content.SharedPreferences

import org.json.JSONArray
import org.json.JSONObject

import java.util.ArrayList

/**
 * 发帖草稿箱: SharedPreferences + JSON 持久化, 最多 20 条, 按 time 倒序。
 * PostActivity 退出时自动保存(onPause), 发布成功后自动删除对应草稿。
 */
object DraftManager {
    const val PREF = "sqapp_drafts"
    private const val KEY = "draft_list"
    private const val MAX = 20

    /** 草稿条目 */
    class Entry {
        @JvmField
        var id: Long = 0
        @JvmField
        var title: String? = null
        @JvmField
        var content: String? = null
        @JvmField
        var fid: String? = null
        @JvmField
        var forumName: String? = null
        @JvmField
        var anonymous: Boolean = false
        @JvmField
        var time: Long = 0
    }

    private fun prefs(c: Context): SharedPreferences {
        return c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    private fun parse(json: String?): MutableList<Entry> {
        val out = ArrayList<Entry>()
        if (json == null || json.isEmpty()) return out
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val e = Entry()
                e.id = o.optLong("id", 0)
                e.title = o.optString("title", "")
                e.content = o.optString("content", "")
                e.fid = o.optString("fid", "")
                e.forumName = o.optString("forum", "")
                e.anonymous = o.optBoolean("anon", false)
                e.time = o.optLong("time", 0)
                // 仅标题或正文有非空内容才视为有效草稿（单独选版块不计为有效草稿）
                if (e.id > 0 && (!e.title.isNullOrBlank() || !e.content.isNullOrBlank())) out.add(e)
            }
        } catch (ignored: Exception) {
        }
        return out
    }

    /** 全部草稿, 最新在前 */
    @JvmStatic
    fun list(c: Context): MutableList<Entry> {
        val l = parse(prefs(c).getString(KEY, "[]"))
        java.util.Collections.sort(l) { a, b -> java.lang.Long.compare(b.time, a.time) }
        return l
    }

    /** 最新一条(无草稿返回 null) */
    @JvmStatic
    fun latest(c: Context): Entry? {
        val l = list(c)
        return if (l.isEmpty()) null else l[0]
    }

    @JvmStatic
    fun get(c: Context, id: Long): Entry? {
        for (e in list(c)) if (e.id == id) return e
        return null
    }

    /** 保存或更新草稿(id>0 且存在则覆盖刷新), 返回草稿 id */
    @JvmStatic
    fun saveDraft(c: Context, id: Long, title: String?, content: String?,
                  fid: String?, forumName: String?, anonymous: Boolean): Long {
        val cleanTitle = title?.trim() ?: ""
        val cleanContent = content?.trim() ?: ""
        // 无有效内容时不保存
        if (cleanTitle.isEmpty() && cleanContent.isEmpty()) {
            if (id > 0) delete(c, id)
            return 0L
        }
        val l = parse(prefs(c).getString(KEY, "[]"))
        var e: Entry? = null
        if (id > 0) {
            for (x in l) if (x.id == id) {
                e = x
                break
            }
        }
        if (e == null) {
            e = Entry()
            e.id = System.currentTimeMillis()
            l.add(e)
        }
        e.title = title ?: ""
        e.content = content ?: ""
        e.fid = fid ?: ""
        e.forumName = forumName ?: ""
        e.anonymous = anonymous
        e.time = System.currentTimeMillis()
        // 超上限丢最旧
        while (l.size > MAX) {
            var oldest: Entry? = null
            for (x in l) if (oldest == null || x.time < oldest!!.time) oldest = x
            if (oldest == null) break
            l.remove(oldest)
        }
        prefs(c).edit().putString(KEY, toJSON(l)).commit()
        return e.id
    }

    @JvmStatic
    fun delete(c: Context, id: Long) {
        if (id <= 0) return
        val l = parse(prefs(c).getString(KEY, "[]"))
        for (i in l.indices) {
            if (l[i].id == id) {
                l.removeAt(i)
                break
            }
        }
        prefs(c).edit().putString(KEY, toJSON(l)).commit()
    }

    @JvmStatic
    fun clear(c: Context) {
        prefs(c).edit().putString(KEY, "[]").commit()
    }

    private fun toJSON(l: MutableList<Entry>): String {
        try {
            val arr = JSONArray()
            for (x in l) {
                val o = JSONObject()
                o.put("id", x.id)
                o.put("title", x.title)
                o.put("content", x.content)
                o.put("fid", x.fid)
                o.put("forum", x.forumName)
                o.put("anon", x.anonymous)
                o.put("time", x.time)
                arr.put(o)
            }
            return arr.toString()
        } catch (e: Exception) {
            return "[]"
        }
    }
}
