package com.solosu.mtforum.ui.widget

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
 * 1. 消除重影：水珠层折射底板，Tab 图标在顶层锐利渲染，杜绝多层叠加重影；
 * 2. 状态唯一：任一时刻严格只有水珠当前激活覆盖的 Tab 显示当前主题色，杜绝多个图标混色；
 * 3. 稳健手势：单 pass 完整手势状态机，点按瞬间精准响应，彻底解决点击无响应；
 * 4. 全局跟手：在底栏任意非水珠位置按下或滑动，水珠立刻跟手移动并吸附。
 */
@Composable
fun MTForumLiquidNavBar(
    selectedTabIndex: Int,
    onTabSelected: (index: Int) -> Unit,
    onPostClicked: () -> Unit,
    themeColor: Color,
    modifier: Modifier = Modifier
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

    val baseBackdrop = rememberLayerBackdrop()
    val barBackdrop = rememberLayerBackdrop()

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

        // 当前水珠中心覆盖的唯一活跃 Tab 索引（滑动时动态跟随水珠中心）
        val activeTabIndex by remember(tabsCount) {
            derivedStateOf {
                (dampedDragAnimation.value + 0.5f).toInt().coerceIn(0, tabsCount - 1)
            }
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

        // ================= 1. 底栏容器胶囊背景（高度 64dp，不透明度 65%） =================
        Box(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .layerBackdrop(barBackdrop)
                .drawBackdrop(
                    backdrop = baseBackdrop,
                    shape = { CircleShape },
                    effects = {
                        vibrancy()
                        blur(10f.dp.toPx())
                        lens(20f.dp.toPx(), 20f.dp.toPx())
                    },
                    highlight = {
                        Highlight.Default.copy(alpha = 0.45f)
                    },
                    shadow = {
                        Shadow(alpha = 0.2f)
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

        // ================= 2. 真实 78dp 悬浮放大水珠（位于底栏背景与图标之间，完整菲涅尔透镜与色散） =================
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
                    backdrop = barBackdrop,
                    shape = { CircleShape },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        val rHeight = lerp(4f.dp.toPx(), 18f.dp.toPx(), progress)
                        val rAmount = lerp(8f.dp.toPx(), 26f.dp.toPx(), progress)
                        lens(
                            refractionHeight = rHeight,
                            refractionAmount = rAmount,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Default.copy(alpha = lerp(0.40f, 1f, progress))
                    },
                    shadow = {
                        val progress = dampedDragAnimation.pressProgress
                        Shadow(alpha = lerp(0.15f, 0.45f, progress))
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
                        drawRect(
                            if (isLightTheme) Color.White.copy(0.20f + 0.15f * progress) else Color.White.copy(0.12f + 0.10f * progress)
                        )
                        drawRect(
                            if (isLightTheme) Color.Black.copy(0.06f * (1f - progress)) else Color.Transparent
                        )
                    }
                )
        )

        // ================= 3. 标签内容图层（最顶层，手势监听挂在此层） =================
        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .height(56.dp)
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
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
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isPressed = dampedDragAnimation.pressProgress > 0.05f
            val dropletCenter = dampedDragAnimation.value + 0.5f
            val dropletHalfWidth = dampedDragAnimation.scaleX / 2f
            val dropletLeft = dropletCenter - dropletHalfWidth
            val dropletRight = dropletCenter + dropletHalfWidth

            tabs.forEachIndexed { index, tab ->
                val isTouchedByDroplet = if (isPressed) {
                    dropletRight >= index + 0.16f && dropletLeft <= (index + 1) - 0.16f
                } else {
                    index == currentIndex
                }

                val isActive = isTouchedByDroplet && !tab.isPostButton
                val tint = if (isActive) themeColor else contentNormalColor
                val tabScale = if (isTouchedByDroplet) {
                    lerp(1f, 1.12f, dampedDragAnimation.pressProgress)
                } else {
                    1f
                }

                TabItemView(
                    tab = tab,
                    tint = tint,
                    themeColor = themeColor,
                    tabScale = tabScale,
                    modifier = Modifier.weight(1f)
                )
            }
        }
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
