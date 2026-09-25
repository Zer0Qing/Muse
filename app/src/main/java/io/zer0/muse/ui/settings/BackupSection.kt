package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseSpinner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.backup.BackupService
import io.zer0.muse.backup.CloudBackupConfig
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.common.settings.StatusDot
import io.zer0.muse.ui.theme.MuseDateFormats
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 阶段 7 + P1 修复: 备份与统计 section — iOS 风格分组列表。
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
    /** F-04: 备份记录持久化(诊断页可见最近备份结果)。 */
    autoBackupLogDao: io.zer0.muse.data.stats.AutoBackupLogDao,
    onOpenCloudBackup: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val cloudConfig by settings.cloudBackupConfigFlow.collectAsStateWithLifecycle(
        initialValue = CloudBackupConfig()
    )
    // P3-4: 云备份配置与自动同步间隔编辑收敛到独立「云备份」页(CloudBackupPage),
    // 设置首页不再内嵌字段表单 — 此前两套表单写同一 CloudBackupConfig,字段可互相覆盖。
    // 首页保留状态展示与上传/恢复/开关,点击配置入口时提示去专门页。
    // P3-4: 配置入口直接进入独立云备份页,不要只显示 Toast
    val showGoToPageHint: () -> Unit = onOpenCloudBackup

    // 进度反馈状态
    var exporting by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var localBackupDialogVisible by remember { mutableStateOf(false) }
    // F-27: 自动备份恢复
    var autoRestoreTarget by remember { mutableStateOf<io.zer0.muse.data.stats.AutoBackupLogEntity?>(null) }
    var autoRestoring by remember { mutableStateOf(false) }
    // v1.48: h16 云端上传/恢复加进度反馈 + 防重复点击
    var cloudUploading by remember { mutableStateOf(false) }
    var cloudRestoring by remember { mutableStateOf(false) }
    var cloudBackupDialogVisible by remember { mutableStateOf(false) }

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

    fun startExport(uri: android.net.Uri) {
        scope.launch {
            exporting = true
            localBackupDialogVisible = true
            resultOf {
                val (s, m) = backupService.exportStreaming(context, uri)
                MuseToast.show(context.getString(R.string.settings_backup_export_success, s, m))
            }.onError { _, t ->
                MuseToast.show(context.getString(R.string.settings_backup_export_failed, t?.message), 3500)
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
                startExport(it)
            }
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
    SectionLabel(stringResource(R.string.settings_backup_usage_stats))
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        SettingsItemRow(
            icon = MuseIcons.messages,
            title = stringResource(R.string.settings_backup_session_count),
        ) {
            Text(
                text = "$sessionCount",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        SettingsGroupDivider()
        SettingsItemRow(
            icon = MuseIcons.chat,
            title = stringResource(R.string.settings_backup_message_count),
        ) {
            Text(
                text = "$messageCount",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    // ── 本地备份与恢复 ──
    SectionLabel(stringResource(R.string.settings_backup_local_title))
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        SettingsItemRow(
            icon = MuseIcons.upload,
            title = stringResource(R.string.settings_backup_export),
            subtitle = stringResource(R.string.settings_backup_export_subtitle),
            onClick = {
                runCatching {
                    exportLauncher.launch("muse-backup-${System.currentTimeMillis()}.json")
                }.onFailure {
                    MuseToast.show(context.getString(R.string.settings_backup_export_start_failed, it.message))
                }
            },
        )
        SettingsGroupDivider()
        SettingsItemRow(
            icon = MuseIcons.download,
            title = stringResource(R.string.settings_backup_import),
            subtitle = stringResource(R.string.settings_backup_import_subtitle),
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

    // ── 云备份(P1 新增)──
    SectionLabel(stringResource(R.string.settings_backup_cloud_title))
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        val typeLabel = when (cloudConfig.type) {
            "s3" -> stringResource(R.string.settings_backup_type_s3)
            "webdav" -> "WebDAV"
            else -> stringResource(R.string.settings_backup_type_unconfigured)
        }
        // 云端备份状态(StatusDot + 文字)
        val statusColor = when {
            !cloudConfig.isConfigured -> MaterialTheme.colorScheme.outlineVariant
            checkingCloudBackup -> MaterialTheme.colorScheme.tertiary
            cloudCheckError -> MaterialTheme.colorScheme.error // v1.48: h17 检查失败用错误色
            hasCloudBackup -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outlineVariant
        }
        val statusText = when {
            !cloudConfig.isConfigured -> stringResource(R.string.settings_backup_status_unconfigured)
            checkingCloudBackup -> stringResource(R.string.settings_backup_status_checking)
            cloudCheckError -> stringResource(R.string.settings_backup_status_check_failed) // v1.48: h17 区分错误态
            hasCloudBackup -> stringResource(R.string.settings_backup_status_has_backup)
            else -> stringResource(R.string.settings_backup_status_no_backup)
        }
        SettingsItemRow(
            icon = MuseIcons.cloud,
            title = stringResource(R.string.settings_backup_cloud_status),
            subtitle = statusText,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // A11Y-05: 状态不只靠颜色传达 — 补语义描述
                    Box(
                        modifier = Modifier.semantics { contentDescription = statusText },
                    ) {
                        StatusDot(color = statusColor, pulse = checkingCloudBackup)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
            },
        )
        SettingsGroupDivider()
        SettingsItemRow(
            icon = MuseIcons.cloud,
            title = stringResource(R.string.settings_backup_cloud_type),
            subtitle = typeLabel,
            // P3-4: 配置入口收敛到独立云备份页
            onClick = showGoToPageHint,
        )
        SettingsGroupDivider()
        // 自动同步开关
        SettingsSwitchRow(
            icon = MuseIcons.cloudUpload,
            title = stringResource(R.string.settings_backup_auto_sync),
            subtitle = stringResource(R.string.settings_backup_auto_sync_subtitle),
            checked = cloudConfig.autoSync,
            onCheckedChange = { enabled ->
                scope.launch {
                    settings.saveCloudBackupConfig(cloudConfig.copy(autoSync = enabled))
                }
            },
        )
        // v1.98: 自动同步间隔设置(仅 autoSync=true 时显示;P3-4: 编辑收敛到云备份页,此处只读展示)
            if (cloudConfig.autoSync) {
                SettingsGroupDivider()
                SettingsItemRow(
                    icon = MuseIcons.calendarTime,
                    title = stringResource(R.string.settings_backup_auto_sync_interval),
                    subtitle = stringResource(R.string.settings_backup_interval_days, cloudConfig.autoSyncIntervalHours / 24),
                    onClick = showGoToPageHint,
                )
            }
        SettingsGroupDivider()
        // 立即上传
        SettingsItemRow(
            icon = MuseIcons.cloudUpload,
            title = stringResource(R.string.settings_backup_upload_now),
            subtitle = if (cloudConfig.isConfigured) stringResource(R.string.settings_backup_upload_subtitle_configured) else stringResource(R.string.settings_backup_upload_subtitle_unconfigured),
            onClick = {
                if (!cloudConfig.isConfigured) {
                    MuseToast.show(context.getString(R.string.settings_backup_configure_first))
                    return@SettingsItemRow
                }
                // v1.48: h16 操作中防重复点击
                if (cloudUploading || cloudRestoring) return@SettingsItemRow
                cloudUploading = true
                cloudBackupDialogVisible = true
                scope.launch {
                    val outcome = backupService.exportToCloud()
                    val ok = outcome == io.zer0.muse.backup.BackupService.CloudBackupOutcome.SUCCESS
                    // 上传成功后刷新云端备份状态
                    if (ok) {
                        checkingCloudBackup = true
                        resultOf { backupService.hasCloudBackup() }
                            .onSuccess { hasCloudBackup = it }
                            .onError { _, _ -> cloudCheckError = true }
                        checkingCloudBackup = false
                    }
                    cloudUploading = false
                    cloudBackupDialogVisible = false
                    // B-9: 备份密码失效(Keystore 丢失)时明确提示,而非笼统"上传失败"
                    val message = when (outcome) {
                        io.zer0.muse.backup.BackupService.CloudBackupOutcome.SUCCESS ->
                            context.getString(R.string.settings_backup_uploaded)
                        io.zer0.muse.backup.BackupService.CloudBackupOutcome.PASSWORD_UNAVAILABLE ->
                            context.getString(R.string.settings_backup_password_unavailable)
                        else -> context.getString(R.string.settings_backup_upload_failed)
                    }
                    MuseToast.show(message)
                }
            },
        )
        SettingsGroupDivider()
        // 从云端恢复
        SettingsItemRow(
            icon = MuseIcons.cloudDownload,
            title = stringResource(R.string.settings_backup_restore_from_cloud),
            subtitle = if (cloudConfig.isConfigured) stringResource(R.string.settings_backup_restore_subtitle_configured) else stringResource(R.string.settings_backup_upload_subtitle_unconfigured),
            onClick = {
                if (!cloudConfig.isConfigured) {
                    MuseToast.show(context.getString(R.string.settings_backup_configure_first))
                    return@SettingsItemRow
                }
                // v1.48: h16 操作中防重复点击
                if (cloudUploading || cloudRestoring) return@SettingsItemRow
                cloudRestoring = true
                cloudBackupDialogVisible = true
                scope.launch {
                    val result = backupService.importFromCloud()
                    cloudRestoring = false
                    cloudBackupDialogVisible = false
                    if (result == null) {
                        MuseToast.show(context.getString(R.string.settings_backup_restore_failed))
                    } else {
                        val (s, m) = result
                        MuseToast.show(context.getString(R.string.settings_backup_restored, s, m))
                    }
                }
            },
        )
        SettingsGroupDivider()
        // 上次同步时间(只读)
        // v1.71: 用 remember 缓存 SimpleDateFormat
        val syncFmt = remember { SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, Locale.getDefault()) }
        SettingsItemRow(
            icon = MuseIcons.cloud,
            title = stringResource(R.string.settings_backup_last_sync),
            subtitle = if (cloudConfig.lastSyncAt > 0) {
                syncFmt.format(Date(cloudConfig.lastSyncAt))
            } else {
                stringResource(R.string.settings_backup_never_synced)
            },
        )
    }

    // ── F-04: 最近备份记录(诊断可见,来自 auto_backup_log 表)──
    SectionLabel(stringResource(R.string.settings_backup_log_title))
    SettingsGroup {
        val logs by autoBackupLogDao.observeRecent(10)
            .collectAsStateWithLifecycle(initialValue = emptyList())
        if (logs.isEmpty()) {
            SettingsItemRow(
                icon = MuseIcons.info,
                title = stringResource(R.string.settings_backup_log_empty),
            )
        } else {
            val logFmt = remember { SimpleDateFormat(MuseDateFormats.DATE_TIME_SHORT, Locale.getDefault()) }
            logs.forEach { log ->
                val isSuccess = log.status == "success"
                SettingsItemRow(
                    icon = if (isSuccess) MuseIcons.check else MuseIcons.x,
                    title = logFmt.format(Date(log.createdAt)) + " · " +
                        stringResource(
                            if (isSuccess) R.string.settings_backup_log_success
                            else R.string.settings_backup_log_failed,
                        ),
                    // 成功显示备份体量;失败显示错误摘要(errorMessage 为英文诊断串,
                    // 缺失时回退失败文案,便于用户理解而非显示空行)
                    subtitle = if (isSuccess) {
                        // F-27: 成功行提示可点击恢复(含记忆/经验数据)
                        stringResource(R.string.settings_backup_log_restore_hint, formatBackupSize(log.fileSizeBytes))
                    } else {
                        log.errorMessage.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.settings_backup_log_failed)
                    },
                    // F-27: 成功的自动备份可一键恢复(含确认对话框 + 进度反馈)
                    onClick = if (isSuccess && !autoRestoring) {
                        { autoRestoreTarget = log }
                    } else {
                        null
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
            dismissText = stringResource(R.string.memory_screen_cancel),
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
                startExport(uri)
            },
            dismissText = stringResource(R.string.settings_common_cancel),
            onDismiss = { pendingPlainExportUri = null },
        )
    }

    // U-4: 本地导入覆盖确认(覆盖式操作,确认后才发起导入;恢复前自动生成保护副本)
    pendingImportUri?.let { uri ->
        // B-8: 导入加密备份时可手动输入备份密码(忘记/更换云备份密码后仍可解密旧备份)
        var importPassword by remember { mutableStateOf("") }
        MuseDialog(
            onDismissRequest = { pendingImportUri = null },
            title = stringResource(R.string.settings_backup_import),
            content = {
                Column {
                    Text(stringResource(R.string.settings_backup_overwrite_confirm))
                    Spacer(Modifier.height(12.dp))
                    MuseTextField(
                        value = importPassword,
                        onValueChange = { importPassword = it },
                        placeholder = { Text(stringResource(R.string.settings_backup_import_password_hint)) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )
                }
            },
            confirmText = stringResource(R.string.settings_backup_overwrite_confirm_action),
            onConfirm = {
                val pwd = importPassword.trim().takeIf { it.isNotEmpty() }
                pendingImportUri = null
                importPassword = ""
                scope.launch {
                    importing = true
                    localBackupDialogVisible = true
                    resultOf {
                        val (s, m) = backupService.import(context, uri, pwd)
                        MuseToast.show(context.getString(R.string.settings_backup_import_success, s, m))
                    }.onError { _, t ->
                        MuseToast.show(context.getString(R.string.settings_backup_import_failed, t?.message), 3500)
                    }
                    importing = false
                    localBackupDialogVisible = false
                }
            },
            dismissText = stringResource(R.string.settings_common_cancel),
            onDismiss = { pendingImportUri = null },
        )
    }

    // 导出/导入进行中:进度对话框
    if (localBackupDialogVisible) {
        MuseDialog(
            // 返回只关闭进度展示，导出/导入任务继续运行。
            onDismissRequest = { localBackupDialogVisible = false },
            title = if (exporting) stringResource(R.string.settings_backup_exporting) else stringResource(R.string.settings_backup_importing),
            content = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MuseSpinner()
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(if (exporting) stringResource(R.string.settings_backup_exporting_data) else stringResource(R.string.settings_backup_importing_data))
                }
            },
            onConfirm = null,
            dismissText = null,
        )
    }

    // v1.48: h16 云端上传/恢复进行中:进度对话框(不可点击外部关闭)
    if (cloudBackupDialogVisible) {
        MuseDialog(
            // 返回只关闭进度展示，云端任务继续运行。
            onDismissRequest = { cloudBackupDialogVisible = false },
            title = if (cloudUploading) stringResource(R.string.settings_backup_uploading) else stringResource(R.string.settings_backup_restoring),
            content = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MuseSpinner()
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(if (cloudUploading) stringResource(R.string.settings_backup_uploading_cloud) else stringResource(R.string.settings_backup_restoring_cloud))
                }
            },
            onConfirm = null,
            dismissText = null,
        )
    }

    // 云备份配置编辑已收敛到独立「云备份」页(CloudBackupPage),此处不再内嵌字段表单
}

/**
 * F-04: 备份体量展示(B/KB/MB,无 CJK 字面量)。
 */
private fun formatBackupSize(bytes: Long): String = when {
    // I18N-06: 数字格式跟随系统 Locale(原 Locale.US 固定)
    bytes >= 1_048_576L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024L -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
