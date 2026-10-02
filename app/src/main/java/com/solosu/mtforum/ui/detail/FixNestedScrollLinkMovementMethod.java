package com.solosu.mtforum.ui.detail;

import android.text.method.LinkMovementMethod;
import android.text.Spannable;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import androidx.core.widget.NestedScrollView;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自定义LinkMovementMethod，解决NestedScrollView中链接点击与滚动冲突问题
 * 核心改进：
 * 1. 只在点击位置有链接时才阻止父容器拦截，否则允许正常滚动
 * 2. 支持长按文本选择和复制功能
 * 3. 通过触摸事件处理区分点击链接和选择文本的行为
 */
public class FixNestedScrollLinkMovementMethod extends LinkMovementMethod {
    
    private float mDownX;
    private float mDownY;
    private long mDownTime;
    private boolean mIsLongPress;
    private static final long LONG_PRESS_THRESHOLD = 500; // 500ms视为长按
    
    @Override
    public boolean onTouchEvent(TextView widget, Spannable buffer, MotionEvent event) {
        int action = event.getAction();
        
        if (action == MotionEvent.ACTION_DOWN) {
            mDownX = event.getX();
            mDownY = event.getY();
            mDownTime = System.currentTimeMillis();
            mIsLongPress = false;
            
            // 检查点击位置是否有链接
            if (hasLinkAtPosition(widget, buffer, mDownX, mDownY)) {
                // 有链接时先不阻止父容器拦截，等待确认是点击还是滚动
                // 让父容器有机会处理滚动
            }
        }
        
        // 检查是否为长按（用于文本选择）
        if (action == MotionEvent.ACTION_MOVE) {
            long pressDuration = System.currentTimeMillis() - mDownTime;
            if (pressDuration > LONG_PRESS_THRESHOLD) {
                mIsLongPress = true;
            }
            
            // 如果是长按，允许父容器处理滚动（不阻止拦截）
            if (mIsLongPress) {
                NestedScrollView parent = findNestedScrollView(widget);
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(false);
                }
            } else {
                // 短按移动时，如果不在链接上，也允许滚动
                NestedScrollView parent = findNestedScrollView(widget);
                if (parent != null && !hasLinkAtPosition(widget, buffer, mDownX, mDownY)) {
                    parent.requestDisallowInterceptTouchEvent(false);
                }
            }
        }
        
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            long pressDuration = System.currentTimeMillis() - mDownTime;
            NestedScrollView parent = findNestedScrollView(widget);
            if (parent != null) {
                // 如果是长按，允许父容器处理（文本选择场景）
                // 如果是短按且点击在链接上，阻止父容器拦截
                if (mIsLongPress || pressDuration > LONG_PRESS_THRESHOLD) {
                    parent.requestDisallowInterceptTouchEvent(false);
                } else {
                    parent.requestDisallowInterceptTouchEvent(!hasLinkAtPosition(widget, buffer, mDownX, mDownY));
                }
            }
        }
        
        return super.onTouchEvent(widget, buffer, event);
    }
    
    /**
     * 检查指定位置是否有ClickableSpan
     */
    private boolean hasLinkAtPosition(TextView widget, Spannable buffer, float x, float y) {
        try {
            android.text.Layout layout = widget.getLayout();
            if (layout == null) return false;
            
            int line = layout.getLineForVertical((int) y);
            int offset = layout.getOffsetForHorizontal(line, x);
            
            if (offset < 0) return false;
            
            ClickableSpan[] spans = buffer.getSpans(offset, offset, ClickableSpan.class);
            return spans.length > 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 向上查找NestedScrollView父容器
     */
    private NestedScrollView findNestedScrollView(TextView view) {
        android.view.ViewParent parent = view.getParent();
        while (parent != null) {
            if (parent instanceof NestedScrollView) {
                return (NestedScrollView) parent;
            }
            parent = parent.getParent();
        }
        return null;
    }
    
    /**
     * 使用自定义Pattern在Spannable中查找URL并添加ClickableSpan
     * @param spannable 目标Spannable
     * @param pattern 正则表达式
     * @param urlProcessor 处理匹配到的URL并返回最终URL
     * @param onClickListener 点击链接时的回调，传入处理后的URL
     */
    public static void matcherLinkify(Spannable spannable, Pattern pattern, 
            java.util.function.Function<String, String> urlProcessor,
            java.util.function.Consumer<String> onClickListener) {
        if (spannable == null || pattern == null) return;
        
        String text = spannable.toString();
        Matcher matcher = pattern.matcher(text);
        
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            
            // 跳过已经有ClickableSpan的区域
            boolean alreadySpanned = false;
            for (ClickableSpan span : spannable.getSpans(start, end, ClickableSpan.class)) {
                if (spannable.getSpanStart(span) >= start && spannable.getSpanEnd(span) <= end) {
                    alreadySpanned = true;
                    break;
                }
            }
            if (alreadySpanned) continue;
            
            final String rawUrl = text.substring(start, end);
            final String processedUrl = urlProcessor != null ? urlProcessor.apply(rawUrl) : rawUrl;
            
            spannable.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    if (onClickListener != null) {
                        onClickListener.accept(processedUrl);
                    }
                }
                @Override
                public void updateDrawState(TextPaint ds) {
                    ds.setColor(0xFF1976D2);
                    ds.setUnderlineText(true);
                }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
}