package com.solosu.mtforum.ui.widget

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable


/**
 * 轻量玻璃背景(用于列表项/导航栏等高频刷新场景):
 * 半透明渐变底 + 顶部高光 + 细边框,简洁通透,绘制开销极小。

 */
class FrostedGlassDrawable(
    private val fillColor: Int,
    private val radius: Float,
    private val density: Float
) : Drawable() {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        bgPaint.style = Paint.Style.FILL
        borderPaint.style = Paint.Style.STROKE
        borderPaint.strokeWidth = Math.max(0.5f, 0.75f * density)
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rect.set(bounds.left.toFloat(), bounds.top.toFloat(),
                bounds.right.toFloat(), bounds.bottom.toFloat())
        updateShaders()
    }

    private fun updateShaders() {
        if (rect.width() <= 0 || rect.height() <= 0) return
        val r = Color.red(fillColor)
        val g = Color.green(fillColor)
        val b = Color.blue(fillColor)
        // 顶部稍亮 62% → 底部 50%,留出背景透光
        bgPaint.shader = LinearGradient(
                rect.left, rect.top, rect.left, rect.bottom,
                Color.argb(158, r, g, b),
                Color.argb(128, r, g, b),
                Shader.TileMode.CLAMP)
        // 边框:按底色明暗自适应 — 浅底配深灰细边框(日间可见),深底配白色细边框(夜间)
        val lightBg = (r * 299 + g * 587 + b * 114) / 1000 > 128
        borderPaint.shader = null
        borderPaint.color = if (lightBg)
            Color.argb(45, 0, 0, 0)
        else
            Color.argb(70, 255, 255, 255)
    }

    override fun draw(canvas: Canvas) {
        if (rect.width() <= 0 || rect.height() <= 0) return
        canvas.drawRoundRect(rect, radius, radius, bgPaint)
        canvas.drawRoundRect(rect, radius, radius, borderPaint)
    }

    override fun setAlpha(alpha: Int) {
        bgPaint.alpha = alpha
        borderPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bgPaint.colorFilter = colorFilter
        borderPaint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int {
        return PixelFormat.TRANSLUCENT
    }

    companion object {

        /**
         * 暗色感知工厂:按当前主题自动选择填充色,radiusDp 为圆角半径(dp)。
         */
        @JvmStatic
        fun create(context: android.content.Context, radiusDp: Float): FrostedGlassDrawable {
            // 注意：不要把 `(uiMode and MASK)` 与 `==` 拆成两行——
            // Kotlin 会把表达式断在括号处，导致类型不匹配。
            val mode = context.resources.configuration.uiMode
            val isDark = (mode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            val density = context.resources.displayMetrics.density
            return FrostedGlassDrawable(
                    if (isDark) 0xFF1E1E1E.toInt() else 0xFFFFFFFF.toInt(),
                    radiusDp * density, density)
        }
    }
}
