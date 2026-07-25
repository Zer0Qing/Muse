package io.zer0.muse.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Pending
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import kotlinx.serialization.Serializable

/**
 * 活动面板(openhanako ActivityPanel.tsx 移植)。
 *
 * 展示定时任务/心跳/编译的执行历史及状态。
 */
@Composable
fun ActivityPanel(
    activities: List<ActivityEntry>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(stringResource(R.string.activity_log_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (activities.isEmpty()) {
            item {
                Text(stringResource(R.string.activity_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // v1.131: items 加 key — 列表 reorder/insert/delete 时保留子组件状态(动画/滚动位置),
        // 且避免不必要的重组。fallback 到 timestamp + index 保证 key 唯一性(避免 O(n²) indexOf)。
        itemsIndexed(items = activities, key = { idx, entry -> entry.id.ifEmpty { "${entry.timestamp}-$idx" } }) { _, entry ->
            ActivityCard(entry)
        }
    }
}

@Composable
private fun ActivityCard(entry: ActivityEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (entry.status) {
                "success" -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                "error" -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when (entry.status) {
                    "success" -> Icons.Default.CheckCircle
                    "error" -> Icons.Default.Error
                    else -> Icons.Default.Pending
                },
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = when (entry.status) {
                    "success" -> MaterialTheme.colorScheme.primary
                    "error" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(entry.stepName, style = MaterialTheme.typography.titleSmall)
                if (entry.message.isNotBlank()) {
                    Text(entry.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }
            Text(entry.timestamp, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Serializable
data class ActivityEntry(
    val id: String = "",
    val stepName: String,
    val status: String = "pending", // success / error / pending
    val message: String = "",
    val timestamp: String = "",
)
