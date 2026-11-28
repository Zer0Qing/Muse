package io.zer0.muse.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 语义状态色 — 深浅色成对定义,随 MuseTheme 自动切换。
 *
 * 用途:成功 / 警告 / 错误 / 信息 / 中性 五类状态语义,
 * 覆盖工具风险等级、Snackbar 反馈、日志等级、文档类型图标等业务场景。
 *
 * 设计原则:
 *  - 浅色主题用深一档色(保证浅底对比度 ≥ 4.5:1)
 *  - 深色主题用亮一档色(保证深底对比度)
 *  - [highlight] 为半透明黄,深浅主题通用(搜索高亮 / 调试标记)
 *
 * 用法:
 * ```
 * color = MaterialTheme.statusColors.success
 * ```
 */
data class MuseStatusColors(
    val success: Color,
    val warning: Color,
    val error: Color,
    val info: Color,
    val neutral: Color,
    /** 半透明高亮底色(搜索命中 / 调试标记),深浅通用。 */
    val highlight: Color,
    /** 成对状态容器浅底色，避免业务层手动用 alpha 拼凑浅底导致深浅色对比度失衡。 */
    val successContainer: Color,
    val warningContainer: Color,
    val errorContainer: Color,
    val infoContainer: Color,
    val neutralContainer: Color,
)

/** 浅色主题状态色 — Material 深档,浅底可读。 */
val LightStatusColors = MuseStatusColors(
    success = Color(0xFF2E7D32),
    warning = Color(0xFFEF6C00),
    error = Color(0xFFD32F2F),
    info = Color(0xFF1976D2),
    neutral = Color(0xFF616161),
    highlight = Color(0x66FFEB3B),
    successContainer = Color(0xFFE6F4EA),
    warningContainer = Color(0xFFFFF3E0),
    errorContainer = Color(0xFFFDECEA),
    infoContainer = Color(0xFFE8F1FC),
    neutralContainer = Color(0xFFF0F0EE),
)

/** 深色主题状态色 — Material 亮档,深底可读。 */
val DarkStatusColors = MuseStatusColors(
    success = Color(0xFF66BB6A),
    warning = Color(0xFFFFB74D),
    error = Color(0xFFEF5350),
    info = Color(0xFF64B5F6),
    neutral = Color(0xFF9E9E9E),
    highlight = Color(0x66FFEB3B),
    successContainer = Color(0xFF1B3322),
    warningContainer = Color(0xFF3A2812),
    errorContainer = Color(0xFF3D1A1A),
    infoContainer = Color(0xFF172B42),
    neutralContainer = Color(0xFF28282A),
)

val LocalStatusColors = compositionLocalOf { LightStatusColors }

/** 便捷访问: `MaterialTheme.statusColors.success`。 */
@Suppress("UnusedReceiverParameter")
val MaterialTheme.statusColors: MuseStatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalStatusColors.current

/**
 * 代码语法高亮色 — 深浅色成对,随 MuseTheme 自动切换。
 *
 * 从 ui/markdown/CodeHighlighter.kt 上移,让代码高亮成为主题体系的一部分,
 * 自定义主题后续可覆盖。
 */
data class MuseCodeColors(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val annotation: Color,
    /** diff 新增行(+)。语义复用 success 色,浅深主题各取对应档位。 */
    val diffAdded: Color,
    /** diff 删除行(-)。语义复用 error 色。 */
    val diffRemoved: Color,
    /** diff hunk 头(+++/---/@@)。语义复用 info 色。 */
    val diffHunk: Color,
)

/** 浅色主题代码高亮(原 CodeHighlighter light 调色板)。 */
val LightCodeColors = MuseCodeColors(
    keyword = Color(0xFF537D96),
    string = Color(0xFF10A37F),
    comment = Color(0xFF8A8275),
    number = Color(0xFFB8702C),
    annotation = Color(0xFF9C8A2C),
    diffAdded = Color(0xFF2E7D32),
    diffRemoved = Color(0xFFD32F2F),
    diffHunk = Color(0xFF1976D2),
)

/** 深色主题代码高亮(原 CodeHighlighter dark 调色板,亮化版)。 */
val DarkCodeColors = MuseCodeColors(
    keyword = Color(0xFF8AB4CC),
    string = Color(0xFF7FD4B0),
    comment = Color(0xFFA89F8E),
    number = Color(0xFFD49060),
    annotation = Color(0xFFC8B860),
    diffAdded = Color(0xFF66BB6A),
    diffRemoved = Color(0xFFEF5350),
    diffHunk = Color(0xFF64B5F6),
)

