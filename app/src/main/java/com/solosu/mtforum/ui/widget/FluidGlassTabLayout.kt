package com.solosu.mtforum.ui.widget

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.liquidglass.LiquidGlassTabLayout
import com.example.liquidglass.LiquidGlassView
import com.solosu.mtforum.R
import com.solosu.mtforum.util.ThemeManager
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * FluidGlassTabLayout
 * 基于三方库 LiquidGlassTabLayout 扩展定制：
 * 1. 元素垂直居中：图标 + 文字垂直正中排列，中间独立加号药丸按钮居中；
 * 2. 普通状态水珠：严格保持原生小巧紧凑尺寸（高度约 38~40dp，四周均匀内收，绝不顶格或变大）；
 * 3. 长按状态水珠：高度超出底栏（76dp），宽度在原 1.40 基础上再减 5%（即 1.33 倍 tabWidth）；
 * 4. 背景与颜色：浅色模式 65% 不透明度白色毛玻璃，深色模式 #2D3035 30% 不透明度，消除边缘黑影。
 */
@SuppressLint("ClickableViewAccessibility")
class FluidGlassTabLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LiquidGlassTabLayout(context, attrs, defStyleAttr) {

    private var dropletView: LiquidGlassView? = null
    private var placeDropletMethod: Method? = null
    private var resizeDropletMethod: Method? = null
    private var indicatorPosField: Field? = null
    private var indicatorAnimatorField: Field? = null
    private var scrollerView: View? = null
    private var tabsRowView: LinearLayout? = null

    // 手势识别
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastMoveTime = 0L
    private var isDragging = false
    private var isTouching = false
    private var postTabPressed = false

    // 速度追踪与流体形变
    private var velocityTracker: VelocityTracker? = null
    private var lastDirection = 0

    // 水珠尺寸状态
    private var isExpanded = false
    private var baseNormalW = 0
    private var baseNormalH = 0

    // 阻尼跟手位置
    private var smoothedIndicatorPos = 0f
    private var fluidStretchRatio = 1.0f

    // 动画器
    private var expansionAnimator: ValueAnimator? = null

    // 划动悬停
    private var currentHoverTab = 0

    // 点击中间发帖按钮回调
    var onPostClickListener: (() -> Unit)? = null

    // 中间加号胶囊视图引用
    private var postCapsuleView: View? = null

    // 底栏毛玻璃背景与微光描边画笔
    private val glassBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgBounds = RectF()

    init {
        initReflection()
        clipChildren = false
        clipToPadding = false
        setClipToOutline(false)
        enableShadow = false // 关闭底栏底层阴影以防止右侧胶囊外产生黑弧

        val isDark = isDarkMode()
        if (isDark) {
            setGlassTint(0x2D3035, 0.30f) // 深色模式 #2D3035, 30% 不透明度
        } else {
            setGlassTint(0xFFFFFF, 0.65f) // 浅色模式 0.65f 强度
        }
        setupDropletFresnelProperties()
    }

