package com.solosu.mtforum.util

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View

/**
 * 配合 Glide 异步加载的占位 Drawable（用于 Html.ImageGetter）
 * Html.fromHtml 同步调用 getDrawable 时先返回本占位对象，
 * Glide 加载完成后回调 setReal() 填充真实图并触发宿主 TextView 重绘。
 * ImageSpan 持有的是本对象引用，draw() 委托内部 real，实现异步回显。
 */
class UrlDrawable(targetView: View, sizePx: Int) : ColorDrawable(0x00000000) {

    private var real: Drawable? = null
    private var target: View? = null

    init {
        this.target = targetView
        setBounds(0, 0, sizePx, sizePx)
    }

    /** Glide 加载完成回填真实图，并请求宿主重绘 */
    fun setReal(drawable: Drawable?, targetView: View?) {
        this.real = drawable
        this.target = targetView
        val drawableLocal = drawable
        if (drawableLocal != null) {
            val db = drawableLocal.bounds
            if (db.width() > 0 && db.height() > 0) {
                setBounds(db)
            } else {
                drawableLocal.bounds = getBounds()
            }
        }
        if (targetView is android.widget.TextView) {
            // 图片尺寸变化后必须重新排版，否则图片框停留占位大小
            val tv: android.widget.TextView = targetView
            tv.post(Runnable {
                try {
                    val cs = tv.text
                    tv.text = cs
                } catch (ignore: Exception) {
                }
            })
        } else if (targetView != null) {
            targetView.postInvalidate()
        }
    }

    /**
     * 仅回填真实图并重绘，**不**重新 setText。
     *
     * 供 RecyclerView item 使用：那里 item 会被回收复用，setText 式的重排延迟到
     * 下一帧、而此时 ViewHolder 可能已绑到别的条目，既造成跳动又白白多一次全量排版。
     * 代价是行高按占位尺寸（表情 24dp）计算——评论内联图绝大多数是表情，够用。
     */
    fun setRealNoRelayout(drawable: Drawable?) {
        this.real = drawable
        val drawableLocal = drawable ?: return
        drawableLocal.setBounds(getBounds())
        target?.postInvalidate()
    }

    override fun draw(canvas: Canvas) {
        val realLocal = real
        if (realLocal != null) {
            realLocal.bounds = bounds
            realLocal.draw(canvas)
        } else {
            super.draw(canvas)
        }
    }

    override fun getIntrinsicWidth(): Int {
        val realLocal = real
        return if (realLocal != null) realLocal.getIntrinsicWidth() else getBounds().width()
    }

    override fun getIntrinsicHeight(): Int {
        val realLocal = real
        return if (realLocal != null) realLocal.getIntrinsicHeight() else getBounds().height()
    }

    override fun setAlpha(alpha: Int) {
        val realLocal = real
        if (realLocal != null) {
            realLocal.setAlpha(alpha)
        }
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        val realLocal = real
        if (realLocal != null) {
            realLocal.setColorFilter(colorFilter)
        }
    }

    override fun getOpacity(): Int {
        val realLocal = real
        return if (realLocal != null) realLocal.getOpacity() else PixelFormat.TRANSPARENT
    }
}

/**
 * 把内部 Drawable 裁剪成圆角矩形后再绘制。
 * Html.ImageGetter 得到的是内联 ImageSpan，无法用 View 背景做圆角，只能在绘制层面裁剪。
 */
class RoundedImageDrawable(
    private val content: Drawable,
    private val radiusPx: Float
) : Drawable() {

    private val path = Path()
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        rect.set(b)
        path.reset()
        path.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(path)
        content.bounds = b
        content.draw(canvas)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        content.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        content.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = content.intrinsicWidth

    override fun getIntrinsicHeight(): Int = content.intrinsicHeight
}
