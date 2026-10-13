@file:Suppress("LongMethod", "FunctionNaming")

package io.zer0.muse.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.chat.QuickAction
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v2.6.0: 消息快捷按钮自定义页（Via 浏览器式）。
 *
 * 三级入口：设置 → 聊天设置 → 消息快捷按钮。
 * 上区「已启用」：有序列表，每项可上移/下移/移除；
 * 下区「动作池」：未启用的全部动作，点击加入（追加到队尾）。
 * 结果写入 ChatPreferences.quickActionOrder；「恢复默认」回到默认排布。
 *
 * 注意：拖拽排序在 Compose 里需要额外手势系统，这里采用 Via 兼容的
 * 上移/下移按钮方案——单击可控、无需长按拖动，后续如需可再加拖拽。
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

    // 当前生效顺序：已配置用配置，未配置展示默认（首次编辑时落盘）
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
    ) {
        item {
            SettingsGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            update { it.copy(quickActionOrder = emptyList()) }
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_chat_quick_reset),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        item { SectionLabel(stringResource(R.string.settings_chat_quick_enabled)) }
        item {
            if (currentOrder.isEmpty()) {
                SettingsGroup {
                    Text(
                        text = stringResource(R.string.settings_chat_quick_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = MusePaddings.tinyGap),
                    )
                }
            } else {
                SettingsGroup {
                    currentOrder.forEachIndexed { index, action ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = action.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = stringResource(action.labelRes),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = MusePaddings.tinyGap),
                            )
                            // 上移
                            Text(
                                text = "↑",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (index > 0) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                },
                                modifier = Modifier
                                    .clickable(enabled = index > 0) {
                                        update { p ->
                                            val newOrder = currentOrder.toMutableList()
                                            val tmp = newOrder[index - 1]
                                            newOrder[index - 1] = newOrder[index]
                                            newOrder[index] = tmp
                                            p.copy(quickActionOrder = newOrder.map { it.id })
                                        }
                                    }
                                    .padding(horizontal = 10.dp),
                            )
                            // 下移
                            Text(
                                text = "↓",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (index < currentOrder.lastIndex) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                },
                                modifier = Modifier
                                    .clickable(enabled = index < currentOrder.lastIndex) {
                                        update { p ->
                                            val newOrder = currentOrder.toMutableList()
                                            val tmp = newOrder[index + 1]
                                            newOrder[index + 1] = newOrder[index]
                                            newOrder[index] = tmp
                                            p.copy(quickActionOrder = newOrder.map { it.id })
                                        }
                                    }
                                    .padding(horizontal = 10.dp),
                            )
                            // 移除
                            Text(
                                text = stringResource(R.string.action_delete),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .clickable {
                                        update { p ->
                                            p.copy(quickActionOrder = currentOrder.filter { it != action }.map { it.id })
                                        }
                                    }
                                    .padding(start = 10.dp),
                            )
                        }
                        if (index < currentOrder.lastIndex) {
                            SettingsGroupDivider()
                        }
                    }
                }
            }
        }
        item { SectionLabel(stringResource(R.string.settings_chat_quick_pool)) }
        item {
            if (pool.isEmpty()) {
                SettingsGroup {
                    Text(
                        text = stringResource(R.string.settings_chat_quick_pool_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = MusePaddings.tinyGap),
                    )
                }
            } else {
                SettingsGroup {
                    pool.forEachIndexed { index, action ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
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
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = action.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = stringResource(action.labelRes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = MusePaddings.tinyGap),
                            )
                            Text(
                                text = "+",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                        }
                        if (index < pool.lastIndex) {
                            SettingsGroupDivider()
                        }
                    }
                }
            }
        }
    }
}
