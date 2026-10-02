package com.solosu.mtforum.util

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView

/**
 * 顶栏双击快速回到顶部辅助工具
 */
object ScrollToTopHelper {

    @JvmStatic
    fun attach(topBar: View?, onScrollToTop: () -> Unit) {
        if (topBar == null) return

        var lastClickTime = 0L
        val threshold = 400L

        // 1. 常规点击双击识别
        val clickListener = View.OnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastClickTime <= threshold) {
                lastClickTime = 0L
                onScrollToTop()
            } else {
                lastClickTime = now
            }
        }
        topBar.setOnClickListener(clickListener)

        // 2. 手势探测器辅助双击识别（针对某些消费触摸事件但未触发常规 click 的顶栏）
        val gestureDetector = GestureDetector(topBar.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                onScrollToTop()
                return true
            }
        })

        topBar.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    @JvmStatic
    fun attachRecyclerView(topBar: View?, recyclerView: RecyclerView?) {
        attach(topBar) {
            recyclerView?.smoothScrollToPosition(0)
        }
    }

    @JvmStatic
    fun attachNestedScrollView(topBar: View?, scrollView: NestedScrollView?) {
        attach(topBar) {
            scrollView?.smoothScrollTo(0, 0)
        }
    }

    @JvmStatic
    fun attachScrollView(topBar: View?, scrollView: ScrollView?) {
        attach(topBar) {
            scrollView?.smoothScrollTo(0, 0)
        }
    }
}
