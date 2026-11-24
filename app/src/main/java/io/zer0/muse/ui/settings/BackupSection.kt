package io.zer0.muse.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.backup.BackupService
import io.zer0.muse.backup.BackupImportMode
import io.zer0.muse.backup.CloudBackupConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.export.ConversationExporter
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.StatusDot
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.common.surface.actionItem
import io.zer0.muse.ui.common.surface.switchItem
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale
import io.zer0.muse.ui.chat.ChatListScreen

/**
 * 阶段 7 + P1 修复: 备份与统计 section — 分组列表。
 *
 * 三个 [SettingsGroup]:
 *  - 使用统计:会话总数 + 消息总数
 *  - 本地备份与恢复:导出 + 导入(SAF launcher)
 *  - 云备份(P1 新增):类型选择 + S3/WebDAV 配置 + 自动同步开关 + 立即同步 + 上次同步时间
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // 屏幕级设置分组: 使用统计/本地备份/云备份/备份记录四个 SettingsGroup 与多个对话框为固有长结构(项目惯例,见 ChatListScreen)
@Composable
internal fun BackupSection(
    sessionCount: Int,
    messageCount: Int,
    backupService: BackupService,
    settings: SettingsRepository,
    sessionRepository: SessionRepository,
    /** F-04: 备份记录持久化(诊断页可见最近备份结果)。 */
    autoBackupLogDao: io.zer0.muse.data.stats.AutoBackupLogDao,
    onOpenCloudBackup: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val cloudConfig by settings.cloudBackupConfigFlow.collectAsStateWithLifecycle(
        initialValue = CloudBackupConfig(),
    )
    val exportableSessions by sessionRepository.observeAllSessions()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var sessionExportQuery by rememberSaveable { mutableStateOf("") }
    val filteredExportSessions =
        exportableSessions.filter { session ->
            sessionExportQuery.isBlank() ||
                session.title.contains(sessionExportQuery.trim(), ignoreCase = true)
        }
    // P3-4: 云备份配置与自动同步间隔编辑收敛到独立「云备份」页(CloudBackupPage)。
    // v2.x: 首页整组收敛为单行入口(状态展示 + 导航),开关与快捷操作统一在云备份页。
    val showGoToPageHint: () -> Unit = onOpenCloudBackup

    // 进度反馈状态
    var exporting by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var localBackupDialogVisible by remember { mutableStateOf(false) }
    var showSessionExportDialog by remember { mutableStateOf(false) }
    var pendingSessionExport by remember { mutableStateOf<SessionEntity?>(null) }
    // F-27: 自动备份恢复
    var autoRestoreTarget by remember { mutableStateOf<io.zer0.muse.data.stats.AutoBackupLogEntity?>(null) }
    var autoRestoring by remember { mutableStateOf(false) }

    // 云端备份状态
    var hasCloudBackup by remember { mutableStateOf(false) }
    var checkingCloudBackup by remember { mutableStateOf(false) }
    // v1.48: h17 区分"检查失败"与"无备份",不再静默吞错误
    var cloudCheckError by remember { mutableStateOf(false) }

    // 配置变化后重新检查云端备份状态
    LaunchedEffect(cloudConfig.type, cloudConfig.isConfigured) {
        if (cloudConfig.isConfigured) {
            checkingCloudBackup = true
            cloudCheckError = false
            // v1.48: h17 用 onSuccess/onFailure 区分错误态,失败不再伪装成"无备份"
            resultOf { backupService.hasCloudBackup() }
                .onSuccess { hasCloudBackup = it }
                .onError { _, _ -> cloudCheckError = true }
            checkingCloudBackup = false
        } else {
            hasCloudBackup = false
            cloudCheckError = false
        }
    }

    // 导出前明确处理未设置加密密码的情况,避免用户无感知地产生明文备份。
    var pendingPlainExportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    // v2.6.0: 待执行的导出是否包含密钥（SAF 回调前记录用户勾选）
    var pendingExportSecrets by remember { mutableStateOf(false) }

    fun startExport(uri: android.net.Uri, includeSecrets: Boolean = false) {
        scope.launch {
            exporting = true
            localBackupDialogVisible = true
            resultOf {
                val (s, m) = backupService.exportStreaming(context, uri, includeSecrets)
                MuseToast.show(context.getString(R.string.settings_backup_export_success, s, m))
            }.onError { _, t ->
                MuseToast.show(context.getString(R.string.settings_backup_export_failed, t?.message), 3500)
            }
            exporting = false
            localBackupDialogVisible = false
        }
    }

    fun startSessionExport(session: SessionEntity, uri: android.net.Uri) {
        scope.launch {
            exporting = true
            localBackupDialogVisible = true
            resultOf {
                val messages = sessionRepository.getAllMessagesForBackfill(session.id)
                val json =
                    ConversationExporter.exportToJson(
                        sessionId = session.id,
                        messages = messages,
                        chatTitle = io.zer0.muse.data.session.displaySessionTitle(
                            session.title,
                            context.getString(R.string.chat_new_session),
                        ),
                        locale = Locale.getDefault(),
                    )
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error(context.getString(R.string.backup_cannot_write, uri))
                }
                MuseToast.show(
                    context.getString(
                        R.string.settings_backup_export_session_success,
                        messages.size,
                    ),
                )
            }.onError { _, t ->
                MuseToast.show(
                    context.getString(R.string.settings_backup_export_session_failed, t?.message),
                    3500,
                )
            }
            exporting = false
            localBackupDialogVisible = false
        }
    }

    // 导出 launcher(SAF CreateDocument)
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let {
            if (cloudConfig.backupPassword.isBlank() && !cloudConfig.backupPasswordSet) {
                pendingPlainExportUri = it
            } else {
                startExport(it, pendingExportSecrets)
            }
        }
    }

    val sessionExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val session = pendingSessionExport
        pendingSessionExport = null
        if (uri != null && session != null) {
            startSessionExport(session, uri)
        }
    }

    // U-4: 待导入的本地备份 URI — 选中文件后先弹覆盖确认框,确认后才真正执行导入
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    // 导入 launcher(SAF OpenDocument)
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        // U-4: 覆盖式导入不直接执行,先记录所选文件触发确认框,由确认对话框 onConfirm 发起导入
        uri?.let { pendingImportUri = it }
    }

    // ── 使用统计 ──
    CardGroup(title = { Text(stringResource(R.string.settings_backup_usage_stats)) }) {
        item(
            key = "backup_session_count",
            leadingContent = {
                Icon(
                    imageVector = MuseIcons.messages,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            },
            headlineContent = { Text(stringResource(R.string.settings_backup_session_count)) },
            trailingContent = {
                Text(
                    text = "$sessionCount",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
        )
        item(
            key = "backup_message_count",
            leadingContent = {
                Icon(
                    imageVector = MuseIcons.chat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            },
            headlineContent = { Text(stringResource(R.string.settings_backup_message_count)) },
            trailingContent = {
                Text(
                    text = "$messageCount",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
        )
    }

    // ── 本地备份与恢复 ──
    var includeSecrets by rememberSaveable { mutableStateOf(false) }
    CardGroup(
        title = { Text(stringResource(R.string.settings_backup_local_title)) },
        modifier = Modifier.padding(top = MusePaddings.cardGap),
    ) {
        // v2.6.0: 包含供应商密钥（用户反馈：密钥多，希望备份能全量带走）。
        // 默认关 —— 开启后 providers_json（含 apiKey/OAuth token）明文进入备份文件。
        switchItem(
            key = "backup_include_secrets",
            title = stringResource(R.string.settings_backup_include_secrets),
            checked = includeSecrets,
            onCheckedChange = { includeSecrets = it },
            leadingContent = {
                Icon(
                    imageVector = MuseIcons.key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            },
        )
        actionItem(
            key = "backup_export",
            title = stringResource(R.string.settings_backup_export),
            onClick = {
                runCatching {
                    pendingExportSecrets = includeSecrets
                    exportLauncher.launch(
                        // 用可读日期而非 13 位毫秒：后者在选择器里会被截断，
                        // 既看不出导出时间，也看不到 .json 后缀，多份备份还同名。
                        "muse-backup-${
                            java.time.format
                                .DateTimeFormatter
                                .ofPattern("yyyyMMdd-HHmmss")
                                .format(java.time.LocalDateTime.now())
                        }.json",
                    )
                }.onFailure {
                    MuseToast.show(context.getString(R.string.settings_backup_export_start_failed, it.message))
                }
            },
        )
        actionItem(
            key = "backup_export_session",
            title = stringResource(R.string.settings_backup_export_session),
            onClick = { showSessionExportDialog = true },
        )
        actionItem(
            key = "backup_import",
            title = stringResource(R.string.settings_backup_import),
            onClick = {
                runCatching {
                    // 部分文件管理器会把 .json/.ndjson/.zip 标成 octet-stream 或未知类型;
                    // 备份服务本身按内容识别格式,选择器不能只过滤 application/json。
                    importLauncher.launch(
                        arrayOf(
                            "application/json",
                            "application/zip",
                            "application/octet-stream",
                            "text/plain",
                            "*/*",
                        ),
                    )
                }.onFailure {
                    MuseToast.show(context.getString(R.string.settings_backup_import_start_failed, it.message))
                }
            },
        )
    }

    if (showSessionExportDialog) {
        MuseDialog(
            onDismissRequest = { showSessionExportDialog = false },
            title = stringResource(R.string.settings_backup_export_session_title),
            content = {
                if (exportableSessions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_backup_export_session_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        MuseTextField(
                            value = sessionExportQuery,
                            onValueChange = { sessionExportQuery = it },
                            placeholder = {
                                Text(stringResource(R.string.settings_backup_export_session_search))
                            },
                            singleLine = true,
                        )
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                        ) {
                            items(filteredExportSessions, key = { it.id }) { session ->
                                // v2.5.2 fix: 与首页列表(CHAT-16)同口径 —— 历史导入/自动命名
                                // 可能把标题存成 "…"/"..." 等纯省略号,导出列表此前直接显示像坏数据。
                                val sessionDefaultTitle = stringResource(R.string.session_repo_default_title)
                                // 长标题仍可区分：导出列表比普通设置行多给一行。
                                val titleMaxLines = 2
                                MuseListItem(
                                    onClick = {
                                        showSessionExportDialog = false
                                        sessionExportQuery = ""
                                        pendingSessionExport = session
                                        runCatching {
                                            sessionExportLauncher.launch(
                                                "muse-session-${safeExportFileName(session.title)}.json",
                                            )
                                        }.onFailure {
                                            pendingSessionExport = null
                                            MuseToast.show(
                                                context.getString(
                                                    R.string.settings_backup_export_start_failed,
                                                    it.message,
                                                ),
                                            )
                                        }
                                    },
                                    leadingContent = {
                                        Icon(
                                            imageVector = MuseIcons.chat,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                                        )
                                    },
                                    headlineContent = {
                                        Text(
                                            text = io.zer0.muse.data.session.displaySessionTitle(
                                                session.title,
                                                sessionDefaultTitle,
                                            ),
                                            maxLines = titleMaxLines,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            stringResource(
                                                R.string.settings_backup_export_session_count,
                                                session.messageCount,
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            },
            onConfirm = null,
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showSessionExportDialog = false },
        )
    }

    // ── 云备份(P1 新增;v2.x: 收敛为单行入口 — 配置、开关与操作统一在独立云备份页)──
    CardGroup(
        title = { Text(stringResource(R.string.settings_backup_cloud_title)) },
        modifier = Modifier.padding(top = MusePaddings.cardGap),
    ) {
        // 云端备份状态(StatusDot + 文字);点击进入独立云备份页
        val statusColor = when {
            !cloudConfig.isConfigured -> MaterialTheme.colorScheme.outlineVariant
            checkingCloudBackup -> MaterialTheme.colorScheme.tertiary
            cloudCheckError -> MaterialTheme.colorScheme.error
            hasCloudBackup -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outlineVariant
        }
        val statusText = when {
            !cloudConfig.isConfigured -> stringResource(R.string.settings_backup_status_unconfigured)
            checkingCloudBackup -> stringResource(R.string.settings_backup_status_checking)
            cloudCheckError -> stringResource(R.string.settings_backup_status_check_failed)
            hasCloudBackup -> stringResource(R.string.settings_backup_status_has_backup)
            else -> stringResource(R.string.settings_backup_status_no_backup)
        }
        item(
            key = "backup_cloud_entry",
            onClick = showGoToPageHint,
            leadingContent = {
                Icon(
                    imageVector = MuseIcons.cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            },
            headlineContent = { Text(stringResource(R.string.settings_backup_cloud_title)) },
            supportingContent = { Text(statusText) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.semantics { contentDescription = statusText },
                    ) {
                        StatusDot(color = statusColor, pulse = checkingCloudBackup)
                    }
                    Spacer(modifier = Modifier.width(MuseSpacing.sm))
                    ChevronRight()
                }
            },
        )
    }

    // ── F-04: 最近备份记录(诊断可见,来自 auto_backup_log 表)──
    CardGroup(title = { Text(stringResource(R.string.settings_backup_log_title)) }) {
        val logs by autoBackupLogDao.observeRecent(10)
            .collectAsStateWithLifecycle(initialValue = emptyList())
        if (logs.isEmpty()) {
            item(
                key = "backup_log_empty",
                leadingContent = {
                    Icon(
                        imageVector = MuseIcons.info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                },
                headlineContent = { Text(stringResource(R.string.settings_backup_log_empty)) },
            )
        } else {
            val logFmt = remember { MuseDateFormats.dateTimeShort() }
            logs.forEach { log ->
                val isSuccess = log.status == "success"
                item(
                    key = "backup_log_${log.id}",
                    onClick = if (isSuccess && !autoRestoring) {
                        { autoRestoreTarget = log }
                    } else {
                        null
                    },
                    leadingContent = {
                        Icon(
                            imageVector = if (isSuccess) MuseIcons.check else MuseIcons.x,
                            contentDescription = null,
                            tint = if (isSuccess) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                    headlineContent = {
                        Text(
                            logFmt.format(Date(log.createdAt)) + " · " +
                                stringResource(
                                    if (isSuccess) {
                                        R.string.settings_backup_log_success
                                    } else {
                                        R.string.settings_backup_log_failed
                                    },
                                ),
                        )
                    },
                    // 成功显示备份体量;失败显示错误摘要(errorMessage 为英文诊断串,
                    // 缺失时回退失败文案,便于用户理解而非显示空行)
                    supportingContent = {
                        Text(
                            if (isSuccess) {
                                // F-27: 成功行提示可点击恢复(含记忆/经验数据)
                                stringResource(
                                    R.string.settings_backup_log_restore_hint,
                                    formatBackupSize(log.fileSizeBytes),
                                )
                            } else {
                                log.errorMessage.takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.settings_backup_log_failed)
                            },
                        )
                    },
                )
            }
        }
    }

    // F-27: 从自动备份恢复的确认对话框
    autoRestoreTarget?.let { target ->
        MuseDialog(
            onDismissRequest = { if (!autoRestoring) autoRestoreTarget = null },
            title = stringResource(R.string.settings_backup_restore_auto_confirm_title),
            content = {
                Text(stringResource(R.string.settings_backup_restore_auto_confirm))
            },
            confirmText = stringResource(R.string.settings_backup_restore_auto_confirm_action),
            onConfirm = {
                autoRestoring = true
                localBackupDialogVisible = true
                scope.launch {
                    val ok = runCatching { backupService.restoreFromAutoBackup(target.backupPath) }
                        .onSuccess { (s, m) ->
                            MuseToast.show(context.getString(R.string.settings_backup_restore_auto_done, s, m))
                        }
                        .onFailure { e ->
                            Logger.w("BackupSection", "自动备份恢复失败: ${e.message}", e)
                            MuseToast.show(context.getString(R.string.settings_backup_restore_auto_failed))
                        }
                        .isSuccess
                    autoRestoring = false
                    localBackupDialogVisible = false
                    autoRestoreTarget = null
                    if (ok) {
                        MuseToast.show(context.getString(R.string.settings_backup_restore_auto_restart_hint))
                    }
                }
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { if (!autoRestoring) autoRestoreTarget = null },
        )
    }

    // 导出明文确认
    pendingPlainExportUri?.let { uri ->
        MuseDialog(
            onDismissRequest = { pendingPlainExportUri = null },
            title = stringResource(R.string.settings_backup_plaintext_export_title),
            content = { Text(stringResource(R.string.settings_backup_plaintext_export_message)) },
            confirmText = stringResource(R.string.settings_backup_plaintext_export_action),
            onConfirm = {
                pendingPlainExportUri = null
                // 明文确认只是多一步；「包含密钥」是上一步的选择，必须一起传下去，
                // 否则用户勾了密钥、确认明文后又悄悄变成不含密钥的备份。
                startExport(uri, pendingExportSecrets)
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { pendingPlainExportUri = null },
        )
    }

    // U-4: 本地导入确认 —— 先让用户选「叠加合并」还是「覆盖恢复」，再按所选语义执行。
    pendingImportUri?.let { uri ->
        // B-8: 导入加密备份时可手动输入备份密码(忘记/更换云备份密码后仍可解密旧备份)
        var importPassword by remember { mutableStateOf("") }
        var importMode by remember { mutableStateOf(BackupImportMode.OVERWRITE) }
        val isMerge = importMode == BackupImportMode.MERGE
        MuseDialog(
            onDismissRequest = { pendingImportUri = null },
            title = stringResource(R.string.settings_backup_import),
            content = {
                Column {
                    Text(stringResource(R.string.settings_backup_import_mode_title))
                    Spacer(Modifier.height(MusePaddings.contentGap))
                    // 两种语义后果不同，用单选把差异直接摆在选择项上，而不是只写在说明里。
                    CardGroup {
                        switchItem(
                            key = "import_mode_merge",
                            title = stringResource(R.string.settings_backup_import_mode_merge),
                            subtitle = stringResource(R.string.settings_backup_import_mode_merge_desc),
                            checked = isMerge,
                            onCheckedChange = { importMode = BackupImportMode.MERGE },
                        )
                        switchItem(
                            key = "import_mode_overwrite",
                            title = stringResource(R.string.settings_backup_import_mode_overwrite),
                            subtitle = stringResource(R.string.settings_backup_import_mode_overwrite_desc),
                            checked = !isMerge,
                            onCheckedChange = { importMode = BackupImportMode.OVERWRITE },
                        )
                    }
                    Spacer(Modifier.height(MusePaddings.contentGap))
                    Text(
                        if (isMerge) {
                            stringResource(R.string.settings_backup_merge_confirm)
                        } else {
                            stringResource(R.string.settings_backup_overwrite_confirm)
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    MuseTextField(
                        value = importPassword,
                        onValueChange = { importPassword = it },
                        placeholder = { Text(stringResource(R.string.settings_backup_import_password_hint)) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )
                }
            },
            confirmText = if (isMerge) {
                stringResource(R.string.settings_backup_merge_confirm_action)
            } else {
                stringResource(R.string.settings_backup_overwrite_confirm_action)
            },
            onConfirm = {
                val pwd = importPassword.trim().takeIf { it.isNotEmpty() }
                val chosenMode = importMode
                pendingImportUri = null
                importPassword = ""
                scope.launch {
                    importing = true
                    localBackupDialogVisible = true
                    resultOf {
                        val (s, m) = backupService.import(context, uri, pwd, chosenMode)
                        val messageRes = if (chosenMode == BackupImportMode.MERGE) {
                            R.string.settings_backup_merge_success
                        } else {
                            R.string.settings_backup_import_success
                        }
                        MuseToast.show(context.getString(messageRes, s, m))
                    }.onError { _, t ->
                        MuseToast.show(
                            context.getString(
                                R.string.settings_backup_import_failed,
                                friendlyImportError(
                                    raw = t?.message,
                                    context = context,
                                ),
                            ),
                            3500,
                        )
                    }
                    importing = false
                    localBackupDialogVisible = false
                }
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { pendingImportUri = null },
        )
    }

    // 导出/导入进行中:进度对话框
    if (localBackupDialogVisible) {
        MuseDialog(
            // 返回只关闭进度展示，导出/导入任务继续运行。
            onDismissRequest = { localBackupDialogVisible = false },
            title = if (exporting) {
                stringResource(
                    R.string.settings_backup_exporting,
                )
            } else {
                stringResource(R.string.settings_backup_importing)
            },
            content = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MuseSpinner()
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        if (exporting) {
                            stringResource(
                                R.string.settings_backup_exporting_data,
                            )
                        } else {
                            stringResource(R.string.settings_backup_importing_data)
                        },
                    )
                }
            },
            onConfirm = null,
            dismissText = null,
        )
    }

    // 云备份配置编辑已收敛到独立「云备份」页(CloudBackupPage),此处不再内嵌字段表单与快捷操作
}

/**
 * F-04: 备份体量展示(B/KB/MB,无 CJK 字面量)。
 */
private fun safeExportFileName(title: String): String = title
    .replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_")
    .trim()
    .take(48)
    .ifBlank { "session" }

private fun formatBackupSize(bytes: Long): String = when {
    // I18N-06: 数字格式跟随系统 Locale(原 Locale.US 固定)
    bytes >= 1_048_576L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024L -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * 把导入失败的内部错误翻译成用户能理解的一句话。
 *
 * 原始信息里有数据库表名（如 knowledgeDocs）、「声明 / 入库」这类内部术语，
 * 直接展示等于什么都没说。这里按常见成因给出可操作提示。
 */
private fun friendlyImportError(raw: String?, context: android.content.Context): String {
    if (raw.isNullOrBlank()) return context.getString(R.string.backup_import_error_unreadable)
    val lower = raw.lowercase()
    val rules = listOf(
        listOf("verify", "check") to context.getString(R.string.backup_import_error_mismatch),
        listOf("password", "decrypt") to context.getString(R.string.backup_import_error_password),
        listOf("version") to context.getString(R.string.backup_import_error_version),
        listOf("no such file", "not found") to context.getString(R.string.backup_import_error_missing),
        listOf("space") to context.getString(R.string.backup_import_error_space),
    )
    val hit = rules.firstOrNull { (keys, _) -> keys.any { it in lower } }
    return hit?.second ?: context.getString(R.string.backup_import_error_unknown)
}
