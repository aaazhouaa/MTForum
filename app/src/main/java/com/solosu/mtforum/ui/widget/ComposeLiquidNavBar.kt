package com.solosu.mtforum.ui.widget

import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.solosu.mtforum.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

private val LocalLiquidBottomTabScale = staticCompositionLocalOf { { 1f } }

data class NavTabItem(
    val titleRes: Int,
    val iconRes: Int,
    val isPostButton: Boolean = false
)

/**
 * 完整遵循 Kyant0 官方规范的 LiquidBottomTabs
 * 1. 真实毛玻璃底栏：通过 ViewPager2 画布捕获 + blur(10dp) + 65% 半透明磨砂遮罩，底栏绝不产生怪异彩斑；
 * 2. 0 重影官方架构：全组件严格仅由三层构成（底层常规灰度Tab、中间离屏彩色Tab、顶层折射放大水珠），顶层绝无重复渲染层，物理杜绝重影；
 * 3. 真实菲涅尔透镜与边缘彩虹色散：水珠折射离屏图层，长按放大与跨 Tab 时自动呈现主题色与精致的液态彩虹折射；
 * 4. 全局手势覆盖：点击任意 Tab 极速响应切换，滑动任意非水珠位置水珠自动吸附跟手。
 */
@Composable
fun MTForumLiquidNavBar(
    selectedTabIndex: Int,
    onTabSelected: (index: Int) -> Unit,
    onPostClicked: () -> Unit,
    themeColor: Color,
    modifier: Modifier = Modifier,
    backdropSourceView: View? = null,
    hasUnreadMessage: Boolean = false
) {
    val tabs = remember {
        listOf(
            NavTabItem(R.string.tab_home, R.drawable.ic_home),
            NavTabItem(R.string.tab_circle, R.drawable.ic_discover),
            NavTabItem(R.string.action_post, R.drawable.ic_tab_add, isPostButton = true),
            NavTabItem(R.string.tab_message, R.drawable.ic_message),
            NavTabItem(R.string.tab_profile, R.drawable.ic_profile)
        )
    }

    val isLightTheme = !isSystemInDarkTheme()
    val containerColor =
        if (isLightTheme) Color.White.copy(0.65f)
        else Color(0xFF1E1E20).copy(0.65f)
    val contentNormalColor =
        if (isLightTheme) Color.Black.copy(0.70f)
        else Color.White.copy(0.70f)

    // 捕获底层 ViewPager2 真实内容并施加底色垫层，呈现真实毛玻璃背景
    val navView = LocalView.current
    var refreshTick by remember { mutableLongStateOf(0L) }
    DisposableEffect(backdropSourceView) {
        val source = backdropSourceView ?: return@DisposableEffect onDispose {}
        val listener = android.view.ViewTreeObserver.OnDrawListener {
            refreshTick = System.nanoTime()
        }
        val vto = source.viewTreeObserver
        vto.addOnDrawListener(listener)
        onDispose {
            if (vto.isAlive) {
                vto.removeOnDrawListener(listener)
            } else {
                source.viewTreeObserver.removeOnDrawListener(listener)
            }
        }
    }

    val hostBackdrop = rememberCanvasBackdrop {
        val _tick = refreshTick
        drawRect(if (isLightTheme) Color(0xFFFAFAFA) else Color(0xFF141416))
        val source = backdropSourceView ?: return@rememberCanvasBackdrop
        val canvas = drawContext.canvas.nativeCanvas
        canvas.save()
        val navLoc = IntArray(2)
        val sourceLoc = IntArray(2)
        navView.getLocationInWindow(navLoc)
        source.getLocationInWindow(sourceLoc)
        val dx = (sourceLoc[0] - navLoc[0]).toFloat()
        val dy = (sourceLoc[1] - navLoc[1]).toFloat()
        canvas.translate(dx, dy)
        try {
            source.draw(canvas)
        } catch (_: Throwable) {}
        canvas.restore()
    }
    val tabsBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(84.dp),
        contentAlignment = Alignment.Center
    ) {
        val density = LocalDensity.current
        val viewConfiguration = LocalViewConfiguration.current
        val tabsCount = tabs.size
        val availableWidth = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else with(density) { 360.dp.toPx() }
        val paddingPx = with(density) { 4f.dp.toPx() }
        val tabAreaWidth = (availableWidth - paddingPx * 2f).coerceAtLeast(1f)
        val tabWidth = tabAreaWidth / tabsCount

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / availableWidth).fastCoerceIn(-1f, 1f)
                with(density) { 4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction)) }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var currentIndex by remember { mutableIntStateOf(selectedTabIndex) }

        // 水珠按压放大控制器：56dp 膨胀至 78dp (78f / 56f)
        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTabIndex.toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                initialScale = 1f,
                pressedScale = 78f / 56f
            )
        }

        // 外部 ViewPager2 页面滑动切换联动
        LaunchedEffect(selectedTabIndex) {
            currentIndex = selectedTabIndex
            dampedDragAnimation.animateToValue(selectedTabIndex.toFloat())
        }

        // 拖动时的径向微光高光
        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    Offset(
                        if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidth + paddingPx + panelOffset
                        else size.width - (dampedDragAnimation.value + 0.5f) * tabWidth - paddingPx + panelOffset,
                        size.height / 2f
                    )
                }
            )
        }

        // ================= 1. 底栏容器胶囊背景（官方规范：毛玻璃模糊 + 65%磨砂遮罩 + 官方原版透镜，无怪异彩斑） =================
        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = hostBackdrop,
                    shape = { CircleShape },
                    effects = {
                        vibrancy()
                        blur(10f.dp.toPx())
                        lens(
                            refractionHeight = 24f.dp.toPx(),
                            refractionAmount = 24f.dp.toPx(),
                            depthEffect = false,
                            chromaticAberration = false // 底栏容器不开启色散，杜绝上下怪异彩斑
                        )
                    },
                    layerBlock = {
                        val progress = dampedDragAnimation.pressProgress
                        if (size.width > 0f) {
                            val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                            scaleX = scale
                            scaleY = scale
                        }
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .then(interactiveHighlight.modifier)
                .height(64.dp)
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { _, tab ->
                val showBadge = tab.titleRes == R.string.tab_message && hasUnreadMessage
                TabItemView(
                    tab = tab,
                    tint = contentNormalColor,
                    themeColor = themeColor,
                    showBadge = showBadge,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ================= 2. 官方离屏图层（录制全部 Tab 的高亮主题色） =================
        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.20f, dampedDragAnimation.pressProgress)
            }
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = hostBackdrop,
                        shape = { CircleShape },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            vibrancy()
                            blur(10f.dp.toPx())
                            lens(
                                24f.dp.toPx() * progress,
                                24f.dp.toPx() * progress
                            )
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Default.copy(alpha = progress)
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(interactiveHighlight.modifier)
                    .height(56.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEachIndexed { _, tab ->
                    val showBadge = tab.titleRes == R.string.tab_message && hasUnreadMessage
                    TabItemView(
                        tab = tab,
                        tint = themeColor,
                        themeColor = themeColor,
                        showBadge = showBadge,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // ================= 3. 悬浮放大水珠（位于最顶层，折射 hostBackdrop + tabsBackdrop，官方菲涅尔彩虹色散与透镜） =================
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .align(Alignment.CenterStart)
                .graphicsLayer {
                    translationX =
                        if (isLtr) dampedDragAnimation.value * tabWidth + panelOffset
                        else availableWidth - paddingPx * 2f - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                }
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(emptyBackdrop(), tabsBackdrop),
                    shape = { CircleShape },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        lens(
                            refractionHeight = 10f.dp.toPx() * progress,
                            refractionAmount = 14f.dp.toPx() * progress,
                            depthEffect = true,
                            chromaticAberration = true, // 水珠精美边缘彩虹色散
                            // 色散因子已改为沿边缘均匀衰减（circleMap），不再是对角分段的
                            // (x*y)/(halfW*halfH)，因此重标定强度：边缘总跨度约 1.7dp 的细彩边。
                            dispersionScale = 0.06f
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Default.copy(alpha = progress)
                    },
                    shadow = {
                        val progress = dampedDragAnimation.pressProgress
                        Shadow(alpha = progress)
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(radius = (8f * progress).dp, alpha = progress)
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(
                            if (isLightTheme) Color.Black.copy(0.10f) else Color.White.copy(0.10f),
                            alpha = 1f - progress
                        )
                        drawRect(Color.Black.copy(alpha = 0.03f * progress))
                    }
                )
                .height(56.dp)
                .fillMaxWidth(1f / tabsCount)
        )

        // ================= 4. 全局手势交互层（点击极速切页，滑动非水珠位置水珠自动吸附移动） =================
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(animationScope, tabWidth, tabsCount, isLtr) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val rawDownX = (down.position.x - paddingPx).coerceIn(0f, tabAreaWidth)
                        val downTab = if (isLtr) {
                            (rawDownX / tabWidth).toInt().coerceIn(0, tabsCount - 1)
                        } else {
                            (tabsCount - 1 - (rawDownX / tabWidth).toInt()).coerceIn(0, tabsCount - 1)
                        }

                        var isDragging = false
                        var lastX = down.position.x

                        // 滑动非水珠当前位置水珠自动移动到当前位置
                        val touchCenterFraction = if (isLtr) {
                            (rawDownX / tabWidth - 0.5f).fastCoerceIn(0f, (tabsCount - 1).toFloat())
                        } else {
                            ((tabAreaWidth - rawDownX) / tabWidth - 0.5f).fastCoerceIn(0f, (tabsCount - 1).toFloat())
                        }
                        dampedDragAnimation.updateValue(touchCenterFraction)
                        dampedDragAnimation.press()

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.fastFirstOrNull { it.id == down.id } ?: break

                            if (change.changedToUpIgnoreConsumed()) {
                                change.consume()
                                dampedDragAnimation.release()

                                if (!isDragging) {
                                    // 点击底栏 tab 极速响应切换
                                    if (downTab == 2) {
                                        onPostClicked()
                                        dampedDragAnimation.animateToValue(currentIndex.toFloat())
                                    } else {
                                        currentIndex = downTab
                                        dampedDragAnimation.animateToValue(downTab.toFloat())
                                        onTabSelected(downTab)
                                    }
                                } else {
                                    // 拖拽松手吸附
                                    val releaseTab = (dampedDragAnimation.value + 0.5f).toInt().coerceIn(0, tabsCount - 1)
                                    if (releaseTab == 2) {
                                        dampedDragAnimation.animateToValue(currentIndex.toFloat())
                                    } else {
                                        currentIndex = releaseTab
                                        dampedDragAnimation.animateToValue(releaseTab.toFloat())
                                        onTabSelected(releaseTab)
                                    }
                                }
                                animationScope.launch {
                                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                                }
                                break
                            }

                            if (!change.pressed) {
                                dampedDragAnimation.release()
                                dampedDragAnimation.animateToValue(currentIndex.toFloat())
                                animationScope.launch {
                                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                                }
                                break
                            }

                            val deltaX = change.position.x - down.position.x
                            val stepDeltaX = change.position.x - lastX
                            lastX = change.position.x

                            if (!isDragging && abs(deltaX) > viewConfiguration.touchSlop) {
                                isDragging = true
                            }

                            if (isDragging) {
                                change.consume()
                                val currentTouchX = (change.position.x - paddingPx).coerceIn(0f, tabAreaWidth)
                                val currentFraction = if (isLtr) {
                                    (currentTouchX / tabWidth - 0.5f).fastCoerceIn(0f, (tabsCount - 1).toFloat())
                                } else {
                                    ((tabAreaWidth - currentTouchX) / tabWidth - 0.5f).fastCoerceIn(0f, (tabsCount - 1).toFloat())
                                }
                                dampedDragAnimation.updateValue(currentFraction)
                                animationScope.launch {
                                    offsetAnimation.snapTo(offsetAnimation.value + stepDeltaX)
                                }
                            }
                        }
                    }
                }
        )
    }
}

