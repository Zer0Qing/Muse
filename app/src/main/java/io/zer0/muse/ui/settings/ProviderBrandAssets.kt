package io.zer0.muse.ui.settings

import compose.icons.TablerIcons
import compose.icons.tablericons.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.zer0.ai.core.ProviderType
import io.zer0.muse.R
import io.zer0.muse.ui.theme.BrandAnthropic
import io.zer0.muse.ui.theme.BrandDeepSeek
import io.zer0.muse.ui.theme.BrandGemini
import io.zer0.muse.ui.theme.BrandOpenAI

/**
 * Provider 品牌资源映射。
 *
 * 根据 Provider 类型与显示名返回品牌图标、主题色与中文类型名,
 * 用于 Provider 列表行与模型行左侧的圆形图标。
 */

/** 返回 Provider 品牌图标。 */
@Composable
fun providerBrandIcon(type: ProviderType, name: String): ImageVector {
    val lower = name.lowercase()
    return when {
        lower.contains("deepseek") -> TablerIcons.Droplet
        type == ProviderType.OPENAI && (lower.contains("relay") || lower.contains("中转")) -> TablerIcons.Sitemap
        type == ProviderType.OPENAI -> TablerIcons.Stars
        type == ProviderType.ANTHROPIC -> TablerIcons.Sun
        type == ProviderType.GEMINI -> TablerIcons.Diamond
        else -> TablerIcons.Cloud
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
    ProviderType.OPENAI_RESPONSES -> stringResource(R.string.settings_provider_type_openai)
}
