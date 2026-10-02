package com.solosu.mtforum.util

import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * 自动扶梯式透视卷动退场动效（Perspective Escalator Scroll Exit）
 * 1. 严格限制仅在紧贴顶栏或底栏极边缘（48dp）的微小区域生效，屏幕主视区绝不触发任何动画变形。
 * 2. 模拟自动扶梯梳齿板卷入：卡片到达顶栏边缘时，以边缘为轴向后翻卷、沉入滚轴内部自然消失。
 */
object PerspectiveFoldScrollHelper {

    @JvmStatic
    fun attach(recyclerView: RecyclerView?) {
        if (recyclerView == null) return

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                applyEscalatorExit(rv)
            }
        })

        recyclerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyEscalatorExit(recyclerView)
        }
    }

    private fun applyEscalatorExit(recyclerView: RecyclerView) {
        val rvHeight = recyclerView.height
        if (rvHeight <= 0) return

        val density = recyclerView.resources.displayMetrics.density
        // 极窄卷入区域：仅 48dp
        val exitZone = 48f * density

        val count = recyclerView.childCount
        for (i in 0 until count) {
            val child = recyclerView.getChildAt(i) ?: continue
            val top = child.top.toFloat()
            val bottom = child.bottom.toFloat()

            // 1. 顶栏极边缘卷入：卡片顶边刚刚滑入顶栏边缘极窄 48dp 区域内
            if (top < exitZone && bottom > 0f) {
                val progress = ((exitZone - top) / (exitZone * 1.6f)).coerceIn(0f, 1f)

                // 扶梯卷轴轴心：卡片顶端边缘
                child.pivotX = child.width / 2f
                child.pivotY = 0f

                // 向后向内立体翻卷沉入
                child.rotationX = -progress * 32f
                child.scaleX = 1f - (progress * 0.06f)
                child.scaleY = 1f - (progress * 0.06f)
                child.translationY = -progress * (14f * density)
                child.translationZ = -progress * 12f
                child.alpha = (1f - progress * 1.25f).coerceIn(0f, 1f)
            }
            // 2. 底栏极边缘卷入：卡片底边滑到底栏边缘极窄 48dp 区域内
            else if (bottom > (rvHeight - exitZone) && top < rvHeight) {
                val progress = ((bottom - (rvHeight - exitZone)) / (exitZone * 1.6f)).coerceIn(0f, 1f)

                // 扶梯卷轴轴心：卡片底端边缘
                child.pivotX = child.width / 2f
                child.pivotY = child.height.toFloat()

                // 向后向内立体翻卷沉入底栏
                child.rotationX = progress * 32f
                child.scaleX = 1f - (progress * 0.06f)
                child.scaleY = 1f - (progress * 0.06f)
                child.translationY = progress * (14f * density)
                child.translationZ = -progress * 12f
                child.alpha = (1f - progress * 1.25f).coerceIn(0f, 1f)
            }
            // 3. 正常可视区域（屏幕 90% 以上大面积范围）：绝对平整，100% 原始形态无变形
            else {
                if (child.rotationX != 0f || child.scaleX != 1f || child.alpha != 1f || child.translationY != 0f) {
                    child.rotationX = 0f
                    child.scaleX = 1f
                    child.scaleY = 1f
                    child.alpha = 1f
                    child.translationY = 0f
                    child.translationZ = 0f
                }
            }
        }
    }
}
