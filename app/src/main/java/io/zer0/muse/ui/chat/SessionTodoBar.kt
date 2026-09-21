package io.zer0.muse.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import compose.icons.TablerIcons
import compose.icons.tablericons.Check
import compose.icons.tablericons.ChevronUp
import compose.icons.tablericons.Circle
import io.zer0.muse.R
import io.zer0.muse.tools.TodoTool
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * v1.0.92: 会话待办条 — 展示 AI 通过 todo_write 维护的任务分解进度。
 *
 * 此前清单仅存内存(TodoTool.sessionTodos),用户看不到 AI 的拆解与执行进度;
 * 有内容时在输入栏上方显示"任务待办 · 完成/总数",点击展开完整清单。
 * 无待办时不占任何空间。
 */
@Composable
fun SessionTodoBar(
    sessionId: String,
    modifier: Modifier = Modifier,
) {
    if (sessionId.isBlank()) return
    val todoList by remember(sessionId) { TodoTool.observeTodos(sessionId) }
        .collectAsStateWithLifecycle(initialValue = TodoTool.TodoList())
    val todos = todoList.todos
    if (todos.isEmpty()) return

    var showSheet by remember { mutableStateOf(false) }
    val doneCount = todos.count { it.status == "completed" }

    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.tightGap),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showSheet = true }
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Icon(
                imageVector = TablerIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(R.string.chat_task_todo_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$doneCount/${todos.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            Icon(
                imageVector = TablerIcons.ChevronUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp),
            )
        }
    }

    if (showSheet) {
        MuseBottomSheet(onDismissRequest = { showSheet = false }) {
            Text(
                text = stringResource(R.string.chat_task_todo_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(MusePaddings.itemGap))
            todos.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MusePaddings.tightGap),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    val completed = item.status == "completed"
                    val inProgress = item.status == "in_progress"
                    Icon(
                        imageVector = if (completed) TablerIcons.Check else TablerIcons.Circle,
                        contentDescription = null,
                        tint = if (completed || inProgress) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (inProgress && item.activeForm.isNotBlank()) item.activeForm else item.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (completed) {
                            MaterialTheme.colorScheme.outline
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
