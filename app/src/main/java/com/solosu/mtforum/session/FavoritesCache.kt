package com.solosu.mtforum.session

import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils

/**
 * 收藏数缓存：tid -> favoriteCount。
 * 列表页 DOM 没有收藏数（只有详情页 HTML 里有 comiis_favorite_a_num），
 * 进过详情页后把数字缓存起来，帖子卡片第四格统计（收藏）就能回填显示。
 * 进程内 ConcurrentHashMap 快存 + SharedPreferences 持久化兜底。
 */
object FavoritesCache {
    private val MEM: MutableMap<String, Int> = java.util.concurrent.ConcurrentHashMap()
    private const val PREFS = "thread_favorites_cache"
    private const val PREFIX = "fav_"

    /** 读取：内存命中直接返回，否则查 SharedPreferences（-1 表示无数据） */
    @JvmStatic
    fun get(c: Context?, tid: String?): Int? {
        if (TextUtils.isEmpty(tid)) return null
        val safe = tid!!
        val mem = MEM[safe]
        if (mem != null) return mem
        if (c == null) return null
        try {
            val sp: SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val v = sp.getInt(PREFIX + safe, -1)
            if (v >= 0) {
                MEM[safe] = v
                return v
            }
        } catch (ignore: Exception) {
        }
        return null
    }

    /** 详情页解析出收藏数后写入（内存 + prefs 持久化） */
    @JvmStatic
    fun put(c: Context?, tid: String?, count: Int) {
        if (TextUtils.isEmpty(tid) || c == null || count < 0) return
        val safe = tid!!
        MEM[safe] = count
        try {
            val sp: SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            sp.edit().putInt(PREFIX + safe, count).apply()
        } catch (ignore: Exception) {
        }
    }
}
