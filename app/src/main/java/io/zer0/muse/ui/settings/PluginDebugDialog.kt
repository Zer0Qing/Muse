package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.plugin.PluginBridgeCallLog
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.theme.MusePaddings

/**
 * 插件调试台（插件计划 P4）。
 *
 * 把最近的桥接调用列出来：**哪个插件、哪个动作、放行还是被拒、拒绝原因**。
 * 这些信息此前只在日志文件里，作者得连电脑翻 logcat 才能看到 ——
 * 而"为什么被拒绝"恰恰是写插件时最常卡住的地方。
 *
 * 只显示最近 50 条：调试信息越积越多会把界面拖垮，而要看的就是刚刚发生了什么。
 */
@Composable
fun PluginDebugDialog(onDismiss: () -> Unit) {
    val entries = remember { PluginBridgeCallLog.recent(50) }
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.plugin_debug_title),
        content = {
            if (entries.isEmpty()) {
                Text(
                    text = "还没有桥接调用。装一个插件并让它跑一次，这里就会出现记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    items(entries) { entry ->
                        Column {
                            Text(
                                text = "${if (entry.allowed) "✓" else "✗"} ${entry.pluginId} · ${entry.action}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (entry.allowed) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                            entry.detail?.let { detail ->
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmText = stringResource(R.string.common_close),
        onConfirm = onDismiss,
        dismissText = null,
        onDismiss = onDismiss,
    )
}
