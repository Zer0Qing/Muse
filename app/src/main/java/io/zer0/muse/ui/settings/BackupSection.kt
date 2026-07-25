package io.zer0.muse.ui.settings

import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.theme.MusePaddings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.backup.BackupService
import io.zer0.muse.backup.CloudBackupConfig
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.SectionLabel
import io.zer0.muse.ui.common.SettingsGroup
import io.zer0.muse.ui.common.SettingsGroupDivider
import io.zer0.muse.ui.common.SettingsItemRow
import io.zer0.muse.ui.common.SettingsSwitchRow
import io.zer0.muse.ui.common.StatusDot
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseShapes
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
@Composable
internal fun BackupSection(
    sessionCount: Int,
    messageCount: Int,
    backupService: BackupService,
    settings: SettingsRepository,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val cloudConfig by settings.cloudBackupConfigFlow.collectAsStateWithLifecycle(
        initialValue = CloudBackupConfig()
    )
    var showCloudConfigDialog by remember { mutableStateOf(false) }
    // v1.98: 自动同步间隔设置对话框
    var showIntervalDialog by remember { mutableStateOf(false) }

    // 进度反馈状态
    var exporting by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    // v1.48: h16 云端上传/恢复加进度反馈 + 防重复点击
    var cloudUploading by remember { mutableStateOf(false) }
    var cloudRestoring by remember { mutableStateOf(false) }

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
            runCatching { backupService.hasCloudBackup() }
                .onSuccess { hasCloudBackup = it }
                .onFailure { cloudCheckError = true }
            checkingCloudBackup = false
        } else {
            hasCloudBackup = false
            cloudCheckError = false
        }
    }

    // 导出 launcher(SAF CreateDocument)
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let {
            scope.launch {
                exporting = true
                runCatching {
                    val (s, m) = backupService.exportStreaming(context, it)
                    MuseToast.show(context.getString(R.string.settings_backup_export_success, s, m))
                }.onFailure { t ->
                    MuseToast.show(context.getString(R.string.settings_backup_export_failed, t.message), 3500)
                }
                exporting = false
            }
        }
    }

    // 导入 launcher(SAF OpenDocument)
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            scope.launch {
                importing = true
                runCatching {
                    val (s, m) = backupService.import(context, it)
                    MuseToast.show(context.getString(R.string.settings_backup_import_success, s, m))
                }.onFailure { t ->
                    MuseToast.show(context.getString(R.string.settings_backup_import_failed, t.message), 3500)
                }
                importing = false
            }
        }
    }

    // ── 使用统计 ──
    SectionLabel(stringResource(R.string.settings_backup_usage_stats))
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        SettingsItemRow(
            icon = Icons.Outlined.Forum,
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
            icon = Icons.AutoMirrored.Outlined.Message,
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
            icon = Icons.Outlined.FileUpload,
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
            icon = Icons.Outlined.FileDownload,
            title = stringResource(R.string.settings_backup_import),
            subtitle = stringResource(R.string.settings_backup_import_subtitle),
            onClick = {
                runCatching {
                    importLauncher.launch(arrayOf("application/json"))
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
            icon = Icons.Outlined.Cloud,
            title = stringResource(R.string.settings_backup_cloud_status),
            subtitle = statusText,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(color = statusColor, pulse = checkingCloudBackup)
                    Spacer(modifier = Modifier.width(8.dp))
                }
            },
        )
        SettingsGroupDivider()
        SettingsItemRow(
            icon = Icons.Outlined.Cloud,
            title = stringResource(R.string.settings_backup_cloud_type),
            subtitle = typeLabel,
            onClick = { showCloudConfigDialog = true },
        )
        SettingsGroupDivider()
        // 自动同步开关
        SettingsSwitchRow(
            icon = Icons.Outlined.CloudUpload,
            title = stringResource(R.string.settings_backup_auto_sync),
            subtitle = stringResource(R.string.settings_backup_auto_sync_subtitle),
            checked = cloudConfig.autoSync,
            onCheckedChange = { enabled ->
                scope.launch {
                    settings.saveCloudBackupConfig(cloudConfig.copy(autoSync = enabled))
                }
            },
        )
        // v1.98: 自动同步间隔设置(仅 autoSync=true 时显示)
        if (cloudConfig.autoSync) {
            SettingsGroupDivider()
            SettingsItemRow(
                icon = Icons.Outlined.Schedule,
                title = stringResource(R.string.settings_backup_auto_sync_interval),
                subtitle = "${cloudConfig.autoSyncIntervalHours} 小时",
                onClick = { showIntervalDialog = true },
            )
        }
        SettingsGroupDivider()
        // 立即上传
        SettingsItemRow(
            icon = Icons.Outlined.CloudUpload,
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
                scope.launch {
                    val ok = backupService.exportToCloud()
                    // 上传成功后刷新云端备份状态
                    if (ok) {
                        checkingCloudBackup = true
                        runCatching { backupService.hasCloudBackup() }
                            .onSuccess { hasCloudBackup = it }
                            .onFailure { cloudCheckError = true }
                        checkingCloudBackup = false
                    }
                    cloudUploading = false
                    MuseToast.show(if (ok) context.getString(R.string.settings_backup_uploaded) else context.getString(R.string.settings_backup_upload_failed))
                }
            },
        )
        SettingsGroupDivider()
        // 从云端恢复
        SettingsItemRow(
            icon = Icons.Outlined.CloudDownload,
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
                scope.launch {
                    val result = backupService.importFromCloud()
                    cloudRestoring = false
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
            icon = Icons.Outlined.Cloud,
            title = stringResource(R.string.settings_backup_last_sync),
            subtitle = if (cloudConfig.lastSyncAt > 0) {
                syncFmt.format(Date(cloudConfig.lastSyncAt))
            } else {
                stringResource(R.string.settings_backup_never_synced)
            },
        )
    }

    // 导出/导入进行中:进度对话框
    if (exporting || importing) {
        MuseDialog(
            onDismissRequest = { /* 不可中断 */ },
            title = if (exporting) stringResource(R.string.settings_backup_exporting) else stringResource(R.string.settings_backup_importing),
            content = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(if (exporting) stringResource(R.string.settings_backup_exporting_data) else stringResource(R.string.settings_backup_importing_data))
                }
            },
            onConfirm = null,
            dismissText = null,
        )
    }

    // v1.48: h16 云端上传/恢复进行中:进度对话框(不可点击外部关闭)
    if (cloudUploading || cloudRestoring) {
        MuseDialog(
            onDismissRequest = { /* 不可中断 */ },
            title = if (cloudUploading) stringResource(R.string.settings_backup_uploading) else stringResource(R.string.settings_backup_restoring),
            content = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(if (cloudUploading) stringResource(R.string.settings_backup_uploading_cloud) else stringResource(R.string.settings_backup_restoring_cloud))
                }
            },
            onConfirm = null,
            dismissText = null,
        )
    }

    // 云存储配置对话框
    if (showCloudConfigDialog) {
        CloudBackupConfigDialog(
            initial = cloudConfig,
            onDismiss = { showCloudConfigDialog = false },
            onSave = { newConfig -> settings.saveCloudBackupConfig(newConfig) },
        )
    }

    // v1.98: 自动同步间隔设置对话框
    if (showIntervalDialog) {
        var intervalInput by remember { mutableStateOf(cloudConfig.autoSyncIntervalHours.toString()) }
        MuseDialog(
            onDismissRequest = { showIntervalDialog = false },
            title = stringResource(R.string.settings_backup_auto_sync_interval),
            content = {
                OutlinedTextField(
                    value = intervalInput,
                    onValueChange = { intervalInput = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("间隔小时数 (1-168)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MuseShapes.medium,
                )
            },
            confirmText = stringResource(R.string.settings_common_save),
            onConfirm = {
                val hours = intervalInput.toIntOrNull()?.coerceIn(1, 168) ?: 24
                scope.launch {
                    settings.saveCloudBackupConfig(cloudConfig.copy(autoSyncIntervalHours = hours))
                }
                showIntervalDialog = false
            },
            onDismiss = { showIntervalDialog = false },
        )
    }
}

/**
 * 云备份配置对话框 — 支持 S3 / WebDAV 两种类型。
 */
@Composable
private fun CloudBackupConfigDialog(
    initial: CloudBackupConfig,
    onDismiss: () -> Unit,
    onSave: suspend (CloudBackupConfig) -> Unit,
) {
    var type by remember { mutableStateOf(initial.type) }
    var s3Endpoint by remember { mutableStateOf(initial.s3Endpoint) }
    var s3Region by remember { mutableStateOf(initial.s3Region) }
    var s3Bucket by remember { mutableStateOf(initial.s3Bucket) }
    var s3AccessKey by remember { mutableStateOf(initial.s3AccessKey) }
    var s3SecretKey by remember { mutableStateOf(initial.s3SecretKey) }
    var s3KeyPrefix by remember { mutableStateOf(initial.s3KeyPrefix) }
    var webdavUrl by remember { mutableStateOf(initial.webdavUrl) }
    var webdavUsername by remember { mutableStateOf(initial.webdavUsername) }
    var webdavPassword by remember { mutableStateOf(initial.webdavPassword) }
    var webdavPath by remember { mutableStateOf(initial.webdavPath) }
    var backupPassword by remember { mutableStateOf(initial.backupPassword) }
    var secretVisible by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_backup_config_title),
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // v1.74: 局部变量捕获避免 !!(委托属性无法 smart-cast)
                val err = saveError
                if (err != null) {
                    Surface(
                        shape = MuseShapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = stringResource(R.string.settings_backup_error_hint),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.settings_common_close),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { saveError = null },
                            )
                        }
                    }
                }
                // 类型选择
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemeModeOption(stringResource(R.string.settings_backup_type_none), type == "none") { type = "none" }
                    ThemeModeOption("S3", type == "s3") { type = "s3" }
                    ThemeModeOption("WebDAV", type == "webdav") { type = "webdav" }
                }

                if (type == "s3") {
                    OutlinedTextField(
                        value = s3Endpoint,
                        onValueChange = { s3Endpoint = it },
                        label = { Text(stringResource(R.string.settings_backup_endpoint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        value = s3Region,
                        onValueChange = { s3Region = it },
                        label = { Text(stringResource(R.string.settings_backup_region)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    OutlinedTextField(
                        value = s3Bucket,
                        onValueChange = { s3Bucket = it },
                        label = { Text("Bucket") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    OutlinedTextField(
                        value = s3AccessKey,
                        onValueChange = { s3AccessKey = it },
                        label = { Text(stringResource(R.string.settings_backup_access_key)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    OutlinedTextField(
                        value = s3SecretKey,
                        onValueChange = { s3SecretKey = it },
                        label = { Text("Secret Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        visualTransformation = if (secretVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { secretVisible = !secretVisible }) {
                                Text(if (secretVisible) stringResource(R.string.settings_common_hide) else stringResource(R.string.settings_common_show))
                            }
                        },
                    )
                    OutlinedTextField(
                        value = s3KeyPrefix,
                        onValueChange = { s3KeyPrefix = it },
                        label = { Text(stringResource(R.string.settings_backup_key_prefix)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                } else if (type == "webdav") {
                    OutlinedTextField(
                        value = webdavUrl,
                        onValueChange = { webdavUrl = it },
                        label = { Text(stringResource(R.string.settings_backup_webdav_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        value = webdavUsername,
                        onValueChange = { webdavUsername = it },
                        label = { Text(stringResource(R.string.settings_backup_username)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    OutlinedTextField(
                        value = webdavPassword,
                        onValueChange = { webdavPassword = it },
                        label = { Text(stringResource(R.string.settings_backup_password)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        visualTransformation = if (secretVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { secretVisible = !secretVisible }) {
                                Text(if (secretVisible) stringResource(R.string.settings_common_hide) else stringResource(R.string.settings_common_show))
                            }
                        },
                    )
                    OutlinedTextField(
                        value = webdavPath,
                        onValueChange = { webdavPath = it },
                        label = { Text(stringResource(R.string.settings_backup_remote_dir)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
            // v1.120: 备份加密密码(通用,S3/WebDAV 均适用)
            HorizontalDivider(modifier = Modifier.padding(vertical = MusePaddings.contentGap))
            Text(
                text = stringResource(R.string.settings_backup_encrypt_password),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = backupPassword,
                onValueChange = { backupPassword = it },
                label = { Text(stringResource(R.string.settings_backup_encrypt_password_hint)) },
                singleLine = true,
                visualTransformation = if (secretVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { secretVisible = !secretVisible }) {
                        Text(if (secretVisible) stringResource(R.string.settings_common_hide) else stringResource(R.string.settings_common_show))
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        },
        confirmText = stringResource(R.string.settings_common_save),
        onConfirm = {
            val newConfig = CloudBackupConfig(
                type = type,
                s3Endpoint = s3Endpoint.trim(),
                s3Region = s3Region.trim(),
                s3Bucket = s3Bucket.trim(),
                s3AccessKey = s3AccessKey.trim(),
                s3SecretKey = s3SecretKey.trim(),
                s3KeyPrefix = s3KeyPrefix.trim().ifBlank { "muse/" },
                webdavUrl = webdavUrl.trim(),
                webdavUsername = webdavUsername.trim(),
                webdavPassword = webdavPassword.trim(),
                webdavPath = webdavPath.trim().ifBlank { "/muse/" },
                autoSync = initial.autoSync,
                lastSyncAt = initial.lastSyncAt,
                backupPassword = backupPassword,
            )
            saveError = null
            scope.launch {
                runCatching { onSave(newConfig) }
                    .onSuccess { onDismiss() }
                    .onFailure { saveError = context.getString(R.string.settings_backup_save_failed, it.message) }
            }
        },
        dismissText = stringResource(R.string.settings_common_cancel),
        onDismiss = onDismiss,
    )
}
