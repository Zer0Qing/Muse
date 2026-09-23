package io.zer0.muse.ui.settings

import compose.icons.TablerIcons
import compose.icons.tablericons.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.zer0.ai.core.ProviderType
import io.zer0.muse.R
import io.zer0.muse.ui.theme.BrandAnthropic
import io.zer0.muse.ui.theme.BrandDeepSeek
import io.zer0.muse.ui.theme.BrandGemini
import io.zer0.muse.ui.theme.BrandOpenAI

/**
 * Provider 品牌资源映射。
 *
 * v2.0 改版:
 *  - 头像从"渐变圆 + 抽象图标"改为"品牌色调的圆角方形 logo 砖":
 *    已知厂商用线性图标(Tabler line 风格),未知厂商用名称首字符字标,
 *    统一圆角/底色/描边口径,避免之前渐变圆风格杂乱、辨识度低的问题。
 *  - 品牌色仍来自 theme/StatusColors.kt 固定品牌色,未知厂商回退主题主色。
 */

/** 返回 Provider 品牌图标(已知厂商);未知返回 null,由 [ProviderLogo] 渲染字标。 */
@Composable
fun providerBrandIconOrNull(type: ProviderType, name: String): ImageVector? {
    val lower = name.lowercase()
    return when {
        lower.contains("copilot") || lower.contains("github") -> TablerIcons.BrandGithub
        lower.contains("google") || lower.contains("gemini") || lower.contains("palm") -> TablerIcons.BrandGoogle
        lower.contains("telegram") -> TablerIcons.BrandTelegram
        lower.contains("bing") -> TablerIcons.BrandBing
        lower.contains("deepseek") -> TablerIcons.WaveSine
        lower.contains("anthropic") || lower.contains("claude") -> TablerIcons.Feather
        lower.contains("openai") || lower.contains("gpt") || lower.contains("codex") -> TablerIcons.Star
        lower.contains("qwen") || lower.contains("通义") -> TablerIcons.World
        lower.contains("zhipu") || lower.contains("glm") || lower.contains("智谱") -> TablerIcons.Hexagon
        lower.contains("kimi") || lower.contains("moonshot") -> TablerIcons.MoonStars
        lower.contains("doubao") || lower.contains("volc") || lower.contains("豆包") -> TablerIcons.Flame
        lower.contains("step") || lower.contains("阶跃") -> TablerIcons.Propeller
        lower.contains("silicon") || lower.contains("硅基") -> TablerIcons.LayersSubtract
        lower.contains("hunyuan") || lower.contains("混元") -> TablerIcons.Atom
        lower.contains("baidu") || lower.contains("百度") || lower.contains("千帆") -> TablerIcons.Compass
        lower.contains("modelscope") || lower.contains("魔搭") -> TablerIcons.Box
        lower.contains("infini") || lower.contains("无问") -> TablerIcons.Infinity
        lower.contains("mimo") || lower.contains("xiaomi") || lower.contains("小米") -> TablerIcons.BrandAndroid
        lower.contains("agnes") -> TablerIcons.Wind
        lower.contains("minimax") -> TablerIcons.Stack
        lower.contains("ollama") -> TablerIcons.Server
        lower.contains("openrouter") -> TablerIcons.Route
        lower.contains("groq") -> TablerIcons.Bolt
        lower.contains("together") -> TablerIcons.Users
        lower.contains("mistral") -> TablerIcons.Wind
        lower.contains("fireworks") -> TablerIcons.Flame
        lower.contains("perplexity") -> TablerIcons.Compass
        lower.contains("deepinfra") -> TablerIcons.Server
        lower.contains("xai") || lower.contains("grok") -> TablerIcons.Planet
        lower.contains("relay") || lower.contains("中转") || lower.contains("oneapi") ||
            lower.contains("newapi") || lower.contains("aihubmix") || lower.contains("api2d") ||
            lower.contains("deepbricks") || lower.contains("opencode") -> TablerIcons.Sitemap
        type == ProviderType.ANTHROPIC -> TablerIcons.Feather
        type == ProviderType.GEMINI -> TablerIcons.BrandGoogle
        else -> null
    }
}

/** 返回 Provider 品牌色(固定品牌色,不随主题切换,定义在 theme/StatusColors.kt)。 */
@Composable
fun providerBrandColor(type: ProviderType, name: String): Color {
    val lower = name.lowercase()
    return when {
        lower.contains("deepseek") -> BrandDeepSeek
        type == ProviderType.OPENAI -> BrandOpenAI
        type == ProviderType.ANTHROPIC -> BrandAnthropic
        type == ProviderType.GEMINI -> BrandGemini
        else -> MaterialTheme.colorScheme.primary
    }
}

/** 把 ProviderType 转成中文显示名。 */
@Composable
fun providerDisplayTypeName(type: ProviderType): String = when (type) {
    ProviderType.OPENAI -> stringResource(R.string.settings_provider_type_openai)
    ProviderType.ANTHROPIC -> stringResource(R.string.settings_provider_type_anthropic)
    ProviderType.GEMINI -> stringResource(R.string.settings_provider_type_gemini)
    ProviderType.OPENAI_RESPONSES -> stringResource(R.string.settings_provider_type_openai_responses)
}

/**
 * Provider logo 砖 — 圆角方形 + 品牌色调浅底 + 线性图标/首字符字标。
 *
 * 已知厂商(tabler 线性图标):图标着色品牌色;未知厂商:显示名称首个字母/汉字字标。
 * 底色 = 品牌色 10% 透明度;描边 = 品牌色 18% 透明度;圆角默认 12dp。
 */
@Composable
fun ProviderLogo(
    type: ProviderType,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    cornerRadius: Dp = 12.dp,
) {
    val tint = providerBrandColor(type, name)
    val icon = providerBrandIconOrNull(type, name)
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.18f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                // 图标尺寸按砖块 52% 取整,保证不同尺寸下比例一致
                modifier = Modifier.size(size * 0.52f),
            )
        } else {
            Text(
                text = providerMonogram(name),
                color = tint,
                fontWeight = FontWeight.SemiBold,
                // 按砖块尺寸的 42% 估算字标字号(视觉上与图标同比例)
                fontSize = (size.value * 0.42f).sp,
            )
        }
    }
}

/** 取名称首个字母/汉字作为字标;跳过括号等符号。 */
internal fun providerMonogram(displayName: String): String {
    val trimmed = displayName.trim()
    if (trimmed.isEmpty()) return "?"
    val first = trimmed.firstOrNull { it.isLetterOrDigit() } ?: trimmed.first()
    return first.uppercaseChar().toString()
}
