package com.solosu.mtforum.util

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
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
            drawableLocal.setBounds(getBounds())
        }
        if (targetView is android.widget.TextView) {
            // 图片尺寸变化后必须重新排版，否则图片框停留占位大小
            val tv: android.widget.TextView = targetView
            tv.post(Runnable {
                try {
                    tv.setText(tv.getText())
                } catch (ignore: Exception) {
                }
            })
        } else if (targetView != null) {
            targetView.postInvalidate()
        }
    }

    override fun draw(canvas: Canvas) {
        val realLocal = real
        if (realLocal != null) {
            realLocal.setBounds(getBounds())
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
