package io.zer0.muse.ui.screen

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.common.FileSizeFormatter
import io.zer0.common.Logger
import io.zer0.memory.ai.MemoryAutoSaveScheduler
import io.zer0.memory.compile.MemoryCompiler
import io.zer0.memory.compile.MemoryFileWriter
import io.zer0.memory.dream.MemoryMaintenanceLock
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.pin.PinnedMemoryStore
import io.zer0.memory.reflection.MemoryContradictionStore
import io.zer0.memory.summary.MemoryDb
import io.zer0.memory.summary.SessionSummaryManager
import io.zer0.muse.R
import io.zer0.muse.data.agentbot.AgentDreamArchiveStore
import io.zer0.muse.data.chat.ConversationTreeSnapshotStore
import io.zer0.muse.data.groupchat.GroupChatMemoryRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.ui.chat.SessionArtifactCleanup
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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
    // P0-10: 注入分库提供者与 daily 文件写入器,重置记忆需级联清理子助手分库与 daily/*.md
    factDbProvider: FactDbProvider = koinInject(),
    memoryFileWriter: MemoryFileWriter = koinInject(),
    // 重置记忆还要清掉不属于事实表的残留:置顶、Dream 状态/快照/归档、自动保存指纹。
    pinnedMemoryStore: PinnedMemoryStore = koinInject(),
    memoryDb: MemoryDb = koinInject(),
    autoSaveScheduler: MemoryAutoSaveScheduler = koinInject(),
    // 重置还要覆盖上下文检查点、群聊记忆与历史矛盾清单：它们都不在事实表里，
    // 不清的话重置后仍会接回“上次聊到哪”、群聊摘要卡片与旧的矛盾清单。
    groupChatMemoryRepository: GroupChatMemoryRepository = koinInject(),
    contradictionStore: MemoryContradictionStore = koinInject(),
) {
    val context = LocalContext.current
    val clearChatsDone = stringResource(R.string.data_management_clear_chats_done)
    val clearCacheDone = stringResource(R.string.data_management_clear_cache_done)
    val resetMemoryDone = stringResource(R.string.data_management_reset_memory_done)
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

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.data_management_title),
                onBack = onBack,
                // CMP-10: 子页统一大标题规则(与 SettingsSubPageScaffold 系一致)
                largeTitle = true,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                MuseSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MuseShapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.data_management_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
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
                MuseCapsuleButton(
                    text = stringResource(R.string.data_management_clear_chats),
                    onClick = { showClearChatsDialog = true },
                    leadingIcon = MuseIcons.messages,
                    destructive = true,
                )
            }
            item {
                MuseCapsuleButton(
                    text = stringResource(R.string.data_management_clear_cache),
                    onClick = { showClearCacheDialog = true },
                    leadingIcon = MuseIcons.wand,
                )
            }
            item {
                MuseCapsuleButton(
                    text = stringResource(R.string.data_management_reset_memory),
                    onClick = { showResetMemoryDialog = true },
                    leadingIcon = MuseIcons.brain,
                    destructive = true,
                )
            }
        }
    }

    if (showClearChatsDialog) {
        MuseDialog(
            onDismissRequest = { showClearChatsDialog = false },
            title = stringResource(R.string.data_management_clear_chats),
            content = { Text(stringResource(R.string.data_management_clear_chats_confirm)) },
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                showClearChatsDialog = false
                scope.launch {
                    val deletedIds = withContext(Dispatchers.IO) { sessionRepository.hardDeleteAllSessions() }
                    // 会话级残留要跟着清：对话树快照、附件索引、待办（内存与库）、工具装载。
                    // 以前批量路径只做到 DB 层，单条删除做了七八件、这里一件都没做。
                    val snapshotStore = runCatching {
                        org.koin.core.context.GlobalContext.get().get<ConversationTreeSnapshotStore>()
                    }.getOrNull()
                    SessionArtifactCleanup.purge(
                        sessionIds = deletedIds,
                        snapshotStore = snapshotStore,
                        reason = "清除全部聊天",
                    )
                    MuseToast.show(clearChatsDone)
                    sessionCount = 0
                    messageCount = 0
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
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                showClearCacheDialog = false
                scope.launch {
                    withContext(Dispatchers.IO) {
                        context.cacheDir.listFiles()?.forEach { it.delete() }
                    }
                    MuseToast.show(clearCacheDone)
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
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                showResetMemoryDialog = false
                scope.launch {
                    withContext(Dispatchers.IO) {
                        // 与 Dream 发布/回滚、助手删除清理共享同一把维护锁：
                        // 否则重置删到一半时一次 Dream 回滚会把旧段落写回来。
                        MemoryMaintenanceLock.mutex.withLock {
                            // P0-10: 重置级联清理 — 默认库 + 所有子助手分库(事实/FTS/墓碑/图谱边)
                            // + daily/*.md + 会话摘要 + 编译段。此前只清默认库与部分文件,
                            // 旧日记经 assembleWeekFromDaily 从 daily/*.md 复活,子助手分库完全未清理。
                            factStore.clearAllWithLinks()
                            // 只遍历磁盘上真实存在的分库：按 filesDir 里的文件名猜 id 会把 -wal/-shm
                            // 和别的 db 一起误认成助手 id，还会为已删助手凭空建库。
                            factDbProvider.listAssistantDbIds().forEach { assistantId ->
                                runCatching { factDbProvider.getFactStore(assistantId).clearAllWithLinks() }
                                    .onFailure { error ->
                                        Logger.w("DataManagementScreen", "清理子助手记忆分库失败: $assistantId", error)
                                    }
                            }
                            memoryFileWriter.clearAllDailyFiles()
                            summaryManager.clearAll()
                            memoryCompiler.clearAll()
                            // 升级前的单空间编译表也要清空：scoped 行被清成空串后读取会优先看它，
                            // 但旧表里的内容仍会在 scoped 行缺失时被兜底读回。
                            memoryDb.compiledSectionDao().clearAll(java.time.Instant.now().toString())
                            // 上下文检查点是“下次接着聊”的隐性记忆，群聊记忆与矛盾清单也各自落盘/落表：
                            // 只清事实与段落，它们在重置后依旧会把旧内容带回界面。
                            runCatching { memoryDb.contextCheckpointDao().deleteAll() }
                                .onFailure { error -> Logger.w("DataManagementScreen", "清理上下文检查点失败", error) }
                            runCatching { groupChatMemoryRepository.deleteAll() }
                                .onFailure { error -> Logger.w("DataManagementScreen", "清理群聊记忆失败", error) }
                            runCatching { contradictionStore.clearAll() }
                                .onFailure { error -> Logger.w("DataManagementScreen", "清理矛盾清单失败", error) }
                            // 事实/段落之外的相关残留：置顶记忆、Dream 状态与 revision、归档 JSONL、
                            // 自动保存指纹。少了这些，重置后仍会显示置顶卡、可回滚到旧记忆、
                            // 或因为指纹命中而跳过对同一段历史的重新提取。
                            runCatching { pinnedMemoryStore.clearAll() }
                                .onFailure { error -> Logger.w("DataManagementScreen", "清理置顶记忆失败", error) }
                            runCatching {
                                memoryDb.memoryDreamDao().deleteAllStates()
                                memoryDb.memoryDreamDao().deleteAllRevisions()
                            }.onFailure { error -> Logger.w("DataManagementScreen", "清理 Dream 状态失败", error) }
                            runCatching {
                                AgentDreamArchiveStore(
                                    File(context.filesDir, AgentDreamArchiveStore.DIRECTORY),
                                ).clear()
                            }.onFailure { error -> Logger.w("DataManagementScreen", "清理夜间归档失败", error) }
                            runCatching { autoSaveScheduler.invalidateFingerprintCache() }
                        }
                    }
                    MuseToast.show(resetMemoryDone)
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
        // LAYOUT-01: 标签取剩余宽度（数值仍固定靠右），放大字号下不再逐字换行
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // LAYOUT-01: 数值靠右固定（SpaceBetween），只限行、不取宽
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatFileSize(bytes: Long): String = FileSizeFormatter.format(bytes)

private fun getDirSize(dir: File): Long {
    var size = 0L
    dir.listFiles()?.forEach { file ->
        size += if (file.isDirectory) getDirSize(file) else file.length()
    }
    return size
}
