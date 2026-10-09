@file:Suppress("MatchingDeclarationName", "TooManyFunctions")

package io.zer0.muse.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import io.github.fletchmckee.liquid.LiquidState
import io.github.fletchmckee.liquid.liquid

/**
 * v2.6: 液态玻璃效果中枢（真玻璃引擎）。
 *
 * ## 从 v2.5.2 到 v2.6 的实质变化
 * v2.5.2 用「Haze 1.5.3 模糊底 + 自绘三要素」，因为当时项目在 Compose 1.7.x，
 * 而专用玻璃库都要求 Compose 1.10+（Haze 2.x / backdrop / liquid 同理）。
 * v2.6 起项目已升级到 Compose 1.10.4，门槛清掉，改用**专用引擎**：
 *
 * | 风格 | 引擎 | 效果栈 |
 * |---|---|---|
 * | FROST（磨砂） | kyant `backdrop` | vibrancy + blur + lens(折射/色散) + Highlight + Shadow |
 * | WATER（水玻璃） | fletchmckee `liquid` | RuntimeShader(liquid)：frost/curve/refraction/dispersion/saturation/contrast |
 *
 * 两者都从**全局背景层**取景（[LocalLayerBackdrop] / [LocalWaterGlassState]），
 * 而不是各自捕获 —— 所以整屏只登记一次取样源，几十个玻璃面共享，滚动不翻倍。
 *
 * ## 低版本降级
 * - FROST/WATER 真玻璃需要 API 33+（RuntimeShader）。低于 33 或无背景层，
 *   自动回退到 [Modifier.glassEdgeHighlight] + [Modifier.glassBorder] 的假玻璃
 *   （与 v2.5.2 同款高光三要素），观感接近、零成本。
 * - 消息气泡一律走假玻璃（[glassFakeSurfaceColor]）——一屏几十个真模糊会拖垮滚动，
 *   这是刻意保留的性能边界，与 v2.5.2 一致。
 *
 * ## 保留的兼容面
 * [GlassStyle] / [LiquidGlassConfig] / [GlassIsland] / [glassEdgeHighlight] /
 * [glassFakeSurfaceColor] / [glassBorder] / [LocalLiquidGlass] 的签名不变，调用点零改动。
 */

/** 玻璃风格。 */
enum class GlassStyle {
    /** 水玻璃 — 轻薄透亮，高光锐利。 */
    WATER,

    /** 磨砂玻璃 — 厚重乳白，高光柔和。 */
    FROST,
}

/** 液态玻璃全局配置;mode=OFF 或 strength<=0 表示关闭。 */
data class LiquidGlassConfig(
    val style: GlassStyle = GlassStyle.FROST,
    /** 强度 0f..1f;0 = 关闭。 */
    val strength: Float = 0.5f,
    /**
     * v2.6.1: 原始模式(off/water/frost)。
     *
     * 背景:此前 enabled 只看 strength，导致“模式设为关闭但强度滑条不为 0”时
     * 玻璃仍然生效（用户实测：关了动效还有玻璃）。现在 enabled 同时要求 mode != off。
     */
    val mode: String = MODE_OFF,
) {
    val enabled: Boolean get() = mode != MODE_OFF && strength > 0.01f

    companion object {
        const val MODE_OFF = "off"
        const val MODE_WATER = "water"
        const val MODE_FROST = "frost"

        fun modeFrom(value: String?): String = when (value) {
            MODE_OFF, MODE_WATER, MODE_FROST -> value
            else -> MODE_OFF
        }

        fun styleFrom(mode: String): GlassStyle = if (mode == MODE_WATER) GlassStyle.WATER else GlassStyle.FROST
    }
}

/** 真玻璃引擎所需的最低 API（RuntimeShader / RenderEffect 完备）。 */
private const val GLASS_MIN_API = Build.VERSION_CODES.TIRAMISU

