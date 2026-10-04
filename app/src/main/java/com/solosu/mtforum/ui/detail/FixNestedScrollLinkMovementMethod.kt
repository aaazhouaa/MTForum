package com.solosu.mtforum.ui.detail

import android.text.method.LinkMovementMethod
import android.text.Spannable
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * 自定义LinkMovementMethod，解决NestedScrollView中链接点击与滚动冲突问题
 * 核心改进：
 * 1. 只在点击位置有链接时才阻止父容器拦截，否则允许正常滚动
 * 2. 支持长按文本选择和复制功能
 * 3. 通过触摸事件处理区分点击链接和选择文本的行为
 */
class FixNestedScrollLinkMovementMethod : LinkMovementMethod() {

    private var mDownX = 0f
    private var mDownY = 0f
    private var mDownTime = 0L
    private var mIsLongPress = false
    private val LONG_PRESS_THRESHOLD = 500L // 500ms视为长按

    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        val action = event.action

        if (action == MotionEvent.ACTION_DOWN) {
            mDownX = event.x
            mDownY = event.y
            mDownTime = System.currentTimeMillis()
            mIsLongPress = false

            // 检查点击位置是否有链接
            if (hasLinkAtPosition(widget, buffer, mDownX, mDownY)) {
                // 有链接时先不阻止父容器拦截，等待确认是点击还是滚动
                // 让父容器有机会处理滚动
            }
        }

        // 检查是否为长按（用于文本选择）
        if (action == MotionEvent.ACTION_MOVE) {
            val pressDuration = System.currentTimeMillis() - mDownTime
            if (pressDuration > LONG_PRESS_THRESHOLD) {
                mIsLongPress = true
            }

            // 如果是长按，允许父容器处理滚动（不阻止拦截）
            if (mIsLongPress) {
                val parent = findNestedScrollView(widget)
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(false)
                }
            } else {
                // 短按移动时，如果不在链接上，也允许滚动
                val parent = findNestedScrollView(widget)
                if (parent != null && !hasLinkAtPosition(widget, buffer, mDownX, mDownY)) {
                    parent.requestDisallowInterceptTouchEvent(false)
                }
            }
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            val pressDuration = System.currentTimeMillis() - mDownTime
            val parent = findNestedScrollView(widget)
            if (parent != null) {
                // 如果是长按，允许父容器处理（文本选择场景）
                // 如果是短按且点击在链接上，阻止父容器拦截
                if (mIsLongPress || pressDuration > LONG_PRESS_THRESHOLD) {
                    parent.requestDisallowInterceptTouchEvent(false)
                } else {
                    parent.requestDisallowInterceptTouchEvent(!hasLinkAtPosition(widget, buffer, mDownX, mDownY))
                }
            }
        }

        return super.onTouchEvent(widget, buffer, event)
    }

    /**
     * 检查指定位置是否有ClickableSpan
     */
    private fun hasLinkAtPosition(widget: TextView, buffer: Spannable, x: Float, y: Float): Boolean {
        try {
            val layout = widget.layout
            if (layout == null) return false

            val line = layout.getLineForVertical(y.toInt())
            val offset = layout.getOffsetForHorizontal(line, x)

            if (offset < 0) return false

            val spans = buffer.getSpans(offset, offset, ClickableSpan::class.java)
            return spans.size > 0
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 向上查找NestedScrollView父容器
     */
    private fun findNestedScrollView(view: TextView): NestedScrollView? {
        var parent: android.view.ViewParent? = view.parent
        while (parent != null) {
            if (parent is NestedScrollView) {
                return parent
            }
            parent = parent.parent
        }
        return null
    }

    companion object {

        /**
         * 使用自定义Pattern在Spannable中查找URL并添加ClickableSpan
         * @param spannable 目标Spannable
         * @param pattern 正则表达式
         * @param urlProcessor 处理匹配到的URL并返回最终URL
         * @param onClickListener 点击链接时的回调，传入处理后的URL
         */
        @JvmStatic
        @JvmOverloads
        fun matcherLinkify(
            spannable: Spannable?,
            pattern: Pattern?,
            urlProcessor: java.util.function.Function<String, String>?,
            onClickListener: java.util.function.Consumer<String>?,
            linkColor: Int = 0xFF2563EB.toInt()
        ) {
            if (spannable == null || pattern == null) return

            val text = spannable.toString()
            val matcher: Matcher = pattern.matcher(text)

            while (matcher.find()) {
                val start = matcher.start()
                val end = matcher.end()

                // 跳过已经有ClickableSpan的区域
                var alreadySpanned = false
                for (span in spannable.getSpans(start, end, ClickableSpan::class.java)) {
                    if (spannable.getSpanStart(span) >= start && spannable.getSpanEnd(span) <= end) {
                        alreadySpanned = true
                        break
                    }
                }
                if (alreadySpanned) continue

                val rawUrl = text.substring(start, end)
                val processedUrl: String = if (urlProcessor != null) urlProcessor.apply(rawUrl) else rawUrl

                spannable.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        if (onClickListener != null) {
                            onClickListener.accept(processedUrl)
                        }
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = linkColor
                        ds.isUnderlineText = true
                    }
                }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
