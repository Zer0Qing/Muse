package io.zer0.muse.ui.groupchat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.groupchat.GroupChatEntity
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.launch
import io.zer0.muse.ui.screen.ArchivedChatsScreen

/**
 * 已归档群聊列表页。
 *
 * 2026-11-20 真机反馈补齐:此前群聊归档后从列表消失,整机找不到任何恢复入口
 * (群聊 Tab 三点菜单只有搜索/工作区,首页已归档页与数据备份归档页都只列会话)。
 * 数据其实一直在 DB 里(group_chats.isArchived=1),只是没有 UI 暴露。
 *
 * 本页与会话的 ArchivedChatsScreen 对齐:列表 + 取消归档 + 撤销。
 */
@Composable
fun ArchivedGroupChatsScreen(
    chats: List<GroupChatEntity>,
    onBack: () -> Unit,
    onUnarchive: (String) -> Unit,
    onOpenChat: (String) -> Unit,
    onUnarchiveUndo: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.groupchat_archived_title),
                onBack = onBack,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        if (chats.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                MuseEmptyState(
                    icon = MuseIcons.archive,
                    title = stringResource(R.string.groupchat_empty_archived),
                )
            }
            return@MusePageScaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(
                horizontal = MusePaddings.screen,
                vertical = MusePaddings.screen,
            ),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            items(chats, key = { it.id }) { chat ->
                ArchivedGroupChatRow(
                    chat = chat,
                    onOpen = { onOpenChat(chat.id) },
                    onUnarchive = {
                        onUnarchive(chat.id)
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = context.getString(R.string.groupchat_unarchived),
                                actionLabel = context.getString(R.string.action_undo),
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                onUnarchiveUndo(chat.id)
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ArchivedGroupChatRow(chat: GroupChatEntity, onOpen: () -> Unit, onUnarchive: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = MusePaddings.itemGap, vertical = MusePaddings.tightGap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.iconPadding),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chat.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = MuseDateFormats.dateTimeShort().format(java.util.Date(chat.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MuseTactileButton(
                icon = MuseIcons.archive,
                onClick = onUnarchive,
                contentDescription = stringResource(R.string.groupchat_unarchive),
            )
        }
    }
}
