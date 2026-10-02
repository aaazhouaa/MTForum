package com.solosu.mtforum.ui.widget;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;


/**
 * 轻量玻璃背景(用于列表项/导航栏等高频刷新场景):
 * 半透明渐变底 + 顶部高光 + 细边框,简洁通透,绘制开销极小。

 */
public class FrostedGlassDrawable extends Drawable {

    /**
     * 暗色感知工厂:按当前主题自动选择填充色,radiusDp 为圆角半径(dp)。
     */
    public static FrostedGlassDrawable create(android.content.Context context, float radiusDp) {
        boolean isDark = (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        float density = context.getResources().getDisplayMetrics().density;
        return new FrostedGlassDrawable(
                isDark ? 0xFF1E1E1E : 0xFFFFFFFF,
                radiusDp * density, density);
    }

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float radius;
    private final float density;
    private int fillColor;

    /**
     * @param fillColor 基础填充色(会被强制设为 50%-62% 不透明度)
     * @param radius    圆角半径(px)
     * @param density   屏幕密度(用于高光/边框尺寸)
     */
    public FrostedGlassDrawable(int fillColor, float radius, float density) {
        this.radius = radius;
        this.density = density;
        this.fillColor = fillColor;

        bgPaint.setStyle(Paint.Style.FILL);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(Math.max(0.5f, 0.75f * density));
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        rect.set(bounds.left, bounds.top, bounds.right, bounds.bottom);
        updateShaders();
    }

    private void updateShaders() {
        if (rect.width() <= 0 || rect.height() <= 0) return;
        int r = Color.red(fillColor);
        int g = Color.green(fillColor);
        int b = Color.blue(fillColor);
        // 顶部稍亮 62% → 底部 50%,留出背景透光
        bgPaint.setShader(new LinearGradient(
                rect.left, rect.top, rect.left, rect.bottom,
                Color.argb(158, r, g, b),
                Color.argb(128, r, g, b),
                Shader.TileMode.CLAMP));
        // 边框:按底色明暗自适应 — 浅底配深灰细边框(日间可见),深底配白色细边框(夜间)
        boolean lightBg = (r * 299 + g * 587 + b * 114) / 1000 > 128;
        borderPaint.setShader(null);
        borderPaint.setColor(lightBg
                ? Color.argb(45, 0, 0, 0)
                : Color.argb(70, 255, 255, 255));
    }

    @Override
    public void draw(Canvas canvas) {
        if (rect.width() <= 0 || rect.height() <= 0) return;
        canvas.drawRoundRect(rect, radius, radius, bgPaint);
        canvas.drawRoundRect(rect, radius, radius, borderPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        bgPaint.setAlpha(alpha);
        borderPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        bgPaint.setColorFilter(colorFilter);
        borderPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
