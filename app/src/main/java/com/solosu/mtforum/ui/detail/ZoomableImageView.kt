package com.solosu.mtforum.ui.detail

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewTreeObserver
import android.widget.ImageView

import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.appcompat.widget.AppCompatImageView

/**
 * 支持双指缩放、单指拖动、双击放大/还原的 ImageView
 *
 * 历史缺陷（本次重写修复）：
 * 1. 旧实现 setScaleType(MATRIX) 后 matrix 永远是 identity（单位矩阵），
 *    大图只露左上角、小图死贴左上角，从不做适配屏幕的初始化。
 * 2. 旧 MIN_SCALE=1.0 按绝对比例限制，没有基于 fitScale 的动态上下限，
 *    图片永远缩不到适配屏幕状态。
 *
 * 重写要点：
 * - onGlobalLayout 首次布局后计算 fitScale（图完整显示进视图的最小比例），
 *   并 postTranslate 居中，作为基准矩阵 baseMatrix。
 * - 双指缩放限制在 [fitScale*0.5, fitScale*4]。
 * - 双击在 fitScale 与 fitScale*2.5 之间切换。
 * - 抬指后若缩放低于 fitScale 回弹至 fitScale。
 */
class ZoomableImageView @JvmOverloads constructor(
        @NonNull context: Context,
        @Nullable attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val baseMatrix = Matrix()  // fitScale + 居中
    private val suppMatrix = Matrix()  // 用户手势叠加
    private val drawMatrix = Matrix()
    private val savedMatrix = Matrix()

    private val scaleDetector: ScaleGestureDetector
    private val tapDetector: GestureDetector

    private var fitScale = 1.0f
    private var baseReady = false

    private var mode = NONE

    private val lastFinger = PointF()

    init {
        super.setScaleType(ImageView.ScaleType.MATRIX)
        scaleDetector = ScaleGestureDetector(context, ScaleListener())
        tapDetector = GestureDetector(context, TapListener())
        // 首次布局完成时建立基准矩阵
        viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                setupBase()
            }
        })
    }

    /** 计算基准矩阵：图片适配视图并居中（相当于 fitCenter 效果） */
    private fun setupBase() {
        val d: Drawable? = drawable
        if (d == null) {
            return
        }
        val vw = width - paddingLeft - paddingRight
        val vh = height - paddingTop - paddingBottom
        if (vw <= 0 || vh <= 0) {
            return
        }
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0 || dh <= 0) {
            return
        }
        if (baseReady) {
            return // 只初始化一次，避免每帧重算
        }
        baseReady = true
        var scale = Math.min(vw / dw, vh / dh)
        if (scale <= 0) {
            scale = 1.0f
        }
        fitScale = scale
        baseMatrix.reset()
        baseMatrix.postScale(scale, scale)
        // 居中：视图中心 - 缩放后图中心
        val tx = (vw - dw * scale) / 2.0f
        val ty = (vh - dh * scale) / 2.0f
        baseMatrix.postTranslate(tx + paddingLeft, ty + paddingTop)
        suppMatrix.reset()
        applyMatrix()
    }

    /** 图片变化时重建基准（Glide 加载完成会触发 onGlobalLayout，但 baseReady 已置位，这里主动刷新） */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
    }

    override fun setImageDrawable(@Nullable drawable: Drawable?) {
        super.setImageDrawable(drawable)
        baseReady = false // 新图，重建基准
        if (width > 0 && height > 0 && drawable != null) {
            setupBase()
        }
    }

    private fun applyMatrix() {
        drawMatrix.set(baseMatrix)
        drawMatrix.postConcat(suppMatrix)
        setImageMatrix(drawMatrix)
        invalidate()
    }

    private fun currentScale(): Float {
        val v = FloatArray(9)
        drawMatrix.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        tapDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                savedMatrix.set(suppMatrix)
                lastFinger.set(event.x, event.y)
                mode = DRAG
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                savedMatrix.set(suppMatrix)
                mode = ZOOM
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG && event.pointerCount == 1 && currentScale() > fitScale * 1.01f) {
                    val dx = event.x - lastFinger.x
                    val dy = event.y - lastFinger.y
                    suppMatrix.set(savedMatrix)
                    suppMatrix.postTranslate(dx, dy)
                    applyMatrix()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                mode = NONE
                // 低于 fitScale 回弹
                if (currentScale() < fitScale) {
                    suppMatrix.reset()
                    applyMatrix()
                }
            }
        }
        return true
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            var factor = detector.scaleFactor
            val target = currentScale() * factor
            val max = fitScale * MAX_ZOOM_FACTOR
            val min = fitScale * 0.5f
            if (target > max) {
                factor = max / currentScale()
            } else if (target < min) {
                factor = min / currentScale()
            }
            suppMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            applyMatrix()
            return true
        }
    }

    /** 单击回调（预览页用于单击关闭） */
    fun interface OnViewTapListener {
        fun onViewTap()
    }

    private var tapCallback: OnViewTapListener? = null

    fun setOnViewTapListener(l: OnViewTapListener) {
        this.tapCallback = l
    }

    private inner class TapListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val cur = currentScale()
            if (cur > fitScale * 1.01f) {
                suppMatrix.reset()
            } else {
                suppMatrix.reset()
                suppMatrix.postScale(DOUBLE_TAP_FACTOR, DOUBLE_TAP_FACTOR, e.x, e.y)
            }
            applyMatrix()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (tapCallback != null) {
                tapCallback!!.onViewTap()
            }
            return true
        }
    }

    companion object {
        private const val MAX_ZOOM_FACTOR = 4.0f   // 相对 fitScale 的最大放大倍数
        private const val DOUBLE_TAP_FACTOR = 2.5f // 双击放大倍数

        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }
}