/** 当前设备是否支持真玻璃（API 33+）。 */
fun isRealGlassSupported(): Boolean = Build.VERSION.SDK_INT >= GLASS_MIN_API

/**
 * 玻璃参数锚点（强度 0 与 1 两端，中间线性插值）。
 *
 * - tint 克制 —— 让模糊与折射看得见（tint 过重会把玻璃盖成半透明灰块）
 * - 水玻璃模糊浅、靠折射/色散撑质感；磨砂模糊深、靠 tint/高光撑厚度
 */
private data class GlassAnchor(
    val blurAt0: Dp,
    val blurAt1: Dp,
    val baseAlphaAt0: Float,
    val baseAlphaAt1: Float,
    val tintAlphaAt0: Float,
    val tintAlphaAt1: Float,
    /** 顶部高光边峰值 alpha（假玻璃与 Highlight 共用）。 */
    val highlightAt0: Float,
    val highlightAt1: Float,
)

private val ANCHORS = mapOf(
    // 水玻璃：极浅模糊 + 强高光 + 薄 tint（iOS 液态观感）
    GlassStyle.WATER to GlassAnchor(
        blurAt0 = 4.dp, blurAt1 = 14.dp,
        baseAlphaAt0 = 0.05f, baseAlphaAt1 = 0.12f,
        tintAlphaAt0 = 0.06f, tintAlphaAt1 = 0.14f,
        highlightAt0 = 0.30f, highlightAt1 = 0.55f,
    ),
    // 磨砂：厚模糊 + 柔高光 + 厚 tint（原生磨砂观感）
    GlassStyle.FROST to GlassAnchor(
        blurAt0 = 14.dp, blurAt1 = 44.dp,
        baseAlphaAt0 = 0.18f, baseAlphaAt1 = 0.34f,
        tintAlphaAt0 = 0.20f, tintAlphaAt1 = 0.38f,
        highlightAt0 = 0.18f, highlightAt1 = 0.32f,
    ),
)

/** 解析后的玻璃参数（供样式与叠层共用）。 */
internal data class ResolvedGlass(
    val blur: Dp,
    val baseAlpha: Float,
    val tintAlpha: Float,
    val highlightAlpha: Float,
    val style: GlassStyle,
)

internal fun resolveGlass(config: LiquidGlassConfig): ResolvedGlass {
    val a = ANCHORS.getValue(config.style)
    val s = config.strength.coerceIn(0f, 1f)
    fun lerp(x: Float, y: Float) = x + (y - x) * s
    return ResolvedGlass(
        blur = Dp(lerp(a.blurAt0.value, a.blurAt1.value)),
        baseAlpha = lerp(a.baseAlphaAt0, a.baseAlphaAt1),
        tintAlpha = lerp(a.tintAlphaAt0, a.tintAlphaAt1),
        highlightAlpha = lerp(a.highlightAt0, a.highlightAt1),
        style = config.style,
    )
}

/** 玻璃 tint 基调色（描边/假玻璃/Highlight 与底色的一致性来源）。 */
internal fun ResolvedGlass.surfaceTint(surfaceColor: Color): Color =
    surfaceColor.copy(alpha = (tintAlpha + baseAlpha * 0.35f).coerceIn(0f, 0.6f))

/**
 * v2.6: FROST 真玻璃 —— kyant backdrop 引擎。
 *
 * 效果栈：vibrancy（提升饱和度）+ blur（模糊）+ lens（折射/色散），
 * 外加顶部高光边（Highlight）与投影（Shadow），最后叠一层半透明 tint。
 *
 * 必须在已挂 [LocalLayerBackdrop]（内容层）的树上使用；backdrop 为 null 时
 * 调用方应回退到假玻璃（[glassIslandModifier] 已处理该分支）。
 */
