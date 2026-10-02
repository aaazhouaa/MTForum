package com.solosu.mtforum.util

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.solosu.mtforum.R
import java.lang.ref.WeakReference

/**
 * 现代轻量纯净提示工具：
 * 彻底解决 Android 12+ 系统原生 Toast 强制带应用 Logo 图标的问题。
 * 在顶层 DecorView 展示极简高质感微胶囊，无图标，柔和动效。
 */
object ToastUtil {

    const val LENGTH_SHORT = 0
    const val LENGTH_LONG = 1

    private val mainHandler = Handler(Looper.getMainLooper())
    private var topActivityRef: WeakReference<Activity>? = null

    @JvmStatic
    fun setTopActivity(activity: Activity?) {
        if (activity != null) {
            topActivityRef = WeakReference(activity)
        }
    }

    @JvmStatic
    fun clearIfCurrent(activity: Activity?) {
        if (activity != null && topActivityRef?.get() === activity) {
            topActivityRef = null
        }
    }

    private fun resolveActivity(context: Context?): Activity? {
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return topActivityRef?.get()
    }

    @JvmStatic
    @JvmOverloads
    fun show(context: Context?, message: CharSequence?, duration: Int = 0) {
        if (message.isNullOrEmpty()) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showInternal(context, message)
        } else {
            mainHandler.post { showInternal(context, message) }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun show(context: Context?, resId: Int, duration: Int = 0) {
        if (context == null) return
        show(context, context.getString(resId), duration)
    }

    /** 兼容 Toast.makeText(c, m, d).show() 链式调用风格 */
    class ToastProxy(private val context: Context?, private val message: CharSequence?) {
        fun show() {
            ToastUtil.show(context, message)
        }
    }

    @JvmStatic
    fun makeText(context: Context?, message: CharSequence?, duration: Int): ToastProxy {
        return ToastProxy(context, message)
    }

    @JvmStatic
    fun makeText(context: Context?, resId: Int, duration: Int): ToastProxy {
        val msg = context?.getString(resId) ?: ""
        return ToastProxy(context, msg)
    }

    private fun showInternal(context: Context?, message: CharSequence) {
        val activity = resolveActivity(context)
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            // 回退到系统 Toast (无可用前台 Activity 时兜底)
            if (context != null) {
                android.widget.Toast.makeText(context.applicationContext, message, android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }

        val decorView = activity.window?.decorView as? ViewGroup ?: return
        val density = activity.resources.displayMetrics.density

        // 移除已有的 Toast 视图，避免重叠
        val oldToast = decorView.findViewWithTag<View>("app_floating_toast")
        if (oldToast != null) {
            decorView.removeView(oldToast)
        }

        // 现代极简纯净胶囊容器 (纯文本，零 Logo)
        val container = FrameLayout(activity).apply {
            tag = "app_floating_toast"
            isClickable = false
            isFocusable = false
        }

        val tv = TextView(activity).apply {
            text = message
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding((18 * density).toInt(), (10 * density).toInt(), (18 * density).toInt(), (10 * density).toInt())

            val isDark = ThemeManager.isDarkMode(activity)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 22f * density
                setColor(if (isDark) 0xEE40444B.toInt() else 0xEE1E2024.toInt())
            }
            background = bg
            elevation = 6f * density
        }

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = (90 * density).toInt()
            marginEnd = (32 * density).toInt()
            marginStart = (32 * density).toInt()
        }

        container.addView(tv, lp)
        decorView.addView(container, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 优雅入场动效：淡入 + 微上浮
        tv.alpha = 0f
        tv.translationY = 16f * density
        tv.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // 停留 2 秒后淡出移除
        mainHandler.postDelayed({
            if (container.parent != null) {
                tv.animate()
                    .alpha(0f)
                    .translationY(8f * density)
                    .setDuration(160)
                    .withEndAction {
                        decorView.removeView(container)
                    }
                    .start()
            }
        }, 2000)
    }
}
