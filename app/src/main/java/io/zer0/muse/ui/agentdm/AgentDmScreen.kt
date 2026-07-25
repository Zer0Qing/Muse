package io.zer0.muse.ui.agentdm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.agentdm.AgentDmRepository
import io.zer0.muse.data.agentdm.AgentMessageEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.ui.common.EmptyState
import io.zer0.muse.ui.common.IosTopBar
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseDateFormats
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.126: Agent 私信收件箱 — 展示所有 Agent 间的私信往来。
 *
 * 功能:
 * - 按时间倒序展示所有私信
 * - 按助手分组显示未读数量
 * - 一键全部标记已读
 * - 点击消息标记单条已读
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentDmScreen(
    onBack: () -> Unit,
    repository: AgentDmRepository = koinInject(),
    assistantRepository: AssistantRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val assistants by assistantRepository.observeAll.collectAsStateWithLifecycle(initialValue = emptyList<io.zer0.muse.data.assistant.AssistantEntity>())

    var messages by remember { mutableStateOf<List<AgentMessageEntity>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // 加载所有消息(按 agent 聚合)
    fun refreshMessages() {
        scope.launch {
            // 取所有助手的收件箱合并
            val allMessages = mutableListOf<AgentMessageEntity>()
            for (assistant in assistants) {
                allMessages.addAll(repository.getInbox(assistant.id, limit = 100))
            }
            // 按时间倒序去重
            messages = allMessages
                .distinctBy { it.id }
                .sortedByDescending { it.createdAt }
            isLoading = false
        }
    }

    LaunchedEffect(assistants) {
        if (assistants.isNotEmpty()) refreshMessages()
        else isLoading = false
    }

    val totalUnread = messages.count { !it.isRead }
    val fmt = remember { SimpleDateFormat(MuseDateFormats.DATE_TIME_SHORT, Locale.getDefault()) }

    Scaffold(
        topBar = {
            IosTopBar(
                title = stringResource(R.string.agent_dm_title),
                onBack = onBack,
                actions = {
                    if (totalUnread > 0) {
                        IconButton(onClick = {
                            scope.launch {
                                for (assistant in assistants) {
                                    repository.markAllRead(assistant.id)
                                }
                                refreshMessages()
                            }
                        }) {
                            Icon(
                                Icons.Default.MarkEmailRead,
                                contentDescription = stringResource(R.string.agent_dm_mark_all_read),
                            )
                        }
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.CircularProgressIndicator()
            }
        } else if (messages.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EmptyState(
                    icon = Icons.Outlined.Mail,
                    title = stringResource(R.string.agent_dm_empty),
                    subtitle = "",
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = MusePaddings.screen),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 未读统计
                if (totalUnread > 0) {
                    item {
                        Text(
                            text = stringResource(R.string.agent_dm_unread_count, totalUnread),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
                items(messages, key = { it.id }) { msg ->
                    AgentMessageCard(
                        message = msg,
                        assistants = assistants,
                        fmt = fmt,
                        onClick = {
                            if (!msg.isRead) {
                                scope.launch {
                                    repository.markRead(msg.id)
                                    refreshMessages()
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentMessageCard(
    message: AgentMessageEntity,
    assistants: List<io.zer0.muse.data.assistant.AssistantEntity>,
    fmt: SimpleDateFormat,
    onClick: () -> Unit,
) {
    val fromName = assistants.firstOrNull { it.id == message.fromAgentId }?.name ?: message.fromAgentId
    val toName = assistants.firstOrNull { it.id == message.toAgentId }?.name ?: message.toAgentId
    val isUnread = !message.isRead

    Surface(
        onClick = onClick,
        shape = MuseShapes.medium,
        color = if (isUnread)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else
            MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 发送方 → 接收方
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (isUnread) {
                    Icon(
                        Icons.Default.Email,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    text = fromName,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "→",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    text = toName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = fmt.format(Date(message.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            // 消息内容
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isUnread)
                    MaterialTheme.colorScheme.onSurface
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
