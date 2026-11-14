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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.memory.pin.PinnedMemoryStore
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.theme.MuseMotion

/**
 * 置顶记忆管理区。
 *
 * 显示所有置顶记忆及移除按钮。
 *
 * 两个动作必须分开，不能共用一个按钮：
 *  - 取消置顶：条目仍在记忆库里，只是不再每轮注入
 *  - 删除：条目从记忆库消失
 *
 * 此前这里只有一个垃圾桶图标，点击实际执行的是「取消置顶」，用户会以为删掉了。
 */
@Composable
fun PinnedMemorySection(
    pinnedEntries: List<PinnedMemoryStore.PinnedEntry>,
    onUnpin: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDedup: () -> Unit,
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
                        modifier = Modifier.padding(start = 8.dp).weight(1f),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    // 去重入口：条目多了以后手动逐条比对不现实。
                    // 只做精确去重（不调模型），语义重复需用户在别处确认。
                    if (pinnedEntries.size > 1) {
                        MuseTactileButton(
                            icon = MuseIcons.check,
                            onClick = onDedup,
                            contentDescription = "Dedup",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                // v1.0.90 fix: 这里原来用 LazyColumn，但本组件是被塞进 MemoryScreen 的
                // LazyColumn item 里的 —— 列表套列表时内层会拿到无限的"最大高度"，
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
                                // LAYOUT-01: Row 内文本取宽 + 限行，防放大字号下逐字换行
                                // (右侧是定宽按钮组，记忆正文留 2 行可读内容)
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // 取消置顶：条目保留在记忆库，只是不再注入
                            MuseTactileButton(
                                icon = MuseIcons.pin,
                                onClick = { onUnpin(entry.id) },
                                contentDescription = "Unpin",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            // 删除：从记忆库移除
                            MuseTactileButton(
                                icon = MuseIcons.trash,
                                onClick = { onDelete(entry.id) },
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}