val LocalCodeColors = compositionLocalOf { LightCodeColors }

/** 便捷访问: `MaterialTheme.codeColors.keyword`。 */
@Suppress("UnusedReceiverParameter")
val MaterialTheme.codeColors: MuseCodeColors
    @Composable
    @ReadOnlyComposable
    get() = LocalCodeColors.current

// ── 品牌色(固定值,不随主题切换) ─────────────────────────────────────
// 第三方服务商品牌识别色,必须与品牌保持一致,深浅主题下不改变。

/** DeepSeek 品牌蓝。 */
val BrandDeepSeek = Color(0xFF4D6BFA)

/** OpenAI 品牌绿。 */
val BrandOpenAI = Color(0xFF10A37F)

/** Anthropic 品牌棕。 */
val BrandAnthropic = Color(0xFFD4A574)

/** Google Gemini 品牌蓝。 */
val BrandGemini = Color(0xFF4285F4)

// ── 消息渠道平台品牌色（深/浅成对，UI 层禁止硬编码色值） ──────────────────

/** 单个消息渠道平台的品牌前景与浅底容器色对。 */
data class MuseChannelBrandPair(
    val accent: Color,
    val container: Color,
)

/** 五大消息渠道平台的品牌色集合，随深浅主题切换。 */
data class MuseChannelBrandColors(
    val weclaw: MuseChannelBrandPair,
    val feishu: MuseChannelBrandPair,
    val telegram: MuseChannelBrandPair,
    val dingtalk: MuseChannelBrandPair,
    val qq: MuseChannelBrandPair,
)

val LightChannelBrandColors = MuseChannelBrandColors(
    weclaw = MuseChannelBrandPair(accent = Color(0xFF07C160), container = Color(0xFFE8F8EE)),
    feishu = MuseChannelBrandPair(accent = Color(0xFF1456F0), container = Color(0xFFEBF2FF)),
    telegram = MuseChannelBrandPair(accent = Color(0xFF229ED9), container = Color(0xFFE8F5FC)),
    dingtalk = MuseChannelBrandPair(accent = Color(0xFF007FFF), container = Color(0xFFE8F2FF)),
    qq = MuseChannelBrandPair(accent = Color(0xFF12B7F5), container = Color(0xFFE7F7FE)),
)

val DarkChannelBrandColors = MuseChannelBrandColors(
    weclaw = MuseChannelBrandPair(accent = Color(0xFF4ADE80), container = Color(0xFF152E20)),
    feishu = MuseChannelBrandPair(accent = Color(0xFF60A5FA), container = Color(0xFF16253E)),
    telegram = MuseChannelBrandPair(accent = Color(0xFF56CCF2), container = Color(0xFF142B3A)),
    dingtalk = MuseChannelBrandPair(accent = Color(0xFF6BA6FF), container = Color(0xFF172740)),
    qq = MuseChannelBrandPair(accent = Color(0xFF5CD1FF), container = Color(0xFF132D3B)),
)

val LocalChannelBrandColors = compositionLocalOf { LightChannelBrandColors }

/** 便捷访问：`MaterialTheme.channelBrandColors.weclaw`。 */
@Suppress("UnusedReceiverParameter")
val MaterialTheme.channelBrandColors: MuseChannelBrandColors
    @Composable
    @ReadOnlyComposable
    get() = LocalChannelBrandColors.current

// ── 记忆星座星图调色板（星夜观星台 / 浅深纸图，零硬编码色值） ──────────────

/** 记忆星座全套配色：覆盖星空底板、七类星体色、四种关系弧线色与悬浮控件色。 */
data class MuseConstellationPalette(
    val canvasBg: Color,
    val canvasNebulaPrimary: Color,
    val canvasNebulaSecondary: Color,
    val stardust: Color,
    val skeletonEdge: Color,
    val identity: Color,
    val preference: Color,
    val relationship: Color,
    val event: Color,
    val goal: Color,
    val medical: Color,
    val general: Color,
    val edgeRelated: Color,
    val edgeCauses: Color,
    val edgePartOf: Color,
    val edgeContradicts: Color,
    val labelText: Color,
    val labelBg: Color,
    val labelBorder: Color,
    val clusterLabelText: Color,
    val clusterLabelBg: Color,
    val overlaySurface: Color,
    val overlayContent: Color,
    val overlayBorder: Color,
    val selectionGlow: Color,
    val starCore: Color,
)

