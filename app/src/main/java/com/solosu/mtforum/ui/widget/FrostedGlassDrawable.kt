package com.solosu.mtforum.ui.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.solosu.mtforum.R

/**
 * 现代高质感表面背景：
 * 纯净 Surface 材质 + 极细微精致描边，告别发脏半透明与粗糙黑边，
 * 兼具清晰的层级感与极高的绘制性能。
 */
class FrostedGlassDrawable(
    private val fillColor: Int,
    private val strokeColor: Int,
    private val radius: Float,
    private val density: Float
) : Drawable() {

    constructor(fillColor: Int, radius: Float, density: Float) : this(fillColor, 0, radius, density)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val insetRect = RectF()

    init {
        bgPaint.style = Paint.Style.FILL
        bgPaint.color = fillColor

        borderPaint.style = Paint.Style.STROKE
        borderPaint.strokeWidth = Math.max(1f, 1f * density)
        borderPaint.color = strokeColor
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rect.set(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat()
        )
        val halfStroke = borderPaint.strokeWidth / 2f
        insetRect.set(
            rect.left + halfStroke,
            rect.top + halfStroke,
            rect.right - halfStroke,
            rect.bottom - halfStroke
        )
    }

    override fun draw(canvas: Canvas) {
        if (rect.width() <= 0 || rect.height() <= 0) return
        // 绘制纯净背景
        canvas.drawRoundRect(rect, radius, radius, bgPaint)
        // 绘制内嵌极细精致描边
        if (borderPaint.strokeWidth > 0 && Color.alpha(strokeColor) > 0) {
            canvas.drawRoundRect(insetRect, radius, radius, borderPaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        bgPaint.alpha = alpha
        borderPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bgPaint.colorFilter = colorFilter
        borderPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int {
        return if (Color.alpha(fillColor) == 255) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT
    }

    companion object {

        /**
         * 暗色感知工厂：自动获取设计规范中的 Surface 与 Divider 色值
         */
        @JvmStatic
        fun create(context: Context, radiusDp: Float): FrostedGlassDrawable {
            val mode = context.resources.configuration.uiMode
            val isDark = (mode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val density = context.resources.displayMetrics.density

            val bg = ContextCompat.getColor(context, R.color.surface)
            val stroke = ContextCompat.getColor(context, R.color.divider)

            return FrostedGlassDrawable(
                bg,
                stroke,
                radiusDp * density,
                density
            )
        }

        /**
         * 胶囊/按钮等半透明轻衬底工厂（用于小图标微背景）
         */
        @JvmStatic
        fun createSubtle(context: Context, radiusDp: Float): FrostedGlassDrawable {
            val density = context.resources.displayMetrics.density
            val bg = ContextCompat.getColor(context, R.color.background_secondary)
            val stroke = ContextCompat.getColor(context, R.color.divider)

            return FrostedGlassDrawable(
                bg,
                stroke,
                radiusDp * density,
                density
            )
        }
    }
}
