package com.solosu.mtforum.session;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * 收藏数缓存：tid -> favoriteCount。
 * 列表页 DOM 没有收藏数（只有详情页 HTML 里有 comiis_favorite_a_num），
 * 进过详情页后把数字缓存起来，帖子卡片第四格统计（收藏）就能回填显示。
 * 进程内 ConcurrentHashMap 快存 + SharedPreferences 持久化兜底。
 */
public class FavoritesCache {
    private static final java.util.Map<String, Integer> MEM = new java.util.concurrent.ConcurrentHashMap<>();
    private static final String PREFS = "thread_favorites_cache";
    private static final String PREFIX = "fav_";

    /** 读取：内存命中直接返回，否则查 SharedPreferences（-1 表示无数据） */
    public static Integer get(Context c, String tid) {
        if (TextUtils.isEmpty(tid)) return null;
        Integer mem = MEM.get(tid);
        if (mem != null) return mem;
        if (c == null) return null;
        try {
            SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            int v = sp.getInt(PREFIX + tid, -1);
            if (v >= 0) {
                MEM.put(tid, v);
                return v;
            }
        } catch (Exception ignore) { }
        return null;
    }

    /** 详情页解析出收藏数后写入（内存 + prefs 持久化） */
    public static void put(Context c, String tid, int count) {
        if (TextUtils.isEmpty(tid) || c == null || count < 0) return;
        MEM.put(tid, count);
        try {
            SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit().putInt(PREFIX + tid, count).apply();
        } catch (Exception ignore) { }
    }
}
