package io.zer0.muse.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * F3.1 边缘凝光按压指示。
 *
 * 产品前提（不推翻的既有决策）:
 *  - 全站无圆形扩散涟漪遮罩——这里不画从触点扩出去实心圆;
 *  - 光只沿**组件轮廓**出现:外柔光带 + 内锐亮边 + 触点方向偏置的亮段，
 *    抬起即弹簧淡出。
 *
 * 形状与深浅不在绘制期读 CompositionLocal（节点脱离组合上下文），而是
 * 由 [edgeGlowIndication] 在组合期快照后构造节点工厂。容器若想用自己的
 * clip 形状（卡片 20dp、胶囊 50%…），就近包一层
 * [WithMuseGlowShape]/[WithMuseGlowDark] 再取默认工厂即可。
 *
 * 降级契约与 F1 弹簧工厂同源：动画进行中参数就是弹簧；系统"关闭动画"时
 * Animatable 的目标切换依旧收敛（短时窗），不产生永久循环。
 */

/** 凝光强度令牌（浅/深两套；真机验收前视为候选值）。 */
@Immutable
data class MuseGlowConfig(
    val peakAlpha: Float,
    val outerBandDp: Float,
    val innerBandDp: Float,
    /** 触点偏置权重：0=均匀轮廓光，1=亮段几乎全压在触点一侧。 */
    val hotspotGain: Float,
)

object MuseGlowTokens {
    /** 亮主题：轮廓光用墨色（与 pressedVeil 同族的中性向，不引入新色相）。 */
    val Light = MuseGlowConfig(
        peakAlpha = 0.14f,
        outerBandDp = 6f,
        innerBandDp = 1.5f,
        hotspotGain = 0.55f,
    )

    /** 暗主题：反色描边，深底可见。 */
    val Dark = MuseGlowConfig(
        peakAlpha = 0.20f,
        outerBandDp = 6f,
        innerBandDp = 1.5f,
        hotspotGain = 0.55f,
    )

    fun forTheme(darkTheme: Boolean): MuseGlowConfig = if (darkTheme) Dark else Light
}

/** 组件轮廓形状提供者（组合期快照给指示工厂；默认按钮档 12dp）。 */
val LocalMuseGlowShape = compositionLocalOf<Shape> { RoundedCornerShape(MuseCornerRadius.BUTTON.dp) }

/** 就近声明凝光轮廓形状的快捷包装，供 MuseSurface/GlassIsland/MuseListItem 使用。 */
@Composable
fun MuseGlowShapeProvider(shape: Shape, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMuseGlowShape provides shape, content = content)
}

/**
 * 组合期快照构造的凝光工厂：节点全部参数在可组合上下文求值。
 *
 * 深色判定与形状都来自 CompositionLocal，绘制期零读取。
 */
@Composable
fun edgeGlowIndication(): IndicationNodeFactory = MuseEdgeGlowIndication(
    shape = LocalMuseGlowShape.current,
    darkTheme = LocalIsDarkTheme.current,
)

/** 边缘凝光工厂。默认构造保持与 [MuseNoRippleIndication] 相同的调用面。 */
@Immutable
class MuseEdgeGlowIndication(
    private val shape: Shape = RoundedCornerShape(MuseCornerRadius.BUTTON.dp),
    private val darkTheme: Boolean = false,
) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode = EdgeGlowDrawNode(interactionSource, shape, darkTheme)

    // IndicationNodeFactory 契约：相等性参与 Compose 优化，按参数值比较。
    override fun equals(other: Any?): Boolean = other is MuseEdgeGlowIndication && other.shape == shape && other.darkTheme == darkTheme

    override fun hashCode(): Int = 31 * (31 * shape.hashCode() + darkTheme.hashCode()) + 0x4D757667
}

private class EdgeGlowDrawNode(
    private val interactionSource: InteractionSource,
    private val shape: Shape,
    darkTheme: Boolean,
) : Modifier.Node(), DrawModifierNode {

    private val cfg = MuseGlowTokens.forTheme(darkTheme)
    private val glowColor = if (darkTheme) Color.White else Color.Black
    private val progress = Animatable(0f)
    private var pressPosition: Offset = Offset.Zero

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collectLatest { interaction: Interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        pressPosition = interaction.pressPosition
                        progress.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 1500f))
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        progress.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 1200f))
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val p = progress.value
        if (p <= 0.001f) return

        val alpha = cfg.peakAlpha * p

        // 整段凝光裁剪到组件形状后再画：外带压在轮廓外沿的那一半被裁掉，
        // 触点径向亮段也不再以黑色方框铺满 size——圆角/胶囊组件四角不再被"切"出矩形。
        val outline = shape.createOutline(size, layoutDirection, this)
        clipPath(shapeOutlinePath(outline)) {
            strokeShapeOutline(outline, glowColor.copy(alpha = alpha), cfg.outerBandDp.dp.toPx())
            strokeShapeOutline(outline, glowColor.copy(alpha = alpha * 1.35f), cfg.innerBandDp.dp.toPx())
            // 触点偏置的轮廓亮段：径向渐变只提亮触点一侧边缘，裁剪后光影严格随形状。
            if (cfg.hotspotGain > 0f) {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = alpha * cfg.hotspotGain),
                            Color.Transparent,
                        ),
                        center = pressPosition,
                        radius = size.minDimension.coerceAtLeast(1f),
                    ),
                    size = size,
                )
            }
        }
    }
}

/** 由形状 Outline 生成用于裁剪的 Path（Rectangle/Rounded/Generic 均为公开几何数据）。 */
private fun shapeOutlinePath(outline: Outline): Path = Path().apply {
    when (outline) {
        is Outline.Rectangle -> addRect(outline.rect)
        is Outline.Rounded -> addRoundRect(outline.roundRect)
        is Outline.Generic -> addPath(outline.path)
    }
}

/** 按 Outline 三态描边（Rectangle/Rounded/Generic 均为公开几何数据）。 */
private fun DrawScope.strokeShapeOutline(outline: Outline, color: Color, strokeWidth: Float) {
    val style = Stroke(width = strokeWidth)
    when (outline) {
        is Outline.Rectangle ->
            drawRect(color = color, topLeft = outline.rect.topLeft, size = outline.rect.size, style = style)
        is Outline.Rounded -> {
            // RoundRect 只用绝对坐标核心字段（left/top/width/height），派生属性不依赖。
            val rr = outline.roundRect
            drawRoundRect(
                color = color,
                topLeft = Offset(rr.left, rr.top),
                size = androidx.compose.ui.geometry.Size(rr.width, rr.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                    rr.topLeftCornerRadius.x,
                    rr.topLeftCornerRadius.y,
                ),
                style = style,
            )
        }
        is Outline.Generic ->
            drawPath(path = outline.path, color = color, style = style)
    }
}