/** 记忆星座两套视觉模式：星夜观星台（默认深空星图）与跟随明暗的纸质星图。 */
data class MuseConstellationColors(
    val observatory: MuseConstellationPalette,
    val paper: MuseConstellationPalette,
)

val ObservatoryConstellationPalette = MuseConstellationPalette(
    canvasBg = Color(0xFF0A0F1D),
    canvasNebulaPrimary = Color(0xFF28427B),
    canvasNebulaSecondary = Color(0xFF134E5E),
    stardust = Color(0xFFD8E4FF),
    skeletonEdge = Color(0xFF7B93C9),
    identity = Color(0xFF60A5FA),
    preference = Color(0xFFFBBF24),
    relationship = Color(0xFFF472B6),
    event = Color(0xFF2DD4BF),
    goal = Color(0xFF4ADE80),
    medical = Color(0xFFFB923C),
    general = Color(0xFFCBD5E1),
    edgeRelated = Color(0xFF7DD3FC),
    edgeCauses = Color(0xFFFCD34D),
    edgePartOf = Color(0xFF5EEAD4),
    edgeContradicts = Color(0xFFF87171),
    labelText = Color(0xFFF1F5F9),
    labelBg = Color(0xDD131B2E),
    labelBorder = Color(0x4D94A3B8),
    clusterLabelText = Color(0xFFE2E8F0),
    clusterLabelBg = Color(0xD91A243B),
    overlaySurface = Color(0xE6141C2F),
    overlayContent = Color(0xFFF8FAFC),
    overlayBorder = Color(0x3D94A3B8),
    selectionGlow = Color(0xFFFFFFFF),
    starCore = Color(0xFFF8FAFC),
)

val LightPaperConstellationPalette = MuseConstellationPalette(
    canvasBg = Color(0xFFF7F4EC),
    canvasNebulaPrimary = Color(0xFF3B6FD8),
    canvasNebulaSecondary = Color(0xFF0E7490),
    stardust = Color(0xFF64748B),
    skeletonEdge = Color(0xFF475569),
    identity = Color(0xFF2563EB),
    preference = Color(0xFFD97706),
    relationship = Color(0xFFDB2777),
    event = Color(0xFF0D9488),
    goal = Color(0xFF16A34A),
    medical = Color(0xFFEA580C),
    general = Color(0xFF475569),
    edgeRelated = Color(0xFF0284C7),
    edgeCauses = Color(0xFFB45309),
    edgePartOf = Color(0xFF0F766E),
    edgeContradicts = Color(0xFFDC2626),
    labelText = Color(0xFF1E293B),
    labelBg = Color(0xEBFFFFFF),
    labelBorder = Color(0x3364748B),
    clusterLabelText = Color(0xFF0F172A),
    clusterLabelBg = Color(0xF0FFFFFF),
    overlaySurface = Color(0xEBFFFFFF),
    overlayContent = Color(0xFF0F172A),
    overlayBorder = Color(0x2E64748B),
    selectionGlow = Color(0xFF1D4ED8),
    starCore = Color(0xFFFFFFFF),
)

val DarkPaperConstellationPalette = MuseConstellationPalette(
    canvasBg = Color(0xFF12151E),
    canvasNebulaPrimary = Color(0xFF365494),
    canvasNebulaSecondary = Color(0xFF165B6E),
    stardust = Color(0xFFCBD5E1),
    skeletonEdge = Color(0xFF64748B),
    identity = Color(0xFF60A5FA),
    preference = Color(0xFFFBBF24),
    relationship = Color(0xFFF472B6),
    event = Color(0xFF2DD4BF),
    goal = Color(0xFF4ADE80),
    medical = Color(0xFFFB923C),
    general = Color(0xFFCBD5E1),
    edgeRelated = Color(0xFF7DD3FC),
    edgeCauses = Color(0xFFFCD34D),
    edgePartOf = Color(0xFF5EEAD4),
    edgeContradicts = Color(0xFFF87171),
    labelText = Color(0xFFE2E8F0),
    labelBg = Color(0xE01B202C),
    labelBorder = Color(0x4794A3B8),
    clusterLabelText = Color(0xFFF8FAFC),
    clusterLabelBg = Color(0xE0212736),
    overlaySurface = Color(0xEB1B202C),
    overlayContent = Color(0xFFF8FAFC),
    overlayBorder = Color(0x3394A3B8),
    selectionGlow = Color(0xFF93C5FD),
    starCore = Color(0xFFF8FAFC),
)

