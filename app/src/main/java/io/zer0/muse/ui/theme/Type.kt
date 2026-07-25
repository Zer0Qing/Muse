package io.zer0.muse.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.zer0.common.Logger

/**
 * Muse 字体规范(对齐前端设计完整方案 §3)。
 *
 * 字体策略(反 AI 味关键决策):
 *  - 全部使用系统字体(FontFamily.Default),不引入任何自定义字体
 *  - 系统字体渲染最清晰、加载最快、最符合平台用户阅读习惯
 *  - 代码块用 FontFamily.Monospace(系统等宽字体)
 *  - 删除原 Inter / Noto Serif SC / JetBrains Mono Google Fonts 依赖
 *
 * 字号体系(对齐 iOS 标准层级):
 *   - 大标题  displayLarge  34 Bold   "对话" / "记忆" / "设置" 页顶
 *   - 标题    headlineSmall 20 SemiBold 会话标题 / 设置分组标题
 *   - 正文    bodyLarge     16 Regular 消息内容 / 设置项文字
 *   - 辅助    bodyMedium    14 Regular 消息预览 / 思考过程
 *   - 注释    bodySmall     12 Regular 时间戳 / 模型标签
 *   - Tab文字 labelSmall    11 Medium  底部 Tab 标签(M-TP2:≥11sp 满足无障碍最小字号)
 *
 * 行高:正文 1.6 倍(16sp → 25.6sp),比常规 1.4-1.5 更宽松,
 *      长时间阅读 AI 回复时显著降低疲劳。
 */
val MuseTypography: Typography = Typography(
    // ── 标题层 ───────────────────────────────────────────────────────────
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 41.sp,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),

    // ── 标题层 ───────────────────────────────────────────────────────────
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),

    // ── 正文层(聊天消息主力) ──────────────────────────────────────────
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 25.6.sp,  // 1.6 倍行高,长文阅读舒适
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.4.sp,  // 1.6 倍行高
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 19.2.sp,  // L-TP3: 1.6 倍行高,与 bodyLarge/bodyMedium 体系一致
    ),

    // ── 标签层(按钮 / Tab / 操作行) ────────────────────────────────────
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,  // M-TP2: 10→11sp,满足无障碍建议最小字号
        lineHeight = 16.sp,
    ),
)

/** 代码块等宽字体(系统 Monospace,不引入自定义字体)。 */
val MuseMonoFontFamily: FontFamily = FontFamily.Monospace

/**
 * 字号缩放 — 将整个 Typography 的 fontSize / lineHeight 按比例放大或缩小。
 *
 * 4 档: small=0.85x / medium=1.0x(默认) / large=1.15x / xlarge=1.3x。
 * 只缩放文字尺寸,不影响字重和字体族。
 *
 * @param scale 字号档位 "small" / "medium" / "large" / "xlarge"
 */
fun Typography.scaled(scale: String): Typography {
    val factor = when (scale) {
        "small" -> 0.85f
        "large" -> 1.15f
        "xlarge" -> 1.3f
        else -> {
            // L-6: 未知档位静默回退 medium 不利于排查,此处告警并回退 medium(1.0x)。
            Logger.w("MuseTypography", "未知 fontSizeScale=$scale,回退 medium")
            1.0f
        }
    }
    if (factor == 1.0f) return this
    return copy(
        displayLarge = displayLarge.scale(factor),
        displayMedium = displayMedium.scale(factor),
        displaySmall = displaySmall.scale(factor),
        headlineLarge = headlineLarge.scale(factor),
        headlineMedium = headlineMedium.scale(factor),
        headlineSmall = headlineSmall.scale(factor),
        titleLarge = titleLarge.scale(factor),
        titleMedium = titleMedium.scale(factor),
        titleSmall = titleSmall.scale(factor),
        bodyLarge = bodyLarge.scale(factor),
        bodyMedium = bodyMedium.scale(factor),
        bodySmall = bodySmall.scale(factor),
        labelLarge = labelLarge.scale(factor),
        labelMedium = labelMedium.scale(factor),
        labelSmall = labelSmall.scale(factor),
    )
}

/** 单个 TextStyle 按比例缩放 fontSize / lineHeight / letterSpacing。 */
private fun TextStyle.scale(factor: Float): TextStyle {
    // L-TP4: letterSpacing 同步按 factor 缩放,避免大字号下字距偏紧。
    // 未显式设置(Unspecified)时保持原样,不强行注入字距。
    val scaledLetterSpacing = if (letterSpacing != TextUnit.Unspecified) {
        letterSpacing * factor
    } else {
        letterSpacing
    }
    // L-7: fontSize / lineHeight 同样需守卫 Unspecified,否则 Unspecified * factor
    // 会注入非预期值(虽然 copy 会保留原值,但显式守卫语义更清晰、与 letterSpacing 对齐)。
    val scaledFontSize = if (fontSize != TextUnit.Unspecified) fontSize * factor else fontSize
    val scaledLineHeight = if (lineHeight != TextUnit.Unspecified) lineHeight * factor else lineHeight
    return copy(
        fontSize = scaledFontSize,
        lineHeight = scaledLineHeight,
        letterSpacing = scaledLetterSpacing,
    )
}
