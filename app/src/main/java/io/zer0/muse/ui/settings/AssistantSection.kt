package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow

/**
 * 阶段 7: 入口卡片 section — 分组列表。
 *
 * 用 [SettingsGroup] 包裹多个 [SettingsItemRow],行间细分割线,无 elevation。
 * 替代旧的"每行独立 Card + spacedBy"堆叠模式。
 *
 * 包含:Assistant 管理 / 收藏夹 / 世界书 / 快捷消息 / 模式注入。
 *
 * v2.x: 长期记忆开关已移至记忆设置页。
 */
@Composable
internal fun AssistantEntriesSection(
    onOpenAssistants: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenLorebooks: () -> Unit,
    onOpenWorldbook: () -> Unit,
    onOpenQuickMessages: () -> Unit,
    onOpenPromptInjections: () -> Unit,
    onOpenSkills: () -> Unit,
) {
    // ── Phase 8.2: 助手管理 + 收藏夹 + 世界书 + 快捷消息 + 模式注入 ──
    SectionLabel(stringResource(R.string.settings_assistant_section))
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        SettingsItemRow(
            icon = MuseIcons.users,
            title = stringResource(R.string.settings_assistant_manage),
            subtitle = stringResource(R.string.settings_assistant_manage_subtitle),
            onClick = onOpenAssistants,
        ) {
            ChevronRight()
        }
        SettingsItemRow(
            icon = MuseIcons.bookmark,
            title = stringResource(R.string.settings_assistant_favorites),
            subtitle = stringResource(R.string.settings_assistant_favorites_subtitle),
            onClick = onOpenFavorites,
        ) {
            ChevronRight()
        }
        SettingsItemRow(
            icon = MuseIcons.book,
            title = stringResource(R.string.settings_assistant_lorebook),
            subtitle = stringResource(R.string.settings_assistant_lorebook_subtitle),
            onClick = onOpenLorebooks,
        ) {
            ChevronRight()
        }
        // P1-2: 动态世界书(常驻/关键词/正则/深度注入,独立于 Lorebook)
        SettingsItemRow(
            icon = MuseIcons.globe,
            title = stringResource(R.string.settings_assistant_worldbook),
            subtitle = stringResource(R.string.settings_assistant_worldbook_subtitle),
            onClick = onOpenWorldbook,
        ) {
            ChevronRight()
        }
        SettingsItemRow(
            icon = MuseIcons.chat,
            title = stringResource(R.string.settings_assistant_quick_messages),
            subtitle = stringResource(R.string.settings_assistant_quick_messages_subtitle),
            onClick = onOpenQuickMessages,
        ) {
            ChevronRight()
        }
        SettingsItemRow(
            icon = MuseIcons.swapHorizontal,
            title = stringResource(R.string.settings_assistant_prompt_injection),
            subtitle = stringResource(R.string.settings_assistant_prompt_injection_subtitle),
            onClick = onOpenPromptInjections,
        ) {
            ChevronRight()
        }
        SettingsItemRow(
            icon = MuseIcons.puzzle,
            title = stringResource(R.string.settings_assistant_skills),
            subtitle = stringResource(R.string.settings_assistant_skills_subtitle),
            onClick = onOpenSkills,
        ) {
            ChevronRight()
        }
    }
}