@Composable
fun Modifier.frostGlass(backdrop: LayerBackdrop?, shape: Shape, surfaceColor: Color, config: LiquidGlassConfig): Modifier {
    if (backdrop == null || !config.enabled) return this
    val p = resolveGlass(config)
    val tint = p.surfaceTint(surfaceColor)
    val edgeWidth = 1.dp
    val shadowRadius = 14.dp
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(p.blur.toPx())
            lens(
                refractionHeight = 12.dp.toPx(),
                refractionAmount = 18.dp.toPx(),
                chromaticAberration = true,
            )
        },
        highlight = {
            Highlight(
                width = edgeWidth,
                blurRadius = edgeWidth * 2.4f,
                alpha = p.highlightAlpha,
            )
        },
        shadow = {
            Shadow(
                radius = shadowRadius,
                color = Color.Black.copy(alpha = if (surfaceColor.luminance() >= 0.5f) 0.10f else 0.18f),
            )
        },
        onDrawSurface = {
            drawRect(tint)
        },
    )
}

/**
 * v2.6: WATER 真玻璃 —— fletchmckee liquid 引擎（RuntimeShader）。
 *
 * 与 [frostGlass] 的差别：走真·折射着色器（curve/frost/refraction/dispersion），
 * 观感更接近液态玻璃。需要先由调用方在内容层挂 `Modifier.liquefiable(state)`。
 */
@Composable
fun Modifier.waterGlass(state: LiquidState?, shape: Shape, surfaceColor: Color, config: LiquidGlassConfig): Modifier {
    if (state == null || !config.enabled) return this
    val p = resolveGlass(config)
    val light = surfaceColor.luminance() >= 0.5f
    val tint = p.surfaceTint(surfaceColor)
    return this.liquid(state) {
        this.shape = shape
        this.frost = if (light) 6.dp else 8.dp
        this.curve = if (light) 0.40f else 0.30f
        this.refraction = if (light) 0.12f else 0.09f
        this.dispersion = if (light) 0.18f else 0.13f
        this.saturation = if (light) 0.40f else 0.32f
        this.contrast = if (light) 1.22f else 1.40f
        this.tint = tint
    }
}

/**
 * v2.5.2: 玻璃边缘三要素叠层 —— 假玻璃（无模糊）的质感来源。
 *
 * 依次画：
 * 1. 顶部高光边：从上往下的白色渐隐（玻璃厚度感的主要来源）
 * 2. 底部暗边：从下往上的黑色微渐隐（体积感）
 * 3. 斜向光扫：约 35° 的白色线性渐变（环境光反射）
 *
 * v2.6 起既服务低版本降级，也服务于「真玻璃上再加一层边缘光」的可选叠加。
 */
fun Modifier.glassEdgeHighlight(shape: Shape, config: LiquidGlassConfig): Modifier = this.clip(shape).drawWithContent {
    drawContent()
    val p = resolveGlass(config)
    val w = size.width
    val h = size.height
    val hi = p.highlightAlpha

    // 1. 顶部高光边 —— 1.5dp 内从亮到透明
    val topBand = 1.5.dp.toPx()
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = hi),
            1f to Color.Transparent,
            startY = 0f,
            endY = topBand,
        ),
        size = Size(w, topBand),
    )
    // 2. 底部暗边 —— 1dp 内从暗到透明（体积感）
    val bottomBand = 1.dp.toPx()
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.Transparent,
            1f to Color.Black.copy(alpha = hi * 0.35f),
            startY = h - bottomBand,
            endY = h,
        ),
        topLeft = Offset(0f, h - bottomBand),
        size = Size(w, bottomBand),
    )
    // 3. 斜向光扫 —— 左上到右下，白色渐隐（环境光）
    drawRect(
        brush = Brush.linearGradient(
            0f to Color.White.copy(alpha = hi * 0.55f),
            0.45f to Color.Transparent,
            start = Offset(0f, 0f),
            end = Offset(w * 0.85f, h),
        ),
    )
}

