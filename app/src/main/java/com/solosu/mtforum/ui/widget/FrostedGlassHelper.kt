package com.solosu.mtforum.ui.widget

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.solosu.mtforum.R

/**
 * 现代界面卡片与容器视觉规范应用器：
 * 统一设置 Surface 表面色、纯净圆角与细微边缘分层，杜绝脏半透明与黑粗边框。
 */
object FrostedGlassHelper {

    /** 递归处理 root 下的所有 CardView 与视觉容器 */
    @JvmStatic
    fun applyToCardViews(root: View?, context: Context?) {
        if (root == null || context == null) return
        if (root is CardView) {
            applyToCard(root, context)
        } else if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                applyToCardViews(root.getChildAt(i), context)
            }
        }
    }

    /** 规范化卡片视觉：大圆角 + 柔和 Surface 背景 + 细微边框 */
    @JvmStatic
    fun applyToCard(card: CardView?, context: Context?) {
        if (card == null || context == null) return
        val density = context.resources.displayMetrics.density
        val surfaceColor = ContextCompat.getColor(context, R.color.surface)
        val dividerColor = ContextCompat.getColor(context, R.color.divider)

        // 规范化圆角，若未指定或较小则采用现代标准的 16dp
        if (card.radius <= 0f || card.radius < 12f * density) {
            card.radius = 16f * density
        }

        if (card is MaterialCardView) {
            card.setCardBackgroundColor(surfaceColor)
            card.strokeColor = dividerColor
            card.strokeWidth = Math.max(1, (1f * density).toInt())
            card.cardElevation = 1f * density
        } else {
            card.setCardBackgroundColor(surfaceColor)
            card.cardElevation = 1f * density
        }
    }

    /** 列表项容器处理 */
    @JvmStatic
    fun applyToItem(root: View?, context: Context?) {
        applyToCardViews(root, context)
    }

    /** 兼容旧接口 */
    @JvmStatic
    fun setVisible(root: View?, visible: Boolean) {
        // no-op
    }
}
