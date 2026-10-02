package com.solosu.mtforum.ui.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import android.view.ViewGroup

import androidx.cardview.widget.CardView

/**
 * 毛玻璃背景统一入口:
 * 所有卡片统一使用 FrostedGlassDrawable(半透明渐变 + 高光 + 细边框),
 * 与主界面导航栏保持一致的视觉风格,自动适配亮色/暗色主题。
 */
object FrostedGlassHelper {

    /** 递归处理 root 下的所有 CardView(包含 root 自身)。 */
    @JvmStatic
    fun applyToCardViews(root: View?, context: Context?) {
        if (root == null || context == null) return
        if (root is CardView) {
            applyToCard(root, context)
        }
        if (root is ViewGroup) {
            val group = root
            for (i in 0 until group.childCount) {
                applyToCardViews(group.getChildAt(i), context)
            }
        }
    }

    /** 给单个卡片设置毛玻璃背景(与列表项一致的轻量样式)。 */
    @JvmStatic
    fun applyToCard(card: CardView?, context: Context?) {
        if (card == null || context == null) return
        val isDark = isDarkMode(context)
        val density = context.resources.displayMetrics.density
        var radius = card.radius
        if (radius <= 0f) radius = 12f * density
        card.setCardBackgroundColor(Color.TRANSPARENT)
        card.background = FrostedGlassDrawable(
                if (isDark) 0xFF1E1E1E.toInt() else 0xFFFFFFFF.toInt(), radius, density)
    }

    /** RecyclerView 卡片创建时使用。 */
    @JvmStatic
    fun applyToItem(root: View?, context: Context?) {
        applyToCardViews(root, context)
    }

    /** 兼容旧接口:无操作。 */
    @JvmStatic
    fun setVisible(root: View?, visible: Boolean) {
        // no-op
    }

    private fun isDarkMode(context: Context): Boolean {
        return (context.resources.configuration.uiMode
                and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
}
