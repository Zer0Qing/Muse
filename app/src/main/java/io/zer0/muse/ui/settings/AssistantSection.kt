package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.navigationItem
import io.zer0.muse.ui.theme.MuseIconSizes

/**
 * 助手资源入口卡片：助手管理 / 收藏夹 / 世界书 / 动态世界书 / 快捷消息 / 模式注入 / 技能。
 */
@Composable
internal fun AssistantEntriesSection(
    onOpenAssistants: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenLorebooks: () -> Unit,
    onOpenWorldbook: () -> Unit,
    onOpenQuickMessages: () -> Unit,
    onOpenPromptInjections: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onOpenSkills: () -> Unit = {},
) {
    val rows = listOf(
        AssistantRow(MuseIcons.users, stringResource(R.string.settings_assistant_manage), onOpenAssistants),
        AssistantRow(MuseIcons.bookmark, stringResource(R.string.settings_assistant_favorites), onOpenFavorites),
        AssistantRow(MuseIcons.book, stringResource(R.string.settings_assistant_lorebook), onOpenLorebooks),
        AssistantRow(MuseIcons.globe, stringResource(R.string.settings_assistant_worldbook), onOpenWorldbook),
        AssistantRow(MuseIcons.chat, stringResource(R.string.settings_assistant_quick_messages), onOpenQuickMessages),
        AssistantRow(MuseIcons.swapHorizontal, stringResource(R.string.settings_assistant_prompt_injection), onOpenPromptInjections),
    )
    CardGroup(title = { Text(stringResource(R.string.settings_assistant_section)) }) {
        rows.forEach { row ->
            navigationItem(
                key = row.title,
                title = row.title,
                leadingContent = {
                    Icon(
                        imageVector = row.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                },
                onClick = row.onClick,
            )
        }
    }
}

private data class AssistantRow(
    val icon: ImageVector,
    val title: String,
    val onClick: () -> Unit,
)
