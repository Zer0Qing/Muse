@file:Suppress("FunctionNaming")

package io.zer0.muse.ui.common.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.zer0.muse.ui.theme.MusePaddings

/**
 * Section 分组标题(设置页分组标题)。
 *
 * 用法:`SectionLabel("基础")`
 *
 * 样式:labelLarge + primary 色(蓝色),与 SettingsScreen / AssistantScreen 等 4 个
 * 管理页统一。
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        // 不再自带 top 间距：使用方的 LazyColumn 已有 spacedBy(组间距)，
        // 两边都加会让分组间隙翻倍，出现「一屏装不下两组」。
        modifier = modifier.padding(bottom = MusePaddings.tightGap),
        style = MaterialTheme.typography.labelLarge.copy(
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        ),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
    )
}
