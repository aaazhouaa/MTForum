package com.solosu.mtforum.util

import android.content.Context
import android.content.Intent
import android.os.SystemClock

import com.solosu.mtforum.model.Thread
import com.solosu.mtforum.ui.detail.ThreadDetailActivity

/**
 * 导航跳转辅助类
 * 统一管理页面跳转逻辑,防止重复点击,传递必要参数
 */
object NavigationHelper {

    private var lastClickTime: Long = 0

    /**
     * 打开帖子详情(防重复点击,间隔 < 500ms 忽略)
     */
    @JvmStatic
    fun openThread(context: Context, thread: Thread?) {
        if (thread == null) return
        openThread(context, thread.tid, thread.title, thread.author, thread)
    }

    /**
     * 打开帖子详情(仅用 tid,用于热帖排行等场景)
     */
    @JvmStatic
    fun openThread(context: Context, tid: String?) {
        openThread(context, tid, null, null, null)
    }

    private fun openThread(context: Context, tid: String?,
                           title: String?, author: String?, thread: Thread?) {
        if (context == null || tid == null || tid.isEmpty()) return

        // 防重复点击:500ms 内只响应一次
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickTime < 500) return
        lastClickTime = now

        val intent = Intent(context, ThreadDetailActivity::class.java)
        intent.putExtra("tid", tid)
        if (title != null) intent.putExtra("title", title)
        if (author != null) intent.putExtra("author", author)
        if (thread != null) {
            val imgs = ArrayList<String>()
            if (thread.imageUrls.isNotEmpty()) {
                imgs.addAll(thread.imageUrls)
            } else if (!thread.thumbnailUrl.isNullOrEmpty()) {
                imgs.add(thread.thumbnailUrl!!)
            }
            if (imgs.isNotEmpty()) {
                intent.putStringArrayListExtra("extra_image_urls", imgs)
            }
        }
        context.startActivity(intent)
    }
}