val LocalConstellationColors = compositionLocalOf {
    MuseConstellationColors(
        observatory = ObservatoryConstellationPalette,
        paper = LightPaperConstellationPalette,
    )
}

/** 便捷访问：`MaterialTheme.constellationColors.observatory`。 */
@Suppress("UnusedReceiverParameter")
val MaterialTheme.constellationColors: MuseConstellationColors
    @Composable
    @ReadOnlyComposable
    get() = LocalConstellationColors.current

// ── 对话语音条色彩令牌（深/浅双套，零硬编码） ────────────────────────────

data class MuseVoiceBubbleColors(
    val assistantBg: Color,
    val assistantBorder: Color,
    val assistantAccent: Color,
    val assistantWaveActive: Color,
    val assistantWaveInactive: Color,
    val assistantText: Color,
    val assistantSubtext: Color,
    val userBg: Color,
    val userBorder: Color,
    val userAccent: Color,
    val userWaveActive: Color,
    val userWaveInactive: Color,
    val userText: Color,
    val userSubtext: Color,
    val unreadDot: Color,
    val transcriptBg: Color,
    val transcriptBorder: Color,
    val synthesizingBg: Color,
    val controlBg: Color,
    val controlBorder: Color,
)

val LightVoiceBubbleColors = MuseVoiceBubbleColors(
    assistantBg = Color(0xFFF3F6FC),
    assistantBorder = Color(0xFFDCE4F4),
    assistantAccent = Color(0xFF1F6FEB),
    assistantWaveActive = Color(0xFF1F6FEB),
    assistantWaveInactive = Color(0x471F6FEB),
    assistantText = Color(0xFF1A2233),
    assistantSubtext = Color(0xFF5D6B82),
    userBg = Color(0xFFDCF8C6),
    userBorder = Color(0xFFBDE69F),
    userAccent = Color(0xFF15803D),
    userWaveActive = Color(0xFF15803D),
    userWaveInactive = Color(0x4D15803D),
    userText = Color(0xFF14331E),
    userSubtext = Color(0xFF3E684B),
    unreadDot = Color(0xFFEF4444),
    transcriptBg = Color(0xFFF8FAFC),
    transcriptBorder = Color(0xFFE2E8F0),
    synthesizingBg = Color(0xFFF1F5F9),
    controlBg = Color(0xFFF8FAFC),
    controlBorder = Color(0xFFD8E0EC),
)

val DarkVoiceBubbleColors = MuseVoiceBubbleColors(
    assistantBg = Color(0xFF1D2434),
    assistantBorder = Color(0xFF2D384F),
    assistantAccent = Color(0xFF60A5FA),
    assistantWaveActive = Color(0xFF60A5FA),
    assistantWaveInactive = Color(0x4D60A5FA),
    assistantText = Color(0xFFF1F5F9),
    assistantSubtext = Color(0xFF94A3B8),
    userBg = Color(0xFF193C27),
    userBorder = Color(0xFF27593B),
    userAccent = Color(0xFF4ADE80),
    userWaveActive = Color(0xFF4ADE80),
    userWaveInactive = Color(0x4D4ADE80),
    userText = Color(0xFFECFDF5),
    userSubtext = Color(0xFF86EFAC),
    unreadDot = Color(0xFFF87171),
    transcriptBg = Color(0xFF181E2B),
    transcriptBorder = Color(0xFF2B3548),
    synthesizingBg = Color(0xFF1C2331),
    controlBg = Color(0xFF1E2636),
    controlBorder = Color(0xFF323E54),
)

val LocalVoiceBubbleColors = compositionLocalOf { LightVoiceBubbleColors }

/** 便捷访问：`MaterialTheme.voiceBubbleColors.assistantBg`。 */
@Suppress("UnusedReceiverParameter")
val MaterialTheme.voiceBubbleColors: MuseVoiceBubbleColors
    @Composable
    @ReadOnlyComposable
    get() = LocalVoiceBubbleColors.current
