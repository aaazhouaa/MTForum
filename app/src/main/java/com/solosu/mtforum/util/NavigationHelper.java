package com.solosu.mtforum.util;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.solosu.mtforum.model.Thread;
import com.solosu.mtforum.ui.detail.ThreadDetailActivity;

/**
 * 导航跳转辅助类
 * 统一管理页面跳转逻辑,防止重复点击,传递必要参数
 */
public class NavigationHelper {

    private static long lastClickTime;

    /**
     * 打开帖子详情(防重复点击,间隔 < 500ms 忽略)
     */
    public static void openThread(Context context, @Nullable Thread thread) {
        if (thread == null) return;
        openThread(context, thread.getTid(), thread.getTitle(), thread.getAuthor());
    }

    /**
     * 打开帖子详情(仅用 tid,用于热帖排行等场景)
     */
    public static void openThread(Context context, @Nullable String tid) {
        openThread(context, tid, null, null);
    }

    private static void openThread(Context context, @Nullable String tid,
                                   @Nullable String title, @Nullable String author) {
        if (context == null || tid == null || tid.isEmpty()) return;

        // 防重复点击:500ms 内只响应一次
        long now = SystemClock.elapsedRealtime();
        if (now - lastClickTime < 500) return;
        lastClickTime = now;

        Intent intent = new Intent(context, ThreadDetailActivity.class);
        intent.putExtra("tid", tid);
        if (title != null) intent.putExtra("title", title);
        if (author != null) intent.putExtra("author", author);
        context.startActivity(intent);
    }
}
