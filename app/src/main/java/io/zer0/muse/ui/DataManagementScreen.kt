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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import io.zer0.muse.ui.common.IosTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.memory.fact.FactStore
import io.zer0.memory.summary.SessionSummaryManager
import io.zer0.memory.compile.MemoryCompiler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File

@Composable
fun DataManagementScreen(
    onBack: () -> Unit,
    sessionRepository: SessionRepository = koinInject(),
    factStore: FactStore = koinInject(),
    summaryManager: SessionSummaryManager = koinInject(),
    memoryCompiler: MemoryCompiler = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dbSizeText by remember { mutableStateOf("") }
    var cacheSizeText by remember { mutableStateOf("") }
    var sessionCount by remember { mutableIntStateOf(0) }
    var messageCount by remember { mutableIntStateOf(0) }
    var memoryCount by remember { mutableIntStateOf(0) }

    var showClearChatsDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showResetMemoryDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val dbFile = context.getDatabasePath("muse.db")
            val cacheDir = context.cacheDir
            dbSizeText = formatFileSize(dbFile?.length() ?: 0L)
            cacheSizeText = formatFileSize(getDirSize(cacheDir))
            sessionCount = sessionRepository.getSessionCount()
            messageCount = sessionRepository.getTotalMessageCount()
            memoryCount = factStore.getAll().size
        }
    }

    Scaffold(
        topBar = {
            IosTopBar(
                title = stringResource(R.string.data_management_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = MuseShapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.data_management_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(12.dp))
                        StatRow(stringResource(R.string.data_management_sessions), "$sessionCount")
                        StatRow(stringResource(R.string.data_management_messages), "$messageCount")
                        StatRow(stringResource(R.string.data_management_memories), "$memoryCount")
                        StatRow(stringResource(R.string.data_management_db_size), dbSizeText)
                        StatRow(stringResource(R.string.data_management_cache_size), cacheSizeText)
                    }
                }
            }
            item {
                Button(
                    onClick = { showClearChatsDialog = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = MuseShapes.medium,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Outlined.Forum, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.data_management_clear_chats))
                }
            }
            item {
                Button(
                    onClick = { showClearCacheDialog = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = MuseShapes.medium,
                ) {
                    Icon(Icons.Outlined.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.data_management_clear_cache))
                }
            }
            item {
                Button(
                    onClick = { showResetMemoryDialog = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = MuseShapes.medium,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Outlined.Psychology, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.data_management_reset_memory))
                }
            }
        }
    }

    if (showClearChatsDialog) {
        MuseDialog(
            onDismissRequest = { showClearChatsDialog = false },
            title = stringResource(R.string.data_management_clear_chats),
            content = { Text(stringResource(R.string.data_management_clear_chats_confirm)) },
            confirmText = stringResource(R.string.action_delete),
            onConfirm = {
                showClearChatsDialog = false
                scope.launch {
                    withContext(Dispatchers.IO) { sessionRepository.hardDeleteAllSessions() }
                    MuseToast.show(context.getString(R.string.data_management_clear_chats_done))
                    sessionCount = 0; messageCount = 0
                }
            },
            destructive = true,
        )
    }
    if (showClearCacheDialog) {
        MuseDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = stringResource(R.string.data_management_clear_cache),
            content = { Text(stringResource(R.string.data_management_clear_cache_confirm)) },
            confirmText = stringResource(R.string.action_delete),
            onConfirm = {
                showClearCacheDialog = false
                scope.launch {
                    withContext(Dispatchers.IO) {
                        context.cacheDir.listFiles()?.forEach { it.delete() }
                    }
                    MuseToast.show(context.getString(R.string.data_management_clear_cache_done))
                    cacheSizeText = "0 B"
                }
            },
            destructive = true,
        )
    }
    if (showResetMemoryDialog) {
        MuseDialog(
            onDismissRequest = { showResetMemoryDialog = false },
            title = stringResource(R.string.data_management_reset_memory),
            content = { Text(stringResource(R.string.data_management_reset_memory_confirm)) },
            confirmText = stringResource(R.string.action_delete),
            onConfirm = {
                showResetMemoryDialog = false
                scope.launch {
                    withContext(Dispatchers.IO) {
                        factStore.clearAll()
                        summaryManager.clearAll()
                        memoryCompiler.clearAll()
                    }
                    MuseToast.show(context.getString(R.string.data_management_reset_memory_done))
                    memoryCount = 0
                }
            },
            destructive = true,
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024
        unitIndex++
    }
    return "%.1f %s".format(size, units[unitIndex])
}

private fun getDirSize(dir: File): Long {
    var size = 0L
    dir.listFiles()?.forEach { file ->
        size += if (file.isDirectory) getDirSize(file) else file.length()
    }
    return size
}
