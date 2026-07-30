package io.zer0.muse.ui.common.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Section 分组标题(iOS 风格设置页分组标题)。
 *
 * 用法:`SectionLabel("基础")`
 *
 * 样式:labelLarge + primary 色(蓝色),与 SettingsScreen / AssistantScreen 等 4 个
 * 管理页统一。
 */
@Composable
fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        ),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
    )
}