/**
 * v2.5.2: 假玻璃底 —— 给气泡这类高频、滚动中的表面用（不跑模糊）。
 *
 * 用垂直渐变替代模糊：顶部略亮（环境光）、底部略深（体积），叠加在基调色上。
 * 配合 [glassEdgeHighlight] 即可获得近似玻璃观感，成本几乎为零。
 */
fun glassFakeSurfaceColor(base: Color, config: LiquidGlassConfig): Brush {
    val p = resolveGlass(config)
    val lift = 0.10f * (p.highlightAlpha / 0.55f).coerceIn(0.3f, 1f)
    fun mix(alpha: Float) = androidx.compose.ui.graphics.lerp(base, Color.White, alpha)
    return Brush.verticalGradient(
        0f to mix(p.baseAlpha + lift),
        0.55f to mix(p.baseAlpha + p.tintAlpha * 0.6f),
        1f to mix(p.baseAlpha + p.tintAlpha),
    )
}

/**
 * v2.6.5: 液态玻璃薄膜 —— ColorOS 17 风格（供悬浮元素 / 浮层用）。
 *
 * 实测 ColorOS 玻璃特征（.design_library 采样）：
 * - 膜内**几乎无渐变**（跨 140px 只差 ~20 灰阶）——是一层半透明薄膜，不是塑料块；
 * - 与背景明度差很小（10~20 灰阶），安静克制；
 * - 只有一条**极细的亮边**和柔和的顶部微光。
 *
 * 与旧假玻璃的区别：去掉强垂直渐变、斜向光扫、0.8dp 渐变描边（那些造成“拟物塑料感”）。
 * 本修饰符不跑模糊——给高频、性能敏感的场景；真模糊场景走 frostGlass / waterGlass。
 *
 * @param baseColor 基色（容器色 / 主题色），膜即在此色上盖一层极淡的均匀薄膜
 * @param alpha 膜的透明度（越小越透）
 */
fun Modifier.liquidFilm(
    shape: Shape,
    baseColor: Color,
    enabled: Boolean = true,
    alpha: Float = 0.16f,
): Modifier {
    if (baseColor.alpha <= 0.01f) return this
    val dim = if (enabled) 1f else MuseActionColors.disabledAlpha
    // 几乎均匀的膜：只保留极轻微的上下差（5%），避免“平板感”但绝不造成渐变块。
    val topLift = if (baseColor.luminance() > 0.5f) 0.06f else 0.04f
    val bottomDrop = if (baseColor.luminance() > 0.5f) 0.02f else 0.03f
    val film = Brush.verticalGradient(
        0f to androidx.compose.ui.graphics.lerp(baseColor, Color.White, topLift).copy(alpha = alpha * dim),
        0.5f to baseColor.copy(alpha = alpha * dim),
        1f to androidx.compose.ui.graphics.lerp(baseColor, Color.Black, bottomDrop).copy(alpha = alpha * dim),
    )
    // 极细亮边：仅顶部 1dp 一道淡白，不做整圈厚描边。
    val edgeTint = Color.White.copy(alpha = 0.30f * dim)
    return this
        .clip(shape)
        .background(film)
        .drawWithContent {
            drawContent()
            val band = 1.dp.toPx()
            drawRect(
                brush = Brush.verticalGradient(
                    0f to edgeTint,
                    1f to Color.Transparent,
                    startY = 0f,
                    endY = band,
                ),
                size = Size(size.width, band),
            )
        }
        .border(0.5.dp, Color.White.copy(alpha = 0.18f * dim), shape)
}

/**
 * v2.5.2: 玻璃描边 —— 上亮下暗的一圈 1px 边，进一步强化"玻璃片"边界。
 * 与 [glassEdgeHighlight] 配合使用（先画高光再描边）。
 */
fun Modifier.glassBorder(shape: Shape, config: LiquidGlassConfig): Modifier {
    val p = resolveGlass(config)
    return this.border(
        width = 0.8.dp,
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = p.highlightAlpha * 0.9f),
            0.5f to Color.White.copy(alpha = p.highlightAlpha * 0.25f),
            1f to Color.Black.copy(alpha = p.highlightAlpha * 0.22f),
        ),
        shape = shape,
    )
}

