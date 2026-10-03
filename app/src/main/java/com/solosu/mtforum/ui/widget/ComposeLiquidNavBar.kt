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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import kotlin.math.roundToInt
import kotlin.math.sign

data class NavTabItem(
    val titleRes: Int,
    val iconRes: Int,
    val isPostButton: Boolean = false
)

/**
 * MTForum 液态毛玻璃底栏
 * 1. 真实菲涅尔透镜与彩虹色散：捕获背部 ViewPager2 真实内容与离屏色彩，水珠与底栏边缘激发纯正菲涅尔彩虹色散与凸透镜折射；
 * 2. 图标绝不消失：保留顶层清晰 Tab，移除了错误的全透明隐藏逻辑，图标无论何时都清晰呈现；
 * 3. 精准触碰判定：以 Tab 图标实际物理区域为准，长按未碰到相邻 Tab 图标时绝不误亮，移近触碰时可同时点亮 2 个 Tab；
 * 4. 底栏不透明度 65%：浅色/深色统一 65% 纯净磨砂玻璃遮罩；
 * 5. 全局极速响应：顶层手势状态机，点按/滑动随心掌控。
 */
@Composable
fun MTForumLiquidNavBar(
    selectedTabIndex: Int,
    onTabSelected: (index: Int) -> Unit,
    onPostClicked: () -> Unit,
    themeColor: Color,
    modifier: Modifier = Modifier,
    backdropSourceView: View? = null
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
        if (isLightTheme) Color(0xFFFFFFFF.toInt()).copy(0.65f)
        else Color(0xFF202022.toInt()).copy(0.65f)
    val contentNormalColor = if (isLightTheme) Color(0x8C000000.toInt()) else Color(0xB8FFFFFF.toInt())

    val navView = LocalView.current
    val canvasBackdrop = rememberCanvasBackdrop {
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

        // 水珠按压放大控制器：56dp 膨胀至 78dp
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

        val isPressed = dampedDragAnimation.pressProgress > 0.05f
        val dropletCenter = dampedDragAnimation.value + 0.5f
        val dropletHalfWidth = dampedDragAnimation.scaleX / 2f
        val dropletLeft = dropletCenter - dropletHalfWidth
        val dropletRight = dropletCenter + dropletHalfWidth

        // ================= 1. 底栏容器胶囊背景（高度 64dp，不透明度 65%，真实折射背后页面内容并带彩虹色散） =================
        Box(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = canvasBackdrop,
                    shape = { CircleShape },
                    effects = {
                        vibrancy()
                        blur(12f.dp.toPx())
                        lens(
                            refractionHeight = 16f.dp.toPx(),
                            refractionAmount = 24f.dp.toPx(),
                            depthEffect = true,
                            chromaticAberration = true // 底栏彩虹色散效果
                        )
                    },
                    highlight = {
                        Highlight.Default.copy(alpha = 0.65f)
                    },
                    shadow = {
                        Shadow(alpha = 0.25f)
                    },
                    innerShadow = {
                        InnerShadow(radius = 8f.dp, alpha = 0.40f)
                    },
                    layerBlock = {
                        val progress = dampedDragAnimation.pressProgress
                        if (size.width > 0f) {
                            val scale = lerp(1f, 1f + 12f.dp.toPx() / size.width, progress)
                            scaleX = scale
                            scaleY = scale
                        }
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .then(interactiveHighlight.modifier)
                .height(64.dp)
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        )

        // ================= 2. 离屏录制层：为水珠透镜提供彩色主题色折射源（透明不直接绘制到屏） =================
        Row(
            Modifier
                .clearAndSetSemantics {}
                .alpha(0f)
                .layerBackdrop(tabsBackdrop)
                .graphicsLayer { translationX = panelOffset }
                .height(56.dp)
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { _, tab ->
                TabItemView(
                    tab = tab,
                    tint = themeColor,
                    themeColor = themeColor,
                    tabScale = 1f,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ================= 3. 真实 78dp 悬浮放大水珠（折射 canvasBackdrop + tabsBackdrop，绚丽菲涅尔透镜与彩虹色散） =================
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset {
                    val x = if (isLtr) {
                        (paddingPx + dampedDragAnimation.value * tabWidth + panelOffset).roundToInt()
                    } else {
                        (availableWidth - paddingPx - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset).roundToInt()
                    }
                    IntOffset(x, 0)
                }
                .width(with(density) { tabWidth.toDp() })
                .height(56.dp)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(canvasBackdrop, tabsBackdrop),
                    shape = { CircleShape },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        // 真实菲涅尔透镜物理参数：长按放大至 14dp / 22dp，边缘彩虹色散全力激发
                        val rHeight = lerp(4f.dp.toPx(), 14f.dp.toPx(), progress)
                        val rAmount = lerp(6f.dp.toPx(), 22f.dp.toPx(), progress)
                        lens(
                            refractionHeight = rHeight,
                            refractionAmount = rAmount,
                            depthEffect = true,
                            chromaticAberration = true // 菲涅尔透镜彩色色散
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Default.copy(alpha = lerp(0.40f, 1f, progress))
                    },
                    shadow = {
                        val progress = dampedDragAnimation.pressProgress
                        Shadow(alpha = lerp(0.18f, 0.45f, progress))
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(radius = (4f + 4f * progress).dp, alpha = lerp(0.30f, 0.85f, progress))
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
                        // 1. 保持底栏连贯的 65% 磨砂遮罩，杜绝水珠区域穿透挖空露出背后头像
                        drawRect(containerColor)
                        // 2. 叠加水珠菲涅尔液态表面微光反射
                        drawRect(
                            if (isLightTheme) Color.White.copy(0.20f + 0.15f * progress)
                            else Color.White.copy(0.12f + 0.10f * progress)
                        )
                        drawRect(
                            if (isLightTheme) Color.Black.copy(0.06f * (1f - progress))
                            else Color.Transparent
                        )
                    }
                )
        )

        // ================= 4. 可见 Tab 内容图层（顶层清晰可见，绝不消失，水珠触碰时变主题色） =================
        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .height(56.dp)
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, tab ->
                // 图标物理区域在 [index + 0.30f, index + 0.70f]，水珠边缘真正接触图标时才变色
                val isTouchedByDroplet = if (isPressed) {
                    dropletRight >= index + 0.30f && dropletLeft <= (index + 1) - 0.30f
                } else {
                    index == currentIndex
                }

                TabItemView(
                    tab = tab,
                    tint = if (isTouchedByDroplet && !tab.isPostButton) themeColor else contentNormalColor,
                    themeColor = themeColor,
                    tabScale = if (isTouchedByDroplet) lerp(1f, 1.10f, dampedDragAnimation.pressProgress) else 1f,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ================= 5. 全局手势交互层（挂在最顶层，手势点击与滑动极速响应） =================
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

                        // 按下非水珠当前位置时，水珠立即响应移动并按压膨胀
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
                                    // 点击 (Tap)：精确以 downTab 为准
                                    if (downTab == 2) {
                                        onPostClicked()
                                        dampedDragAnimation.animateToValue(currentIndex.toFloat())
                                    } else {
                                        currentIndex = downTab
                                        dampedDragAnimation.animateToValue(downTab.toFloat())
                                        onTabSelected(downTab)
                                    }
                                } else {
                                    // 拖拽松手 (Release)：以水珠中心最近的 Tab 为准
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
    tabScale: Float,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(CircleShape)
            .fillMaxHeight()
            .graphicsLayer {
                scaleX = tabScale
                scaleY = tabScale
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
            androidx.compose.foundation.Image(
                painter = painter,
                contentDescription = null,
                colorFilter = ColorFilter.tint(tint),
                modifier = Modifier.size(24.dp)
            )
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