@Composable
private fun TabItemView(
    tab: NavTabItem,
    tint: Color,
    themeColor: Color,
    showBadge: Boolean = false,
    modifier: Modifier = Modifier
) {
    val scale = LocalLiquidBottomTabScale.current
    Column(
        modifier = modifier
            .clip(CircleShape)
            .fillMaxHeight()
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
            },
        verticalArrangement = Arrangement.spacedBy(2f.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val painter: Painter = painterResource(tab.iconRes)
        if (tab.isPostButton) {
            Box(
                Modifier
                    .size(width = 44.dp, height = 28.dp)
                    .clip(CircleShape)
                    .background(themeColor),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(
                    painter = painter,
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color.White),
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            Box(
                modifier = Modifier.size(24.dp)
            ) {
                androidx.compose.foundation.Image(
                    painter = painter,
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(tint),
                    modifier = Modifier.size(24.dp)
                )
                if (showBadge) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(7.dp)
                            .background(Color(0xFFFF3B30), CircleShape)
                    )
                }
            }
            BasicText(
                text = stringResource(tab.titleRes),
                style = TextStyle(color = tint, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            )
        }
    }
}

// ==================== 阻尼弹簧动画与手势物理 ====================

private class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val initialScale: Float,
    val pressedScale: Float
) {
    private val valueAnimationSpec = spring(1f, 1000f, 0.001f)
    private val velocityAnimationSpec = spring(0.5f, 300f, 0.01f)
    private val pressProgressAnimationSpec = spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec = spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec = spring(0.7f, 250f, 0.001f)

    private val valueAnimation = Animatable(initialValue, 0.001f)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)

    private val velocityTracker = VelocityTracker()

    val value: Float get() = valueAnimation.value
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    fun updateValue(value: Float) {
        val target = value.coerceIn(valueRange)
        animationScope.launch {
            launch {
                valueAnimation.animateTo(target, valueAnimationSpec) {
                    updateVelocity()
                }
            }
        }
    }

    fun animateToValue(value: Float) {
        val target = value.coerceIn(valueRange)
        animationScope.launch {
            launch { valueAnimation.animateTo(target, spring(1f, 500f, 0.001f)) }
            if (velocity != 0f) {
                launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
            }
        }
    }

    private fun updateVelocity() {
        val now = System.currentTimeMillis()
        velocityTracker.addPosition(now, Offset(value, 0f))
        val targetVelocity = velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        animationScope.launch { velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec) }
    }
}
