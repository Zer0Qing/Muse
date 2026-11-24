@file:Suppress("LongMethod", "FunctionNaming")

package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.chat.QuickAction
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseButtonSize
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 消息快捷按钮自定义页。
 *
 * 三级入口：设置 → 聊天设置 → 消息快捷按钮。
 * 上区「已启用」：有序列表，每项可上移/下移/移除；
 * 下区「动作池」：未启用的全部动作，点击加入（追加到队尾）。
 * 结果写入 [ChatPreferences.quickActionOrder]；顶栏「恢复默认」回到默认排布。
 */
@Composable
fun QuickActionsSettingsPage(onBack: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val prefs by settings.chatPreferencesFlow.collectAsStateWithLifecycle(
        initialValue = ChatPreferences(),
    )
    val scope = rememberCoroutineScope()

    fun update(block: (ChatPreferences) -> ChatPreferences) {
        scope.launch { settings.updateChatPreferences(block) }
    }

    val currentOrder: List<QuickAction> =
        if (prefs.quickActionOrder.isNotEmpty()) {
            prefs.quickActionOrder.mapNotNull { QuickAction.fromId(it) }
        } else {
            QuickAction.DEFAULT_ORDER
        }
    val pool: List<QuickAction> = QuickAction.entries.filter { it !in currentOrder }

    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_chat_quick_actions),
        onBack = onBack,
        headerActions = {
            MuseCapsuleButton(
                text = stringResource(R.string.settings_chat_quick_reset),
                onClick = { update { it.copy(quickActionOrder = emptyList()) } },
                variant = IosCapsuleButtonVariant.Secondary,
                size = MuseButtonSize.Compact,
                fillWidth = false,
            )
        },
    ) {
        item(key = "quick_enabled_group") {
            CardGroup(title = { Text(stringResource(R.string.settings_chat_quick_enabled)) }) {
                if (currentOrder.isEmpty()) {
                    content(key = "quick_enabled_empty") {
                        Text(
                            text = stringResource(R.string.settings_chat_quick_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(MusePaddings.cardInner),
                        )
                    }
                } else {
                    currentOrder.forEachIndexed { index, action ->
                        val canMoveUp = index > 0
                        val canMoveDown = index < currentOrder.lastIndex
                        item(
                            key = "enabled_${action.id}",
                            leadingContent = {
                                Icon(
                                    imageVector = action.icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                                )
                            },
                            headlineContent = {
                                Text(
                                    text = stringResource(action.labelRes),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xxs),
                                ) {
                                    MuseTactileButton(
                                        icon = MuseIcons.arrowUp,
                                        enabled = canMoveUp,
                                        tint = if (canMoveUp) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = MuseAlpha.disabled)
                                        },
                                        compact = true,
                                        onClick = {
                                            update { p ->
                                                val newOrder = currentOrder.toMutableList()
                                                val tmp = newOrder[index - 1]
                                                newOrder[index - 1] = newOrder[index]
                                                newOrder[index] = tmp
                                                p.copy(quickActionOrder = newOrder.map { it.id })
                                            }
                                        },
                                    )
                                    MuseTactileButton(
                                        icon = MuseIcons.arrowDown,
                                        enabled = canMoveDown,
                                        tint = if (canMoveDown) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = MuseAlpha.disabled)
                                        },
                                        compact = true,
                                        onClick = {
                                            update { p ->
                                                val newOrder = currentOrder.toMutableList()
                                                val tmp = newOrder[index + 1]
                                                newOrder[index + 1] = newOrder[index]
                                                newOrder[index] = tmp
                                                p.copy(quickActionOrder = newOrder.map { it.id })
                                            }
                                        },
                                    )
                                    MuseTactileButton(
                                        icon = MuseIcons.x,
                                        tint = MaterialTheme.colorScheme.error,
                                        contentDescription = stringResource(R.string.common_delete),
                                        compact = true,
                                        onClick = {
                                            update { p ->
                                                p.copy(
                                                    quickActionOrder = currentOrder
                                                        .filter { it != action }
                                                        .map { it.id },
                                                )
                                            }
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

        item(key = "quick_pool_group") {
            CardGroup(title = { Text(stringResource(R.string.settings_chat_quick_pool)) }) {
                if (pool.isEmpty()) {
                    content(key = "quick_pool_empty") {
                        Text(
                            text = stringResource(R.string.settings_chat_quick_pool_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(MusePaddings.cardInner),
                        )
                    }
                } else {
                    pool.forEach { action ->
                        item(
                            key = "pool_${action.id}",
                            onClick = {
                                update { p ->
                                    // 首次编辑：从默认排布出发追加，避免把旧开关状态清空
                                    val base =
                                        if (p.quickActionOrder.isEmpty()) {
                                            QuickAction.DEFAULT_ORDER.map { it.id }
                                        } else {
                                            p.quickActionOrder
                                        }
                                    p.copy(quickActionOrder = base + action.id)
                                }
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = action.icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                                )
                            },
                            headlineContent = {
                                Text(
                                    text = stringResource(action.labelRes),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                Icon(
                                    imageVector = MuseIcons.plus,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
