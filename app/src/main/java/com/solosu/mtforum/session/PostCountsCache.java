package com.solosu.mtforum.session;

import android.text.TextUtils;

/**
 * build80: 帖子点赞数进程内缓存 (tid -> likes)。
 * 列表页 DOM 的点赞数与详情页操作后的真实值可能不同步；
 * 详情页点赞/取消点赞后把最新数字写入这里，返回列表页时按 tid 回填，
 * 使"点赞后退出到帖子列表"能立刻看到新数字，无需整页重拉。
 */
public class PostCountsCache {

    private static final java.util.Map<String, Integer> LIKES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private PostCountsCache() {}

    /** 写入最新点赞数 */
    public static void setLikes(String tid, int likes) {
        if (TextUtils.isEmpty(tid) || likes < 0) return;
        LIKES.put(tid, likes);
    }

    /** 读取最新点赞数, 无则返回 null */
    public static Integer getLikes(String tid) {
        if (TextUtils.isEmpty(tid)) return null;
        return LIKES.get(tid);
    }
}
