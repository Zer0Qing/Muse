package io.zer0.muse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import io.zer0.muse.ui.common.IosTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.ui.common.EmptyState
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecentlyDeletedScreen(
    onBack: () -> Unit,
    sessionRepository: SessionRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val deletedSessions by sessionRepository.observeDeletedSessions()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var pendingPermanentDeleteId by remember { mutableStateOf<String?>(null) }
    val dateFormat = remember { SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, Locale.getDefault()) }

    // 自动清理旧的已删除会话
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { sessionRepository.purgeOldDeletedSessions() }
    }

    Scaffold(
        topBar = {
            IosTopBar(
                title = stringResource(R.string.recently_deleted_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        if (deletedSessions.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EmptyState(
                    icon = Icons.Default.Delete,
                    title = stringResource(R.string.recently_deleted_empty),
                    subtitle = stringResource(R.string.recently_deleted_auto_clear),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(deletedSessions, key = { it.id }) { session ->
                    DeletedSessionCard(
                        session = session,
                        dateFormat = dateFormat,
                        onRestore = {
                            scope.launch {
                                withContext(Dispatchers.IO) { sessionRepository.restoreSession(session.id) }
                                MuseToast.show(context.getString(R.string.recently_deleted_restore_done))
                            }
                        },
                        onPermanentDelete = { pendingPermanentDeleteId = session.id },
                    )
                }
            }
        }
    }

    pendingPermanentDeleteId?.let { id ->
        MuseDialog(
            onDismissRequest = { pendingPermanentDeleteId = null },
            title = stringResource(R.string.recently_deleted_permanent_delete),
            content = { Text(stringResource(R.string.recently_deleted_permanent_delete_confirm)) },
            confirmText = stringResource(R.string.action_delete),
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) { sessionRepository.deleteSession(id) }
                    MuseToast.show(context.getString(R.string.recently_deleted_permanent_delete_done))
                }
                pendingPermanentDeleteId = null
            },
            destructive = true,
        )
    }
}

@Composable
private fun DeletedSessionCard(
    session: SessionEntity,
    dateFormat: SimpleDateFormat,
    onRestore: () -> Unit,
    onPermanentDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MuseShapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title.ifBlank { stringResource(R.string.chat_new_session) },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (session.lastMessagePreview.isNotBlank()) {
                    Text(
                        text = session.lastMessagePreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.chat_list_deleted_toast) + " · " +
                        (session.deletedAt?.let { dateFormat.format(Date(it)) } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            IconButton(onClick = onRestore) {
                Icon(
                    Icons.Default.Unarchive,
                    contentDescription = stringResource(R.string.recently_deleted_restore),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onPermanentDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.recently_deleted_permanent_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
