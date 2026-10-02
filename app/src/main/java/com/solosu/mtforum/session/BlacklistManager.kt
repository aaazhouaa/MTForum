package com.solosu.mtforum.session

import android.content.Context
import android.content.SharedPreferences

import org.json.JSONArray
import org.json.JSONObject

import java.util.ArrayList
import java.util.HashSet
import java.util.Set

/**
 * 个人小黑屋:三源黑名单合并(对齐网页油猴脚本原理)
 * ① 个人黑名单(本机 SharedPreferences,uid+username+time)
 * ② 系统「我的屏蔽」(网页端 localStorage shieldList,Discuz 屏蔽规则 option=uid/user)
 * ③ 服务端黑名单(Discuz home.php?mod=space&do=friend&view=blacklist,需登录)
 * 过滤:列表/回帖按 uid 命中即隐藏;主题帖作者命中隐藏整个帖子。
 */
object BlacklistManager {
    const val PREF = "sqapp_blacklist"
    private const val KEY_LOCAL = "local_list"      // [{uid,user,time}]
    private const val KEY_SERVER = "server_list"    // [uid,...]
    private const val KEY_SERVER_TS = "server_ts"   // 上次同步时间戳
    private const val SERVER_SYNC_INTERVAL = 7 * 24 * 3600 * 1000L // 7天

    /** 条目:uid+用户名+拉黑时间+来源 */
    class Entry(uid: String?, user: String?, time: Long, source: String?) {
        @JvmField
        var uid: String = if (uid == null) "" else uid
        @JvmField
        var user: String = if (user == null) "" else user
        @JvmField
        var time: Long = time
        @JvmField
        var source: String = if (source == null) "local" else source
    }

    private fun prefs(c: Context): SharedPreferences {
        return c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    // ═══ 个人黑名单(本机) ═══

    @JvmStatic
    fun getLocalList(c: Context): MutableList<Entry> {
        return parseEntries(prefs(c).getString(KEY_LOCAL, "[]"), "local")
    }

    @JvmStatic
    fun addLocal(c: Context, uid: String?, user: String?): Boolean {
        if (uid == null || uid.isEmpty()) return false
        val list = getLocalList(c)
        for (e in list) {
            if (uid == e.uid) return true // 已在
        }
        list.add(Entry(uid, user, System.currentTimeMillis(), "local"))
        return prefs(c).edit().putString(KEY_LOCAL, toJSON(list)).commit()
    }

    @JvmStatic
    fun removeLocal(c: Context, uid: String?): Boolean {
        val list = getLocalList(c)
        var removed = false
        for (i in list.size - 1 downTo 0) {
            if (uid != null && uid == list[i].uid) {
                list.removeAt(i)
                removed = true
            }
        }
        if (removed) prefs(c).edit().putString(KEY_LOCAL, toJSON(list)).commit()
        return removed
    }

    @JvmStatic
    fun clearLocal(c: Context): Boolean {
        return prefs(c).edit().putString(KEY_LOCAL, "[]").commit()
    }

    // ═══ 服务端黑名单(Discuz blacklist 页) ═══

    /** 判断是否需要同步:从未同步过或距上次超过 7 天 */
    @JvmStatic
    fun needsServerSync(c: Context): Boolean {
        val ts = prefs(c).getLong(KEY_SERVER_TS, 0)
        return ts == 0L || (System.currentTimeMillis() - ts) > SERVER_SYNC_INTERVAL
    }

    @JvmStatic
    fun getServerList(c: Context): MutableList<Entry> {
        val out = ArrayList<Entry>()
        val a = parseArray(prefs(c).getString(KEY_SERVER, "[]"))
        for (i in 0 until a.length()) {
            var uid: String?
            val user: String
            val o: Any? = a.opt(i)
            if (o is JSONObject) {
                uid = o.optString("uid", "")
                user = o.optString("user", "")
            } else {
                uid = a.optString(i, "")
                user = ""
            }
            if (uid != null && !uid.isEmpty()) out.add(Entry(uid, user, 0, "server"))
        }
        return out
    }

    /** 本地移除服务端条目(下次 7 天同步会重新拉取) */
    @JvmStatic
    fun removeServer(c: Context, uid: String?): Boolean {
        val list = getServerList(c)
        var removed = false
        for (i in list.size - 1 downTo 0) {
            if (uid != null && uid == list[i].uid) {
                list.removeAt(i)
                removed = true
            }
        }
        if (removed) {
            val a = JSONArray()
            for (e in list) {
                try {
                    a.put(JSONObject().put("uid", e.uid).put("user", e.user))
                } catch (ignore: Exception) {
                }
            }
            prefs(c).edit().putString(KEY_SERVER, a.toString()).commit()
        }
        return removed
    }

    @JvmStatic
    fun saveServerList(c: Context, entries: MutableList<Entry>) {
        val a = JSONArray()
        for (e in entries) {
            if (e.uid.isEmpty()) continue
            try {
                a.put(JSONObject().put("uid", e.uid).put("user", e.user))
            } catch (ignore: Exception) {
            }
        }
        prefs(c).edit().putString(KEY_SERVER, a.toString()).putLong(KEY_SERVER_TS, System.currentTimeMillis()).commit()
    }

    // ═══ 合并集合(过滤用) ═══

    /** 三源合并 uid 集合:个人+服务端(系统「我的屏蔽」为网页端 localStorage,App 无法读取,跳过) */
    @JvmStatic
    fun uidSet(c: Context): MutableSet<String> {
        val set = HashSet<String>()
        for (e in getLocalList(c)) if (!e.uid.isEmpty()) set.add(e.uid)
        for (e in getServerList(c)) if (!e.uid.isEmpty()) set.add(e.uid)
        return set
    }

    @JvmStatic
    fun isBlack(c: Context, uid: String?): Boolean {
        if (uid == null || uid.isEmpty()) return false
        return uidSet(c).contains(uid)
    }

    @JvmStatic
    fun isEmpty(c: Context): Boolean {
        return getLocalList(c).isEmpty() && getServerList(c).isEmpty()
    }

    @JvmStatic
    fun size(c: Context): Int {
        return getLocalList(c).size + getServerList(c).size
    }

    // ═══ 序列化 ═══

    private fun toJSON(list: MutableList<Entry>): String {
        val a = JSONArray()
        for (e in list) {
            try {
                a.put(JSONObject().put("uid", e.uid).put("user", e.user).put("time", e.time))
            } catch (ignore: Exception) {
            }
        }
        return a.toString()
    }

    private fun parseArray(raw: String?): JSONArray {
        return try {
            JSONArray(if (raw == null) "[]" else raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun parseEntries(raw: String?, source: String): MutableList<Entry> {
        val out = ArrayList<Entry>()
        val a = parseArray(raw)
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val uid = o.optString("uid", "")
            val user = o.optString("user", "")
            val time = o.optLong("time", 0)
            if (uid != null && !uid.isEmpty()) out.add(Entry(uid, user, time, source))
        }
        return out
    }
}
