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
)

/** 浅色主题状态色 — Material 深档,浅底可读。 */
val LightStatusColors = MuseStatusColors(
    success = Color(0xFF2E7D32),
    warning = Color(0xFFEF6C00),
    error = Color(0xFFD32F2F),
    info = Color(0xFF1976D2),
    neutral = Color(0xFF616161),
    highlight = Color(0x66FFEB3B),
)

/** 深色主题状态色 — Material 亮档,深底可读。 */
val DarkStatusColors = MuseStatusColors(
    success = Color(0xFF66BB6A),
    warning = Color(0xFFFFB74D),
    error = Color(0xFFEF5350),
    info = Color(0xFF64B5F6),
    neutral = Color(0xFF9E9E9E),
    highlight = Color(0x66FFEB3B),
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
