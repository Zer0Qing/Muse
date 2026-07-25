package io.zer0.muse.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.zer0.ai.core.ProviderType
import io.zer0.muse.R

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
        lower.contains("deepseek") -> Icons.Default.WaterDrop
        type == ProviderType.OPENAI && (lower.contains("relay") || lower.contains("中转")) -> Icons.Default.Hub
        type == ProviderType.OPENAI -> Icons.Default.AutoAwesome
        type == ProviderType.ANTHROPIC -> Icons.Default.WbSunny
        type == ProviderType.GEMINI -> Icons.Default.Diamond
        else -> Icons.Default.Cloud
    }
}

/** 返回 Provider 品牌色。 */
@Composable
fun providerBrandColor(type: ProviderType, name: String): Color {
    val lower = name.lowercase()
    return when {
        lower.contains("deepseek") -> Color(0xFF4D6BFA)
        type == ProviderType.OPENAI -> Color(0xFF10A37F)
        type == ProviderType.ANTHROPIC -> Color(0xFFD4A574)
        type == ProviderType.GEMINI -> Color(0xFF4285F4)
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
