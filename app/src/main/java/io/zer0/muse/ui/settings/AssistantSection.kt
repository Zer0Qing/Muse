package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.CardGroupScope
import io.zer0.muse.ui.theme.MuseIconSizes

/**
 * 阶段 7: 入口卡片 section — 分组列表。
 *
 * 2026-11-22: 从 SettingsGroup + SettingsItemRow 迁到 CardGroup + DSL item。
 * 旧组合已被标记 deprecated（CMP-06），两套并存的直接后果是同一页里
 * 新旧行高、内边距、按压反馈都不一致。
 *
 * 包含:Assistant 管理 / 收藏夹 / 世界书 / 快捷消息 / 模式注入 / 技能。
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
    SectionLabel(stringResource(R.string.settings_assistant_section))
    val rows = listOf(
        Triple(MuseIcons.users, R.string.settings_assistant_manage, R.string.settings_assistant_manage_subtitle) to onOpenAssistants,
        Triple(MuseIcons.bookmark, R.string.settings_assistant_favorites, R.string.settings_assistant_favorites_subtitle) to onOpenFavorites,
        Triple(MuseIcons.book, R.string.settings_assistant_lorebook, R.string.settings_assistant_lorebook_subtitle) to onOpenLorebooks,
        // P1-2: 动态世界书(常驻/关键词/正则/深度注入,独立于 Lorebook)
        Triple(MuseIcons.globe, R.string.settings_assistant_worldbook, R.string.settings_assistant_worldbook_subtitle) to onOpenWorldbook,
        Triple(MuseIcons.chat, R.string.settings_assistant_quick_messages, R.string.settings_assistant_quick_messages_subtitle) to onOpenQuickMessages,
        Triple(MuseIcons.swapHorizontal, R.string.settings_assistant_prompt_injection, R.string.settings_assistant_prompt_injection_subtitle) to onOpenPromptInjections,
        Triple(MuseIcons.puzzle, R.string.settings_assistant_skills, R.string.settings_assistant_skills_subtitle) to onOpenSkills,
    )
    // 文案在 Composable 作用域里先解析好，再进 CardGroup 的普通 DSL 作用域。
    val resolved = rows.map { (meta, onClick) ->
        AssistantRow(
            icon = meta.first,
            title = stringResource(meta.second),
            subtitle = stringResource(meta.third),
            onClick = onClick,
        )
    }
    CardGroup(modifier = Modifier.padding(top = 8.dp)) {
        resolved.forEach { row ->
            item(
                key = row.title,
                onClick = row.onClick,
                leadingContent = {
                    Icon(
                        imageVector = row.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                },
                headlineContent = { Text(row.title) },
                supportingContent = {
                    Text(
                        text = row.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = { ChevronRight() },
            )
        }
    }
}

private data class AssistantRow(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit,
)
