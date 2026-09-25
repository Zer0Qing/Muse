package io.zer0.muse.ui.memory

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.zer0.memory.pin.PinnedMemoryStore
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.theme.MuseMotion

/**
 * 置顶记忆管理区(既有实现 pinned-memory-store.ts 的 UI)。
 *
 * 显示所有置顶记忆及移除按钮。
 */
@Composable
fun PinnedMemorySection(
    pinnedEntries: List<PinnedMemoryStore.PinnedEntry>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = pinnedEntries.isNotEmpty(),
        modifier = modifier,
        enter = MuseMotion.expandFadeEnter(),
        exit = MuseMotion.expandFadeExit(),
    ) {
        MuseSurface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(MuseIcons.pin, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Text(
                        text = "Pinned Memories (${pinnedEntries.size})",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 8.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                // v1.0.90 fix: 这里原来用 LazyColumn，但本组件是被塞进 MemoryScreen 的
                // LazyColumn item 里的 —— 列表套列表时内层会拿到无限的"最大高度",
                // 直接抛 IllegalStateException 崩溃。改成一屏内的普通展开（项目里同类问题的既有写法）。
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    pinnedEntries.forEach { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = entry.content,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            MuseTactileButton(
                                icon = MuseIcons.trash,
                                onClick = { onRemove(entry.id) },
                                contentDescription = "Remove",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}
