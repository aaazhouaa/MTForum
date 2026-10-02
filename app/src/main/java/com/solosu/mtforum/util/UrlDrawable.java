package com.solosu.mtforum.util;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * 配合 Glide 异步加载的占位 Drawable（用于 Html.ImageGetter）
 * Html.fromHtml 同步调用 getDrawable 时先返回本占位对象，
 * Glide 加载完成后回调 setReal() 填充真实图并触发宿主 TextView 重绘。
 * ImageSpan 持有的是本对象引用，draw() 委托内部 real，实现异步回显。
 */
public class UrlDrawable extends ColorDrawable {

    private Drawable real;
    private View target;

    public UrlDrawable(View targetView, int sizePx) {
        super(0x00000000);
        this.target = targetView;
        setBounds(0, 0, sizePx, sizePx);
    }

    /** Glide 加载完成回填真实图，并请求宿主重绘 */
    public void setReal(Drawable drawable, View targetView) {
        this.real = drawable;
        this.target = targetView;
        if (drawable != null) {
            drawable.setBounds(getBounds());
        }
        if (targetView instanceof android.widget.TextView) {
            // 图片尺寸变化后必须重新排版，否则图片框停留占位大小
            final android.widget.TextView tv = (android.widget.TextView) targetView;
            tv.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        tv.setText(tv.getText());
                    } catch (Exception ignore) {
                    }
                }
            });
        } else if (targetView != null) {
            targetView.postInvalidate();
        }
    }

    @Override
    public void draw(Canvas canvas) {
        if (real != null) {
            real.setBounds(getBounds());
            real.draw(canvas);
        } else {
            super.draw(canvas);
        }
    }

    @Override
    public int getIntrinsicWidth() {
        return real != null ? real.getIntrinsicWidth() : getBounds().width();
    }

    @Override
    public int getIntrinsicHeight() {
        return real != null ? real.getIntrinsicHeight() : getBounds().height();
    }

    @Override
    public void setAlpha(int alpha) {
        if (real != null) {
            real.setAlpha(alpha);
        }
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        if (real != null) {
            real.setColorFilter(colorFilter);
        }
    }

    @Override
    public int getOpacity() {
        return real != null ? real.getOpacity() : PixelFormat.TRANSPARENT;
    }
}
