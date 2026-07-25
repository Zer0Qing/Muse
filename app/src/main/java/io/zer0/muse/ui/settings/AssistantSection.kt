package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.ChevronRight
import io.zer0.muse.ui.common.SectionLabel
import io.zer0.muse.ui.common.SettingsGroup
import io.zer0.muse.ui.common.SettingsGroupDivider
import io.zer0.muse.ui.common.SettingsItemRow
import io.zer0.muse.ui.common.SettingsSwitchRow

/**
 * 阶段 7: 入口卡片 section — iOS 风格分组列表。
 *
 * 用 [SettingsGroup] 包裹多个 [SettingsItemRow],行间细分割线,无 elevation。
 * 替代旧的"每行独立 Card + spacedBy"堆叠模式。
 *
 * 包含:Assistant 管理 / 收藏夹 / 世界书 / 快捷消息 / 模式注入 + 长期记忆开关。
 */
@Composable
internal fun AssistantEntriesSection(
    onOpenAssistants: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenLorebooks: () -> Unit,
    onOpenQuickMessages: () -> Unit,
    onOpenPromptInjections: () -> Unit,
    onOpenSkills: () -> Unit,
    memoryEnabled: Boolean,
    onMemoryEnabledChange: (Boolean) -> Unit,
) {
    // ── Phase 8.2: 助手管理 + 收藏夹 + 世界书 + 快捷消息 + 模式注入 ──
    SectionLabel(stringResource(R.string.settings_assistant_section))
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        SettingsItemRow(
            icon = Icons.Outlined.AutoAwesome,
            title = stringResource(R.string.settings_assistant_manage),
            subtitle = stringResource(R.string.settings_assistant_manage_subtitle),
            onClick = onOpenAssistants,
        ) {
            ChevronRight()
        }
        SettingsGroupDivider()
        SettingsItemRow(
            icon = Icons.Outlined.Bookmark,
            title = stringResource(R.string.settings_assistant_favorites),
            subtitle = stringResource(R.string.settings_assistant_favorites_subtitle),
            onClick = onOpenFavorites,
        ) {
            ChevronRight()
        }
        SettingsGroupDivider()
        SettingsItemRow(
            icon = Icons.AutoMirrored.Outlined.MenuBook,
            title = stringResource(R.string.settings_assistant_lorebook),
            subtitle = stringResource(R.string.settings_assistant_lorebook_subtitle),
            onClick = onOpenLorebooks,
        ) {
            ChevronRight()
        }
        SettingsGroupDivider()
        SettingsItemRow(
            icon = Icons.Outlined.Bolt,
            title = stringResource(R.string.settings_assistant_quick_messages),
            subtitle = stringResource(R.string.settings_assistant_quick_messages_subtitle),
            onClick = onOpenQuickMessages,
        ) {
            ChevronRight()
        }
        SettingsGroupDivider()
        SettingsItemRow(
            icon = Icons.Outlined.SwapHoriz,
            title = stringResource(R.string.settings_assistant_prompt_injection),
            subtitle = stringResource(R.string.settings_assistant_prompt_injection_subtitle),
            onClick = onOpenPromptInjections,
        ) {
            ChevronRight()
        }
        SettingsGroupDivider()
        SettingsItemRow(
            icon = Icons.Outlined.Extension,
            title = stringResource(R.string.settings_assistant_skills),
            subtitle = stringResource(R.string.settings_assistant_skills_subtitle),
            onClick = onOpenSkills,
        ) {
            ChevronRight()
        }
    }

    // ── 长期记忆开关 ──
    SectionLabel(stringResource(R.string.settings_assistant_long_memory))
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        SettingsSwitchRow(
            icon = Icons.Outlined.Psychology,
            title = stringResource(R.string.settings_assistant_memory_enable),
            subtitle = stringResource(R.string.settings_assistant_memory_enable_subtitle),
            checked = memoryEnabled,
            onCheckedChange = onMemoryEnabledChange,
        )
    }
}
