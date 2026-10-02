package com.solosu.mtforum.ui.detail;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

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
public class ZoomableImageView extends AppCompatImageView {

    private static final float MAX_ZOOM_FACTOR = 4.0f;   // 相对 fitScale 的最大放大倍数
    private static final float DOUBLE_TAP_FACTOR = 2.5f; // 双击放大倍数

    private final Matrix baseMatrix = new Matrix();  // fitScale + 居中
    private final Matrix suppMatrix = new Matrix();  // 用户手势叠加
    private final Matrix drawMatrix = new Matrix();
    private final Matrix savedMatrix = new Matrix();

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector tapDetector;

    private float fitScale = 1.0f;
    private boolean baseReady = false;

    private static final int NONE = 0;
    private static final int DRAG = 1;
    private static final int ZOOM = 2;
    private int mode = NONE;

    private final PointF lastFinger = new PointF();

    public ZoomableImageView(@NonNull Context context) {
        this(context, null);
    }

    public ZoomableImageView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        super.setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        tapDetector = new GestureDetector(context, new TapListener());
        // 首次布局完成时建立基准矩阵
        getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                setupBase();
            }
        });
    }

    /** 计算基准矩阵：图片适配视图并居中（相当于 fitCenter 效果） */
    private void setupBase() {
        Drawable d = getDrawable();
        if (d == null) {
            return;
        }
        int vw = getWidth() - getPaddingLeft() - getPaddingRight();
        int vh = getHeight() - getPaddingTop() - getPaddingBottom();
        if (vw <= 0 || vh <= 0) {
            return;
        }
        float dw = d.getIntrinsicWidth();
        float dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) {
            return;
        }
        if (baseReady) {
            return; // 只初始化一次，避免每帧重算
        }
        baseReady = true;
        float scale = Math.min(vw / dw, vh / dh);
        if (scale <= 0) {
            scale = 1.0f;
        }
        fitScale = scale;
        baseMatrix.reset();
        baseMatrix.postScale(scale, scale);
        // 居中：视图中心 - 缩放后图中心
        float tx = (vw - dw * scale) / 2.0f;
        float ty = (vh - dh * scale) / 2.0f;
        baseMatrix.postTranslate(tx + getPaddingLeft(), ty + getPaddingTop());
        suppMatrix.reset();
        applyMatrix();
    }

    /** 图片变化时重建基准（Glide 加载完成会触发 onGlobalLayout，但 baseReady 已置位，这里主动刷新） */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
    }

    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        baseReady = false; // 新图，重建基准
        if (getWidth() > 0 && getHeight() > 0 && drawable != null) {
            setupBase();
        }
    }

    private void applyMatrix() {
        drawMatrix.set(baseMatrix);
        drawMatrix.postConcat(suppMatrix);
        setImageMatrix(drawMatrix);
        invalidate();
    }

    private float currentScale() {
        float[] v = new float[9];
        drawMatrix.getValues(v);
        return v[Matrix.MSCALE_X];
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        tapDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                savedMatrix.set(suppMatrix);
                lastFinger.set(event.getX(), event.getY());
                mode = DRAG;
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                savedMatrix.set(suppMatrix);
                mode = ZOOM;
                break;

            case MotionEvent.ACTION_MOVE:
                if (mode == DRAG && event.getPointerCount() == 1 && currentScale() > fitScale * 1.01f) {
                    float dx = event.getX() - lastFinger.x;
                    float dy = event.getY() - lastFinger.y;
                    suppMatrix.set(savedMatrix);
                    suppMatrix.postTranslate(dx, dy);
                    applyMatrix();
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                mode = NONE;
                // 低于 fitScale 回弹
                if (currentScale() < fitScale) {
                    suppMatrix.reset();
                    applyMatrix();
                }
                break;
        }
        return true;
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float factor = detector.getScaleFactor();
            float target = currentScale() * factor;
            float max = fitScale * MAX_ZOOM_FACTOR;
            float min = fitScale * 0.5f;
            if (target > max) {
                factor = max / currentScale();
            } else if (target < min) {
                factor = min / currentScale();
            }
            suppMatrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
            applyMatrix();
            return true;
        }
    }

    /** 单击回调（预览页用于单击关闭） */
    public interface OnViewTapListener {
        void onViewTap();
    }

    private OnViewTapListener tapCallback;

    public void setOnViewTapListener(OnViewTapListener l) {
        this.tapCallback = l;
    }

    private class TapListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDoubleTap(MotionEvent e) {
            float cur = currentScale();
            if (cur > fitScale * 1.01f) {
                suppMatrix.reset();
            } else {
                suppMatrix.reset();
                suppMatrix.postScale(DOUBLE_TAP_FACTOR, DOUBLE_TAP_FACTOR, e.getX(), e.getY());
            }
            applyMatrix();
            return true;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            if (tapCallback != null) {
                tapCallback.onViewTap();
            }
            return true;
        }
    }
}
