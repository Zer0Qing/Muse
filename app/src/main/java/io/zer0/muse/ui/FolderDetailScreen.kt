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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import io.zer0.muse.R
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * 38/文件夹：独立详情页。点文件夹进来就是里面的对话（不再只是在列表里就地展开）。
 *
 * 无状态：数据与动作全部由外壳传入，便于用假数据直接渲染测（空态、有会话、添加面板）。
 * 提供三件实事——「新建到此文件夹」「添加已有对话」把散会话收进来、「移出」把某条放回去。
 */
@Composable
fun FolderDetailScreen(
    folderName: String,
    sessions: List<SessionEntity>,
    ungroupedSessions: List<SessionEntity>,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    onCreateInFolder: () -> Unit,
    onAddSession: (String) -> Unit,
    onRemoveSession: (String) -> Unit,
) {
    var showAddPicker by remember { mutableStateOf(false) }

    MusePageScaffold(
        topBar = { MuseTopBar(title = folderName, onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = MusePaddings.screen, vertical = MusePaddings.tightGap),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MuseCapsuleButton(
                    text = stringResource(R.string.folder_detail_new_here),
                    onClick = onCreateInFolder,
                    leadingIcon = MuseIcons.plus,
                    fillWidth = false,
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.folder_detail_add_existing),
                    onClick = { showAddPicker = true },
                    leadingIcon = MuseIcons.folder,
                    fillWidth = false,
                )
            }

            if (sessions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    MuseEmptyState(
                        icon = MuseIcons.folder,
                        title = stringResource(R.string.folder_detail_empty_title),
                        subtitle = stringResource(R.string.folder_detail_empty_sub),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = MusePaddings.screen,
                        vertical = MusePaddings.tightGap,
                    ),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    items(sessions, key = { it.id }) { session ->
                        FolderSessionRow(
                            session = session,
                            onOpen = { onOpenSession(session.id) },
                            onRemove = { onRemoveSession(session.id) },
                        )
                    }
                }
            }
        }
    }

    if (showAddPicker) {
        AddExistingDialog(
            candidates = ungroupedSessions,
            onPick = { id ->
                onAddSession(id)
                showAddPicker = false
            },
            onDismiss = { showAddPicker = false },
        )
    }
}

@Composable
private fun FolderSessionRow(session: SessionEntity, onOpen: () -> Unit, onRemove: () -> Unit) {
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
                    text = session.title.ifBlank { stringResource(R.string.artifact_untitled) },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatSessionTime(session.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MuseTactileButton(
                icon = MuseIcons.minus,
                onClick = onRemove,
                contentDescription = stringResource(R.string.folder_detail_remove),
            )
        }
    }
}

/** 「添加已有对话」：列出未分组会话，点一条即收进本文件夹。 */
@Composable
private fun AddExistingDialog(candidates: List<SessionEntity>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.folder_detail_add_existing),
        content = {
            if (candidates.isEmpty()) {
                Text(
                    text = stringResource(R.string.folder_detail_add_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    candidates.forEach { session ->
                        Text(
                            text = session.title.ifBlank { stringResource(R.string.artifact_untitled) },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(session.id) }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
        },
        confirmText = stringResource(R.string.artifact_close),
        onConfirm = onDismiss,
        dismissText = null,
    )
}

private fun formatSessionTime(timestamp: Long): String =
    io.zer0.muse.ui.theme.MuseDateFormats.dateTimeShort().format(java.util.Date(timestamp))
