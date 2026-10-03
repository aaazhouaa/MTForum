package com.solosu.mtforum.ui.detail

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.view.animation.DecelerateInterpolator

import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.appcompat.widget.AppCompatImageView

/**
 * 支持双指缩放、单指拖动、双击放大/还原的 ImageView
 *
 * 重写要点：
 * - onGlobalLayout 首次布局后计算 fitScale（图完整显示进视图的最小比例），
 *   并 postTranslate 居中，作为基准矩阵 baseMatrix。
 * - 双指缩放限制在 [fitScale*0.5, fitScale*4]。
 * - 双击在 fitScale 与 fitScale*2.5 之间平滑过渡（ValueAnimator，不再瞬间跳变）。
 *
 * 本次修复“放大后拖动不流畅、不易操作”的三个根因：
 * 1. 平移无边界约束：放大后能把图整个拖出屏幕，之后图就消失、拉不回来。
 *    现在拖动/缩放后都会把可视区域夹回视图内（小于视图时强制居中）。
 * 2. 未申请父级不拦截触摸：预览页外层是 ViewPager2，放大后横向平移会与翻页
 *    手势互相抢夺，表现为拖动一顿一顿、或突然翻到下一张。现在在“已放大”或
 *    “多指”时调用 requestDisallowInterceptTouchEvent(true)，回到适应屏幕
 *    状态再放开，让未放大时的左右滑动仍可正常翻页。
 * 3. 捏合过程中抬起一指就结束手势：剩下那根手指必须重新按下才能拖动。
 *    现在 ACTION_POINTER_UP 后若仍有手指，立即以当前位置重建拖动基准。
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

    /** 正在执行的矩阵动画（双击/回弹），新手势开始时需取消，否则会互相覆盖。 */
    private var matrixAnimator: ValueAnimator? = null

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

    /** 是否已放大到需要“接管”手势（此时平移优先于翻页）。 */
    private fun isZoomedIn(): Boolean = currentScale() > fitScale * 1.01f

    /**
     * 把 supp 矩阵的平移量夹回可视范围：
     * - 缩放后小于视图的轴 → 强制居中（否则会歪在一边）
     * - 大于视图的轴 → 保证图始终铺满视图，边缘不外露
     */
    private fun clampTranslation(supp: Matrix) {
        val d: Drawable = drawable ?: return
        val dw0 = d.intrinsicWidth.toFloat()
        val dh0 = d.intrinsicHeight.toFloat()
        if (dw0 <= 0 || dh0 <= 0) return

        val m = Matrix()
        m.set(baseMatrix)
        m.postConcat(supp)
        val v = FloatArray(9)
        m.getValues(v)
        val scale = v[Matrix.MSCALE_X]
        if (scale <= 0f) return

        val vw = (width - paddingLeft - paddingRight).toFloat()
        val vh = (height - paddingTop - paddingBottom).toFloat()
        if (vw <= 0f || vh <= 0f) return

        val dw = dw0 * scale
        val dh = dh0 * scale
        val left = v[Matrix.MTRANS_X]
        val top = v[Matrix.MTRANS_Y]

        val corrX = if (dw <= vw) {
            (paddingLeft + (vw - dw) / 2f) - left
        } else {
            val minL = paddingLeft + vw - dw // 右边缘贴齐
            val maxL = paddingLeft.toFloat() // 左边缘贴齐
            when {
                left > maxL -> maxL - left
                left < minL -> minL - left
                else -> 0f
            }
        }
        val corrY = if (dh <= vh) {
            (paddingTop + (vh - dh) / 2f) - top
        } else {
            val minT = paddingTop + vh - dh
            val maxT = paddingTop.toFloat()
            when {
                top > maxT -> maxT - top
                top < minT -> minT - top
                else -> 0f
            }
        }
        if (corrX != 0f || corrY != 0f) {
            supp.postTranslate(corrX, corrY)
        }
    }

    /** 让父容器（ViewPager2）是否让出触摸决策权。 */
    private fun disallowParentIntercept(disallow: Boolean) {
        var p: ViewParent? = parent
        while (p != null) {
            p.requestDisallowInterceptTouchEvent(disallow)
            p = p.parent
        }
    }

    private fun cancelMatrixAnimator() {
        matrixAnimator?.let {
            it.cancel()
            matrixAnimator = null
        }
    }

    /** 在两个 supp 矩阵之间做平滑过渡，避免双击/回弹瞬间跳变。 */
    private fun animateSuppMatrix(from: Matrix, to: Matrix, duration: Long) {
        cancelMatrixAnimator()
        val a = FloatArray(9)
        val b = FloatArray(9)
        from.getValues(a)
        to.getValues(b)
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = duration
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener { va ->
            val t = va.animatedValue as Float
            val c = FloatArray(9)
            for (i in 0 until 9) {
                c[i] = a[i] + (b[i] - a[i]) * t
            }
            val mm = Matrix()
            mm.setValues(c)
            suppMatrix.set(mm)
            applyMatrix()
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (matrixAnimator === anim) matrixAnimator = null
                // 动画结束后再夹一次，消除插值残差
                clampTranslation(suppMatrix)
                applyMatrix()
                disallowParentIntercept(isZoomedIn())
            }
        })
        matrixAnimator = anim
        anim.start()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancelMatrixAnimator()
        }
        scaleDetector.onTouchEvent(event)
        tapDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                savedMatrix.set(suppMatrix)
                lastFinger.set(event.x, event.y)
                mode = DRAG
                // 不放大时允许父容器（翻页）接管；放大了才独占
                disallowParentIntercept(isZoomedIn())
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                savedMatrix.set(suppMatrix)
                mode = ZOOM
                // 多指一定是缩放意图，禁止翻页抢手势
                disallowParentIntercept(true)
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG && event.pointerCount == 1 && isZoomedIn()) {
                    disallowParentIntercept(true)
                    val dx = event.x - lastFinger.x
                    val dy = event.y - lastFinger.y
                    suppMatrix.set(savedMatrix)
                    suppMatrix.postTranslate(dx, dy)
                    clampTranslation(suppMatrix)
                    applyMatrix()
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 抬起其中一指：若仍有手指，以当前接触点重建拖动基准，
                // 否则剩余手指会因 lastFinger 过期而“跳”一下或干脆拖不动。
                val remainIndex = if (event.actionIndex == 0) 1 else 0
                if (event.pointerCount - 1 >= 1 && remainIndex < event.pointerCount) {
                    savedMatrix.set(suppMatrix)
                    lastFinger.set(event.getX(remainIndex), event.getY(remainIndex))
                    mode = DRAG
                } else {
                    mode = NONE
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = NONE
                val cur = Matrix()
                cur.set(suppMatrix)
                val target = Matrix()
                target.set(suppMatrix)
                // 缩放低于 fitScale：动画回弹到适应屏幕并居中
                val scale = currentScale()
                if (scale < fitScale * 0.999f) {
                    target.reset()
                }
                clampTranslation(target)
                if (!matricesClose(cur, target)) {
                    animateSuppMatrix(cur, target, 180L)
                } else {
                    suppMatrix.set(target)
                    applyMatrix()
                    disallowParentIntercept(isZoomedIn())
                }
            }
        }
        return true
    }

    private fun matricesClose(a: Matrix, b: Matrix): Boolean {
        val va = FloatArray(9)
        val vb = FloatArray(9)
        a.getValues(va)
        b.getValues(vb)
        for (i in 0 until 9) {
            if (Math.abs(va[i] - vb[i]) > 0.5f) return false
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
            clampTranslation(suppMatrix)
            applyMatrix()
            return true
        }

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            // 双指按下即锁定手势，避免父容器在缩放起始帧抢走
            disallowParentIntercept(true)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            clampTranslation(suppMatrix)
            applyMatrix()
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
            val cur = Matrix()
            cur.set(suppMatrix)
            val target = Matrix()
            if (currentScale() > fitScale * 1.01f) {
                target.reset() // 已放大 → 回到适应屏幕
            } else {
                target.postScale(DOUBLE_TAP_FACTOR, DOUBLE_TAP_FACTOR, e.x, e.y)
                clampTranslation(target)
            }
            animateSuppMatrix(cur, target, 220L)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (tapCallback != null) {
                tapCallback!!.onViewTap()
            }
            return true
        }
    }

    override fun onDetachedFromWindow() {
        cancelMatrixAnimator()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val MAX_ZOOM_FACTOR = 4.0f   // 相对 fitScale 的最大放大倍数
        private const val DOUBLE_TAP_FACTOR = 2.5f // 双击放大倍数

        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }
}