/**
 * v2.6: 统一装配玻璃外观（供 [GlassIsland] 等复用）。
 *
 * 决策顺序：
 * 1. FROST + 有 backdrop 层 + API 33+ → [frostGlass]
 * 2. WATER + 有 liquidState + API 33+ → [waterGlass]
 * 3. 其余（低版本 / 无层 / 关闭）→ 假玻璃：[glassFakeSurfaceColor] + 高光 + 描边
 */
@Composable
internal fun glassSurfaceModifier(
    shape: Shape,
    surfaceColor: Color,
    config: LiquidGlassConfig,
    backdrop: LayerBackdrop?,
    waterState: LiquidState?,
): Modifier {
    val realGlass = config.enabled && isRealGlassSupported()
    return when {
        realGlass && config.style == GlassStyle.FROST && backdrop != null ->
            Modifier.frostGlass(backdrop, shape, surfaceColor, config)
        realGlass && config.style == GlassStyle.WATER && waterState != null ->
            Modifier.waterGlass(waterState, shape, surfaceColor, config)
        else ->
            // 降级：假玻璃（渐变底 + 高光三要素 + 描边）
            Modifier
                .clip(shape)
                .background(glassFakeSurfaceColor(surfaceColor, config))
                .glassEdgeHighlight(shape, config)
                .glassBorder(shape, config)
    }
}

/**
 * v2.6.5: 浮层玻璃 —— 独立窗口(Popup / Dialog / BottomSheet)内用。
 *
 * 跨窗口采不到主窗口背景源，真玻璃会黑屏；也不能依赖 blur。
 * 改用 ColorOS 17 式的**薄膜**表达：几乎均匀的半透明白 + 一道极细亮边，
 * 不再用强渐变 / 斜向光扫 / 厚描边（那些会造成“拟物硬塑料”的过时感）。
 */
@Composable
fun Modifier.fakeGlassSurface(shape: Shape, surfaceColor: Color): Modifier {
    val config = LocalLiquidGlass.current
    // 无模糊的浮层只能靠不透明度撑开“面”：ColorOS 的膜靠 blur 糊掉背景，
    // 跨窗口做不了 blur，所以这里用较高的不透明度（0.82~0.94）做干净的半透明面，
    // 配合 fine 亮边，而不是低透明度的“雾”（那会让菜单与背景融成一片）。
    val filmAlpha = (0.82f + config.strength.coerceIn(0f, 1f) * 0.12f)
    return this.liquidFilm(
        shape = shape,
        baseColor = surfaceColor,
        alpha = filmAlpha,
    )
}

/** 液态玻璃配置的全局快照;MainActivity 收集设置流后提供。默认关闭。 */
val LocalLiquidGlass = staticCompositionLocalOf { LiquidGlassConfig(strength = 0f) }

/**
 * v2.6: 全局 FROST 背景层（kyant backdrop）。内容层用 `Modifier.layerBackdrop(it)` 登记；
 * null = 玻璃未启用或不支持。
 */
val LocalLayerBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * v2.6: 全局 WATER 状态（fletchmckee liquid）。内容层用 `Modifier.liquefiable(it)` 登记；
 * null = 玻璃未启用或不支持。
 */
val LocalWaterGlassState = staticCompositionLocalOf<LiquidState?> { null }

/**
 * v2.6: 玻璃背后背景是否偏暗。
 *
 * 玻璃是半透明的，岛内内容色必须与“透出来的背景”对比才能看清。
 * 由页面根据自身背景决定（无背景/浅色渐变=false，深色渐变/深色背景图=true），
 * [GlassIsland] 与顶栏标题据此选择深/浅内容色。
 */
val LocalGlassIsBackgroundDark = staticCompositionLocalOf { false }