    private fun initReflection() {
        try {
            val dropletF = LiquidGlassTabLayout::class.java.getDeclaredField("droplet")
            dropletF.isAccessible = true
            dropletView = dropletF.get(this) as? LiquidGlassView

            val scrollerF = LiquidGlassTabLayout::class.java.getDeclaredField("scroller")
            scrollerF.isAccessible = true
            scrollerView = scrollerF.get(this) as? View

            val tabsRowF = LiquidGlassTabLayout::class.java.getDeclaredField("tabsRow")
            tabsRowF.isAccessible = true
            tabsRowView = tabsRowF.get(this) as? LinearLayout

            indicatorPosField = LiquidGlassTabLayout::class.java.getDeclaredField("indicatorPos")
            indicatorPosField?.isAccessible = true

            indicatorAnimatorField = LiquidGlassTabLayout::class.java.getDeclaredField("indicatorAnimator")
            indicatorAnimatorField?.isAccessible = true

            placeDropletMethod = LiquidGlassTabLayout::class.java.getDeclaredMethod(
                "placeDroplet",
                Boolean::class.javaPrimitiveType
            )
            placeDropletMethod?.isAccessible = true

            resizeDropletMethod = LiquidGlassTabLayout::class.java.getDeclaredMethod(
                "resizeDroplet",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            resizeDropletMethod?.isAccessible = true
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        ensureTabsRowCentered()
        captureBaseNormalDropletSize()
    }

    /**
     * 确保整个 tabsRow 在底栏中垂直严格居中，每个 Tab 上下留白完全一致
     */
    private fun ensureTabsRowCentered() {
        tabsRowView?.let { row ->
            row.gravity = Gravity.CENTER_VERTICAL
            val lp = row.layoutParams as? FrameLayout.LayoutParams
            if (lp != null && lp.gravity != Gravity.CENTER) {
                lp.gravity = Gravity.CENTER
                row.layoutParams = lp
            }
        }
        scrollerView?.let { scroller ->
            val lp = scroller.layoutParams as? FrameLayout.LayoutParams
            if (lp != null && lp.gravity != Gravity.CENTER) {
                lp.gravity = Gravity.CENTER
                scroller.layoutParams = lp
            }
        }
    }

    private fun captureBaseNormalDropletSize() {
        val droplet = dropletView ?: return
        if (!isExpanded && droplet.width > 0 && droplet.height > 0) {
            baseNormalW = droplet.width
            baseNormalH = droplet.height
        }
    }

    private fun setupDropletFresnelProperties() {
        val droplet = dropletView ?: return
        val isDark = isDarkMode()

        droplet.enableShadow = false // 关闭水珠自带阴影防黑边
        droplet.enableChromaticDispersion = true
        droplet.enableChromaticAberration = true
        droplet.dispersionStrength = 0.08f
        droplet.bevelWidth = dpF(10f)
        droplet.refractionHeight = dpF(5f)
        droplet.enableEdgeHighlight = true
        droplet.edgeHighlightOpacity = 0.50f
        droplet.edgeHighlightBorderWidth = dpF(1.2f)
        droplet.cornerRadius = dpF(999f)

        if (!isDark) {
            droplet.setGlassTint(0x2D3035, 0.20f)
        } else {
            droplet.setGlassTint(0xFFFFFF, 0.15f)
        }
    }

    private fun isDarkMode(): Boolean {
        return (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width > 0 && height > 0) {
            val isDark = isDarkMode()
            val r = height / 2f
            bgBounds.set(0f, 0f, width.toFloat(), height.toFloat())

            glassBgPaint.style = Paint.Style.FILL
            if (!isDark) {
                // 浅色模式：65% 纯白磨砂遮罩 (0xA6FFFFFF)
                glassBgPaint.color = 0xA6FFFFFF.toInt()
                canvas.drawRoundRect(bgBounds, r, r, glassBgPaint)
            } else {
                // 深色模式：#2D3035，30% 不透明度 (0x4D2D3035)
                glassBgPaint.color = 0x4D2D3035
                canvas.drawRoundRect(bgBounds, r, r, glassBgPaint)
            }
        }
    }

    override fun draw(canvas: Canvas) {
        val isDark = isDarkMode()
        if (width > 0 && height > 0) {
            val r = height / 2f
            bgBounds.set(0f, 0f, width.toFloat(), height.toFloat())

            shadowPaint.color = if (isDark) 0x33000000 else 0x1A000000
            canvas.drawRoundRect(bgBounds, r, r, shadowPaint)
        }

        super.draw(canvas)

        if (width > 0 && height > 0) {
            val r = height / 2f
            rimBorderPaint.strokeWidth = dpF(1.2f)
            rimBorderPaint.color = if (isDark) 0x28FFFFFF else 0x55FFFFFF
            val halfBorder = rimBorderPaint.strokeWidth / 2f
            val borderR = RectF(
                bgBounds.left + halfBorder,
                bgBounds.top + halfBorder,
                bgBounds.right - halfBorder,
                bgBounds.bottom - halfBorder
            )
            canvas.drawRoundRect(borderR, r - halfBorder, r - halfBorder, rimBorderPaint)
        }
    }

    /**
     * 配置 5 个 Tab：首页、版块、中间发帖、消息、我的
     */
    fun setupTabs(themeColor: Int) {
        removeAllTabs()

        val tabData = listOf(
            context.getString(R.string.tab_home) to R.drawable.ic_home,
            context.getString(R.string.tab_circle) to R.drawable.ic_discover,
            context.getString(R.string.action_post) to R.drawable.ic_tab_add,
            context.getString(R.string.tab_message) to R.drawable.ic_message,
            context.getString(R.string.tab_profile) to R.drawable.ic_profile
        )

        for (i in tabData.indices) {
            val (title, iconRes) = tabData[i]
            val tab = newTab()
            tab.text = title
            tab.icon = ContextCompat.getDrawable(context, iconRes)
            addTab(tab)

            customizeTabInternal(tab, i == 2, themeColor)
        }

        refreshTabColors(selectedTabPosition)
    }

    private fun customizeTabInternal(tab: Tab, isPost: Boolean, themeColor: Int) {
        try {
            val viewF = Tab::class.java.getDeclaredField("view")
            viewF.isAccessible = true
            val tabRoot = viewF.get(tab) as? LinearLayout ?: return

            val iconF = Tab::class.java.getDeclaredField("iconView")
            iconF.isAccessible = true
            val iconView = iconF.get(tab) as? ImageView

            val labelF = Tab::class.java.getDeclaredField("labelView")
            labelF.isAccessible = true
            val labelView = labelF.get(tab) as? TextView

            // 保持垂直方向适中紧凑排列，垂直正中对齐
            tabRoot.orientation = LinearLayout.VERTICAL
            tabRoot.gravity = Gravity.CENTER
            tabRoot.setPadding(0, dp(2), 0, dp(2))

            if (isPost) {
                labelView?.visibility = View.GONE

                val capsule = FrameLayout(context).apply {
                    val bg = GradientDrawable().apply {
                        cornerRadius = dpF(17f)
                        setColor(themeColor)
                    }
                    background = bg
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(32)).apply {
                        gravity = Gravity.CENTER
                    }
                }

                val plus = ImageView(context).apply {
                    setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_tab_add))
                    imageTintList = ColorStateList.valueOf(Color.WHITE)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
                }
                capsule.addView(plus)

                iconView?.visibility = View.GONE
                tabRoot.addView(capsule)
                postCapsuleView = capsule
            } else {
                iconView?.apply {
                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        bottomMargin = dp(2)
                    }
                }
                labelView?.apply {
                    textSize = 10.5f
                    visibility = View.VISIBLE
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        topMargin = 0
                    }
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    /**
     * 浅色模式未选中纯黑 (0xFF000000)，深色模式未选中纯白 (0xFFFFFFFF)，选中为主题色
     */
    fun refreshTabColors(selectedPos: Int) {
        val isDark = isDarkMode()
        val unselectedColor = if (isDark) Color.WHITE else Color.BLACK
        val selectedColor = selectedTintColor ?: ThemeManager.getThemeColor(context)

        for (i in 0 until tabCount) {
            if (i == 2) continue
            val tab = getTabAt(i) ?: continue
            try {
                val iconF = Tab::class.java.getDeclaredField("iconView")
                iconF.isAccessible = true
                val iconView = iconF.get(tab) as? ImageView

                val labelF = Tab::class.java.getDeclaredField("labelView")
                labelF.isAccessible = true
                val labelView = labelF.get(tab) as? TextView

                val isCurrent = (i == selectedPos)
                val targetColor = if (isCurrent) selectedColor else unselectedColor

                labelView?.setTextColor(targetColor)
                labelView?.typeface = if (isCurrent) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                iconView?.imageTintList = ColorStateList.valueOf(targetColor)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = ev.x
            downY = ev.y
            lastX = ev.x
            lastMoveTime = System.currentTimeMillis()
            isTouching = true
            isDragging = false
            lastDirection = 0
            fluidStretchRatio = 1.0f
            smoothedIndicatorPos = getIndicatorPositionInternal()
        }
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastMoveTime = System.currentTimeMillis()
                isTouching = true
                isDragging = false
                lastDirection = 0
                fluidStretchRatio = 1.0f

                val tabIdx = getTabIndexAt(downX)
                if (tabIdx == 2) {
                    postTabPressed = true
                    animatePostScale(0.9f)
                    return true
                }

                postTabPressed = false
                parent?.requestDisallowInterceptTouchEvent(true)

                // 启动长按水珠放大
                captureBaseNormalDropletSize()
                animateDropletExpansion(true)
                smoothedIndicatorPos = getIndicatorPositionInternal()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val totalDx = abs(event.x - downX)
                val now = System.currentTimeMillis()
                val dt = max(1L, now - lastMoveTime)

                if (!isDragging && totalDx > touchSlop) {
                    isDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }

                if (isDragging && !postTabPressed) {
                    cancelIndicatorAnimator()

                    val speed = abs(dx) / dt.toFloat()
                    val currentDirection = if (dx > 0) 1 else if (dx < 0) -1 else 0

                    if (currentDirection != 0 && lastDirection != 0 && currentDirection != lastDirection) {
                        fluidStretchRatio = 0.75f // 反向瞬间变短
                    } else if (speed > 0.04f) {
                        val targetStretch = min(1.30f, 1.0f + speed * 0.10f)
                        fluidStretchRatio += (targetStretch - fluidStretchRatio) * 0.35f
                    } else {
                        fluidStretchRatio += (1.0f - fluidStretchRatio) * 0.18f
                    }

                    if (currentDirection != 0) {
                        lastDirection = currentDirection
                    }
                    lastX = event.x
                    lastMoveTime = now

                    applyDropletFluidTransform()

                    // “慢半拍”粘性阻尼平滑跟随
                    val targetPos = calculateIndicatorPosFromX(event.x)
                    smoothedIndicatorPos += (targetPos - smoothedIndicatorPos) * 0.28f
                    setIndicatorPositionInternal(smoothedIndicatorPos)

                    val nearest = nearestPageTab(smoothedIndicatorPos)
                    if (nearest != currentHoverTab) {
                        currentHoverTab = nearest
                        refreshTabColors(nearest)
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                isTouching = false
                velocityTracker?.recycle()
                velocityTracker = null

                if (postTabPressed) {
                    postTabPressed = false
                    animatePostScale(1.0f)
                    val tabIdx = getTabIndexAt(event.x)
                    if (tabIdx == 2) {
                        onPostClickListener?.invoke()
                    }
                    return true
                }

                // 抬手：立即开始收缩动画，收回至原生尺寸
                animateDropletExpansion(false)

                if (isDragging) {
                    isDragging = false
                    val curPos = getIndicatorPositionInternal()
                    val targetTab = nearestPageTab(curPos)
                    selectTab(targetTab, true)
                } else {
                    val clickedTab = getTabIndexAt(event.x)
                    if (clickedTab == 2) {
                        onPostClickListener?.invoke()
                    } else if (clickedTab in 0 until tabCount) {
                        selectTab(clickedTab, true)
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isTouching = false
                isDragging = false
                postTabPressed = false
                velocityTracker?.recycle()
                velocityTracker = null
                animatePostScale(1.0f)
                animateDropletExpansion(false)
                val cur = selectedTabPosition
                if (cur >= 0) {
                    selectTab(cur, true)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * 仅在长按时膨胀放大：
     * - 未长按时：严禁改动尺寸，尺寸彻底交还三方库原生尺寸（高度紧凑约 38~40dp，绝不变大！）；
     * - 长按时：宽度放大到 1.33 倍 tabWidth，高度 76dp 溢出底栏。
     */
    private fun animateDropletExpansion(expand: Boolean) {
        if (isExpanded == expand) return
        isExpanded = expand

        expansionAnimator?.cancel()
        val droplet = dropletView ?: return
        if (width <= 0) return

        captureBaseNormalDropletSize()

        val tabW = width / max(1, tabCount)
        val normalW = if (baseNormalW > 0) baseNormalW else (tabW * 0.85f).toInt()
        val normalH = if (baseNormalH > 0) baseNormalH else dp(38)
        // 宽度再减少 5%：为 1.33 倍 tab 宽
        val expandedW = (tabW * 1.33f).toInt()
        val expandedH = dp(76)

        val targetW = if (expand) expandedW else normalW
        val targetH = if (expand) expandedH else normalH
        val startW = droplet.width
        val startH = droplet.height

        expansionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (expand) 200L else 180L
            interpolator = if (expand) OvershootInterpolator(1.15f) else DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                val w = (startW + (targetW - startW) * f).toInt()
                val h = (startH + (targetH - startH) * f).toInt()

                if (!expand && f >= 1.0f) {
                    // 彻底恢复未长按：清零 translationY，交回原生放置
                    droplet.translationY = 0f
                    try {
                        placeDropletMethod?.invoke(this@FluidGlassTabLayout, false)
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                } else {
                    applyCustomDropletGeometry(w, h, smoothedIndicatorPos)
                }

                droplet.dispersionStrength = if (expand) 0.11f else 0.08f
                droplet.bevelWidth = dpF(if (expand) 12f else 10f)
                droplet.invalidate()
            }
            start()
        }
    }

    private fun applyCustomDropletGeometry(w: Int, h: Int, pos: Float) {
        val droplet = dropletView ?: return
        if (width <= 0) return

        val tabW = width.toFloat() / max(1, tabCount)
        val centerX = (pos + 0.5f) * tabW
        var transX = centerX - w / 2f

        val minTransX = dpF(2f)
        val maxTransX = width - w - dpF(2f)
        if (maxTransX >= minTransX) {
            transX = transX.coerceIn(minTransX, maxTransX)
        }

        val transY = if (height > 0) (height - h) / 2f else 0f

        val lp = droplet.layoutParams
        if (lp != null && (lp.width != w || lp.height != h)) {
            lp.width = w
            lp.height = h
            droplet.layoutParams = lp
            droplet.measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            )
            droplet.layout(0, 0, w, h)
        }

        droplet.translationX = transX
        droplet.translationY = transY
        droplet.invalidate()
    }

    private fun applyDropletFluidTransform() {
        val droplet = dropletView ?: return
        droplet.scaleX = fluidStretchRatio
        droplet.scaleY = (1.0f / (fluidStretchRatio * 0.4f + 0.6f))
        droplet.invalidate()
    }

    private fun nearestPageTab(pos: Float): Int {
        val pageIndices = listOf(0, 1, 3, 4)
        var best = 0
        var minDist = Float.MAX_VALUE
        for (idx in pageIndices) {
            val dist = abs(pos - idx)
            if (dist < minDist) {
                minDist = dist
                best = idx
            }
        }
        return best
    }

    private fun getTabIndexAt(x: Float): Int {
        val total = tabCount
        if (total <= 0 || width <= 0) return 0
        val tabW = width.toFloat() / total
        return (x / tabW).toInt().coerceIn(0, total - 1)
    }

    private fun calculateIndicatorPosFromX(x: Float): Float {
        val total = tabCount
        if (total <= 1 || width <= 0) return 0f
        val tabW = width.toFloat() / total
        val pos = (x - tabW * 0.5f) / tabW
        return pos.coerceIn(0f, (total - 1).toFloat())
    }

    private fun setIndicatorPositionInternal(pos: Float) {
        try {
            indicatorPosField?.set(this, pos)
            val droplet = dropletView
            if (droplet != null && isExpanded) {
                val tabW = width.toFloat() / max(1, tabCount)
                val targetW = (tabW * 1.33f).toInt()
                val targetH = dp(76)
                applyCustomDropletGeometry(targetW, targetH, pos)
            } else {
                placeDropletMethod?.invoke(this, true)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun getIndicatorPositionInternal(): Float {
        return try {
            indicatorPosField?.get(this) as? Float ?: 0f
        } catch (e: Throwable) {
            0f
        }
    }

    private fun cancelIndicatorAnimator() {
        try {
            val anim = indicatorAnimatorField?.get(this) as? ValueAnimator
            if (anim != null && anim.isRunning) {
                anim.cancel()
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun animatePostScale(targetScale: Float) {
        val post = postCapsuleView ?: return
        post.animate().scaleX(targetScale).scaleY(targetScale).setDuration(120).start()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpF(v: Float): Float = v * resources.displayMetrics.density
}
