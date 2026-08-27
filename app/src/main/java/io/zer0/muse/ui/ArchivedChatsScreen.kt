package io.zer0.muse.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.TablerIcons
import compose.icons.tablericons.Archive
import io.zer0.muse.R
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * 归档聊天列表页。
 *
 * 数据来自 ChatViewModel.archivedSessions；支持打开会话与取消归档。
 */
@Composable
fun ArchivedChatsScreen(
    sessions: List<SessionEntity>,
    onBack: () -> Unit,
    onUnarchive: (String) -> Unit,
    onOpenSession: (String) -> Unit,
) {
    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.chat_list_filter_archived),
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                MuseEmptyState(
                    icon = TablerIcons.Archive,
                    title = stringResource(R.string.chat_list_empty_archived),
                    subtitle = stringResource(R.string.chat_list_empty_archived_sub),
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
            items(sessions, key = { it.id }) { session ->
                ArchivedChatRow(
                    session = session,
                    onOpen = { onOpenSession(session.id) },
                    onUnarchive = { onUnarchive(session.id) },
                )
            }
        }
    }
}

@Composable
private fun ArchivedChatRow(
    session: SessionEntity,
    onOpen: () -> Unit,
    onUnarchive: () -> Unit,
) {
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
                    text = session.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatTime(session.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onUnarchive) {
                Icon(
                    imageVector = Icons.Outlined.Unarchive,
                    contentDescription = stringResource(R.string.chat_list_unarchive),
                )
            }
        }
    }
}

private fun formatTime(timestamp: Long): String =
    java.text.SimpleDateFormat(
        io.zer0.muse.ui.theme.MuseDateFormats.DATE_TIME_SHORT,
        java.util.Locale.getDefault(),
    ).format(java.util.Date(timestamp))

