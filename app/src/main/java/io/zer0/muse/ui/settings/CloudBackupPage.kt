package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.backup.BackupService
import io.zer0.muse.backup.CloudBackupConfig
import io.zer0.muse.backup.CloudBackupService
import io.zer0.muse.backup.RemoteBackup
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.IosDropdown
import io.zer0.muse.ui.common.IosSlider
import io.zer0.muse.ui.common.IosSwitch
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.common.SectionLabel
import io.zer0.muse.ui.common.SettingsGroup
import io.zer0.muse.ui.common.SettingsGroupDivider
import io.zer0.muse.ui.common.SettingsItemRow
import io.zer0.muse.ui.common.SettingsSwitchRow
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.132: 云备份独立设置页(参考 rikkahub/kelivo 风格)。
 *
 * 页面结构:
 *  1. 云存储配置分组 — 类型选择 + S3/WebDAV 字段表单 + 加密密码 + 保存按钮 + 测试连接
 *  2. 操作分组 — 立即备份 / 从最新备份恢复
 *  3. 远端备份列表 — 刷新 + 按版本恢复 + 删除
 *  4. 自动备份分组 — 开关 + 间隔天数 slider
 *
 * 设计:
 *  - 复用 [SettingsSubPageScaffold] 顶部栏 + 滚动容器
 *  - 配置表单用本地草稿,点击「保存配置」后才写入 DataStore(与 ProxySettingsPage 一致)
 *  - 网络操作走 Dispatchers.IO + 进度对话框,防重复点击
 *  - 密码/密钥输入框默认遮罩,点击「显示」切换
 */
@Composable
fun CloudBackupPage(
    onBack: () -> Unit,
) {
    val settings: SettingsRepository = koinInject()
    val backupService: BackupService = koinInject()
    val cloudBackupService: CloudBackupService = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val cloudConfig by settings.cloudBackupConfigFlow.collectAsStateWithLifecycle(
        initialValue = CloudBackupConfig()
    )

    // 本地草稿(初始化为当前 config,保存后同步)
    var draft by remember(cloudConfig) { mutableStateOf(cloudConfig) }
    var secretVisible by remember { mutableStateOf(false) }

    // 操作进度状态
    var testing by remember { mutableStateOf(false) }
    var backingUp by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }

    // 远端备份列表
    var remoteBackups by remember { mutableStateOf<List<RemoteBackup>>(emptyList()) }
    var listLoading by remember { mutableStateOf(false) }
    var listLoadFailed by remember { mutableStateOf(false) }

    // 自动备份间隔(本地草稿,滑动时即时更新 draft,保存配置时落盘)
    var intervalDaysDraft by remember(cloudConfig.autoSyncIntervalHours) {
        mutableStateOf(cloudConfig.autoSyncIntervalHours / 24f)
    }

    // 配置变化或保存后,自动刷新远端备份列表
    LaunchedEffect(cloudConfig.type, cloudConfig.isConfigured) {
        if (cloudConfig.isConfigured) {
            listLoading = true
            listLoadFailed = false
            val result = runCatching { cloudBackupService.listBackups(cloudConfig) }
            result.onSuccess { remoteBackups = it }
                .onFailure { listLoadFailed = true }
            listLoading = false
        } else {
            remoteBackups = emptyList()
            listLoadFailed = false
        }
    }

    SettingsSubPageScaffold(title = stringResource(R.string.cloud_backup_page_title), onBack = onBack) {
        // ── 顶部说明 ──
        item {
            Text(
                text = stringResource(R.string.cloud_backup_page_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = MusePaddings.tightGap),
            )
        }

        // ── 1. 云存储配置 ──
        item { SectionLabel(stringResource(R.string.cloud_backup_section_config)) }
        item {
            SettingsGroup {
                // 启用开关(切换 type 在 none / 之前类型之间)
                SettingsSwitchRow(
                    icon = Icons.Outlined.Cloud,
                    title = stringResource(R.string.cloud_backup_enable),
                    subtitle = if (draft.type != "none") {
                        when (draft.type) {
                            "s3" -> stringResource(R.string.cloud_backup_type_s3)
                            "webdav" -> stringResource(R.string.cloud_backup_type_webdav)
                            else -> stringResource(R.string.cloud_backup_type_none)
                        }
                    } else {
                        stringResource(R.string.cloud_backup_type_none)
                    },
                    checked = draft.type != "none",
                    onCheckedChange = { enabled ->
                        // 启用时回退到之前用过的类型(默认 webdav);禁用时置 none 但保留字段
                        draft = draft.copy(type = if (enabled) {
                            if (draft.s3Endpoint.isNotBlank() || draft.s3Bucket.isNotBlank()) "s3" else "webdav"
                        } else {
                            "none"
                        })
                    },
                )
                SettingsGroupDivider()
                // 类型选择(只在启用时显示)
                if (draft.type != "none") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                    ) {
                        IosDropdown(
                            value = draft.type,
                            onValueChange = { draft = draft.copy(type = it) },
                            label = stringResource(R.string.cloud_backup_type_label),
                            options = listOf(
                                "webdav" to stringResource(R.string.cloud_backup_type_webdav),
                                "s3" to stringResource(R.string.cloud_backup_type_s3),
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    SettingsGroupDivider()
                    // 按类型显示字段表单
                    if (draft.type == "webdav") {
                        WebDavFields(
                            draft = draft,
                            onDraftChange = { draft = it },
                            secretVisible = secretVisible,
                            onToggleSecret = { secretVisible = !secretVisible },
                        )
                    } else if (draft.type == "s3") {
                        S3Fields(
                            draft = draft,
                            onDraftChange = { draft = it },
                            secretVisible = secretVisible,
                            onToggleSecret = { secretVisible = !secretVisible },
                        )
                    }
                    SettingsGroupDivider()
                    // 加密密码(通用)
                    EncryptionPasswordField(
                        password = draft.backupPassword,
                        onPasswordChange = { draft = draft.copy(backupPassword = it) },
                        secretVisible = secretVisible,
                        onToggleSecret = { secretVisible = !secretVisible },
                    )
                }
            }
        }
        // 保存配置 + 测试连接
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MusePaddings.tightGap),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                // 测试连接
                TextButton(
                    onClick = {
                        if (testing) return@TextButton
                        if (!draft.isConfigured) {
                            MuseToast.show(context.getString(R.string.cloud_backup_test_failed))
                            return@TextButton
                        }
                        testing = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { cloudBackupService.testConnection(draft) }
                            testing = false
                            MuseToast.show(
                                context.getString(
                                    if (ok) R.string.cloud_backup_test_success else R.string.cloud_backup_test_failed
                                )
                            )
                        }
                    },
                    enabled = !testing && draft.isConfigured,
                    modifier = Modifier.weight(1f),
                ) {
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(MusePaddings.contentGap))
                    } else {
                        Icon(Icons.Outlined.Verified, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(MusePaddings.contentGap))
                    }
                    Text(stringResource(R.string.cloud_backup_test_connection))
                }
                // 保存配置
                TextButton(
                    onClick = {
                        scope.launch {
                            runCatching { settings.saveCloudBackupConfig(draft) }
                                .onSuccess {
                                    MuseToast.show(context.getString(R.string.cloud_backup_config_saved))
                                }
                                .onFailure {
                                    MuseToast.show(it.message ?: "Save failed", 2500)
                                }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Storage, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(MusePaddings.contentGap))
                    Text(stringResource(R.string.cloud_backup_save_config))
                }
            }
        }

        // ── 2. 操作分组 ──
        item { SectionLabel(stringResource(R.string.cloud_backup_section_actions)) }
        item {
            SettingsGroup {
                SettingsItemRow(
                    icon = Icons.Outlined.CloudUpload,
                    title = stringResource(R.string.cloud_backup_backup_now),
                    subtitle = if (cloudConfig.isConfigured) {
                        stringResource(R.string.settings_backup_upload_subtitle_configured)
                    } else {
                        stringResource(R.string.settings_backup_upload_subtitle_unconfigured)
                    },
                    onClick = {
                        if (!cloudConfig.isConfigured) {
                            MuseToast.show(context.getString(R.string.settings_backup_configure_first))
                            return@SettingsItemRow
                        }
                        if (backingUp || restoring) return@SettingsItemRow
                        backingUp = true
                        scope.launch {
                            val ok = backupService.exportToCloud()
                            backingUp = false
                            MuseToast.show(
                                context.getString(
                                    if (ok) R.string.cloud_backup_backup_success
                                    else R.string.cloud_backup_backup_failed
                                )
                            )
                            // 上传成功后刷新远端列表
                            if (ok) {
                                val list = runCatching { cloudBackupService.listBackups(cloudConfig) }.getOrDefault(emptyList())
                                remoteBackups = list
                            }
                        }
                    },
                )
                SettingsGroupDivider()
                SettingsItemRow(
                    icon = Icons.Outlined.CloudDownload,
                    title = stringResource(R.string.cloud_backup_restore_latest),
                    subtitle = if (cloudConfig.isConfigured) {
                        stringResource(R.string.settings_backup_restore_subtitle_configured)
                    } else {
                        stringResource(R.string.settings_backup_upload_subtitle_unconfigured)
                    },
                    onClick = {
                        if (!cloudConfig.isConfigured) {
                            MuseToast.show(context.getString(R.string.settings_backup_configure_first))
                            return@SettingsItemRow
                        }
                        if (backingUp || restoring) return@SettingsItemRow
                        restoring = true
                        scope.launch {
                            val result = backupService.importFromCloud()
                            restoring = false
                            if (result == null) {
                                MuseToast.show(context.getString(R.string.cloud_backup_restore_failed))
                            } else {
                                val (s, m) = result
                                MuseToast.show(context.getString(R.string.cloud_backup_restore_success, s, m))
                            }
                        }
                    },
                )
            }
        }

        // ── 3. 远端备份列表 ──
        item { SectionLabel(stringResource(R.string.cloud_backup_section_remote)) }
        item {
            SettingsGroup {
                // 刷新按钮(顶部独立行)
                SettingsItemRow(
                    icon = Icons.Outlined.Refresh,
                    title = stringResource(R.string.cloud_backup_refresh_list),
                    subtitle = if (listLoading) stringResource(R.string.cloud_backup_list_loading) else null,
                    onClick = {
                        if (!cloudConfig.isConfigured) {
                            MuseToast.show(context.getString(R.string.settings_backup_configure_first))
                            return@SettingsItemRow
                        }
                        if (listLoading) return@SettingsItemRow
                        listLoading = true
                        listLoadFailed = false
                        scope.launch {
                            val result = runCatching { cloudBackupService.listBackups(cloudConfig) }
                            result.onSuccess { remoteBackups = it }
                                .onFailure { listLoadFailed = true }
                            listLoading = false
                        }
                    },
                )
                SettingsGroupDivider()
                when {
                    !cloudConfig.isConfigured -> {
                        Text(
                            text = stringResource(R.string.settings_backup_status_unconfigured),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(MusePaddings.cardInner),
                        )
                    }
                    listLoading -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(MusePaddings.cardInner),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(MusePaddings.contentGap))
                            Text(
                                text = stringResource(R.string.cloud_backup_list_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    listLoadFailed -> {
                        Text(
                            text = stringResource(R.string.cloud_backup_list_load_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(MusePaddings.cardInner),
                        )
                    }
                    remoteBackups.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.cloud_backup_list_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(MusePaddings.cardInner),
                        )
                    }
                    else -> {
                        remoteBackups.forEachIndexed { idx, backup ->
                            if (idx > 0) SettingsGroupDivider()
                            RemoteBackupRow(
                                backup = backup,
                                onDelete = {
                                    if (deleting != null || restoring || backingUp) return@RemoteBackupRow
                                    deleting = backup.fileName
                                    scope.launch {
                                        val ok = cloudBackupService.deleteBackup(cloudConfig, backup.fileName)
                                        deleting = null
                                        MuseToast.show(
                                            context.getString(
                                                if (ok) R.string.cloud_backup_delete_success
                                                else R.string.cloud_backup_delete_failed
                                            )
                                        )
                                        if (ok) {
                                            remoteBackups = runCatching {
                                                cloudBackupService.listBackups(cloudConfig)
                                            }.getOrDefault(emptyList())
                                        }
                                    }
                                },
                                onRestore = {
                                    if (deleting != null || restoring || backingUp) return@RemoteBackupRow
                                    restoring = true
                                    scope.launch {
                                        val result = backupService.importFromCloudFile(backup.fileName)
                                        restoring = false
                                        if (result == null) {
                                            MuseToast.show(context.getString(R.string.cloud_backup_restore_failed))
                                        } else {
                                            val (s, m) = result
                                            MuseToast.show(context.getString(R.string.cloud_backup_restore_success, s, m))
                                        }
                                    }
                                },
                                isDeleting = deleting == backup.fileName,
                            )
                        }
                    }
                }
            }
        }

        // ── 4. 自动备份 ──
        item { SectionLabel(stringResource(R.string.cloud_backup_section_auto)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = Icons.Outlined.Schedule,
                    title = stringResource(R.string.cloud_backup_auto_enable),
                    subtitle = stringResource(R.string.cloud_backup_auto_subtitle),
                    checked = cloudConfig.autoSync,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            settings.saveCloudBackupConfig(cloudConfig.copy(autoSync = enabled))
                        }
                    },
                )
                if (cloudConfig.autoSync) {
                    SettingsGroupDivider()
                    // 间隔天数 slider(1-30 天)
                    val intervalDays = cloudConfig.autoSyncIntervalHours / 24f
                    val sliderValue = intervalDays.coerceIn(1f, 30f)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.cloud_backup_interval_label),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${sliderValue.toInt()} 天",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(MusePaddings.contentGap))
                        IosSlider(
                            value = sliderValue,
                            onValueChange = { intervalDaysDraft = it },
                            valueRange = 1f..30f,
                            steps = 28, // 1..30 共 30 个取值,steps = 30-2 = 28
                            valueFormatter = { "${it.toInt()} 天" },
                        )
                        Spacer(Modifier.height(MusePaddings.contentGap))
                        // v1.134 P2-3: 短间隔警示 — 间隔 ≤ 2 天时显示流量/电量提示
                        if (sliderValue.toInt() <= 2) {
                            Text(
                                text = stringResource(R.string.cloud_backup_interval_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(MusePaddings.contentGap))
                        }
                        // 拖动结束保存(简化:用 TextButton 显式保存)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = {
                                scope.launch {
                                    val hours = (intervalDaysDraft.toInt().coerceIn(1, 30)) * 24
                                    settings.saveCloudBackupConfig(cloudConfig.copy(autoSyncIntervalHours = hours))
                                    MuseToast.show(context.getString(R.string.cloud_backup_config_saved))
                                }
                            }) {
                                Text(stringResource(R.string.cloud_backup_save_config))
                            }
                        }
                    }
                }
            }
        }
    }

    // 操作进度对话框(测试连接不弹窗,只显示按钮内 spinner)
    if (backingUp || restoring) {
        MuseDialog(
            onDismissRequest = { /* 不可中断 */ },
            title = if (backingUp) stringResource(R.string.settings_backup_uploading) else stringResource(R.string.settings_backup_restoring),
            content = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        if (backingUp) stringResource(R.string.settings_backup_uploading_cloud)
                        else stringResource(R.string.settings_backup_restoring_cloud)
                    )
                }
            },
            onConfirm = null,
            dismissText = null,
        )
    }
}

/**
 * WebDAV 字段表单(URL / 用户名 / 密码 / 远程目录)。
 */
@Composable
private fun WebDavFields(
    draft: CloudBackupConfig,
    onDraftChange: (CloudBackupConfig) -> Unit,
    secretVisible: Boolean,
    onToggleSecret: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        OutlinedTextField(
            value = draft.webdavUrl,
            onValueChange = { onDraftChange(draft.copy(webdavUrl = it)) },
            label = { Text(stringResource(R.string.settings_backup_webdav_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MuseShapes.medium,
        )
        OutlinedTextField(
            value = draft.webdavUsername,
            onValueChange = { onDraftChange(draft.copy(webdavUsername = it)) },
            label = { Text(stringResource(R.string.settings_backup_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MuseShapes.medium,
        )
        OutlinedTextField(
            value = draft.webdavPassword,
            onValueChange = { onDraftChange(draft.copy(webdavPassword = it)) },
            label = { Text(stringResource(R.string.settings_backup_password)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MuseShapes.medium,
            visualTransformation = if (secretVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = onToggleSecret) {
                    Text(
                        if (secretVisible) stringResource(R.string.cloud_backup_hide_secret)
                        else stringResource(R.string.cloud_backup_show_secret)
                    )
                }
            },
        )
        OutlinedTextField(
            value = draft.webdavPath,
            onValueChange = { onDraftChange(draft.copy(webdavPath = it)) },
            label = { Text(stringResource(R.string.settings_backup_remote_dir)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MuseShapes.medium,
        )
    }
}

/**
 * S3 字段表单(Endpoint / Region / Bucket / AccessKey / SecretKey / KeyPrefix)。
 */
@Composable
private fun S3Fields(
    draft: CloudBackupConfig,
    onDraftChange: (CloudBackupConfig) -> Unit,
    secretVisible: Boolean,
    onToggleSecret: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        OutlinedTextField(
            value = draft.s3Endpoint,
            onValueChange = { onDraftChange(draft.copy(s3Endpoint = it)) },
            label = { Text(stringResource(R.string.settings_backup_endpoint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MuseShapes.medium,
        )
        OutlinedTextField(
            value = draft.s3Region,
            onValueChange = { onDraftChange(draft.copy(s3Region = it)) },
            label = { Text(stringResource(R.string.settings_backup_region)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MuseShapes.medium,
        )
        OutlinedTextField(
            value = draft.s3Bucket,
            onValueChange = { onDraftChange(draft.copy(s3Bucket = it)) },
            label = { Text(stringResource(R.string.settings_backup_bucket)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MuseShapes.medium,
        )
        OutlinedTextField(
            value = draft.s3AccessKey,
            onValueChange = { onDraftChange(draft.copy(s3AccessKey = it)) },
            label = { Text(stringResource(R.string.settings_backup_access_key)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MuseShapes.medium,
        )
        OutlinedTextField(
            value = draft.s3SecretKey,
            onValueChange = { onDraftChange(draft.copy(s3SecretKey = it)) },
            label = { Text(stringResource(R.string.settings_backup_secret_key)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MuseShapes.medium,
            visualTransformation = if (secretVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = onToggleSecret) {
                    Text(
                        if (secretVisible) stringResource(R.string.cloud_backup_hide_secret)
                        else stringResource(R.string.cloud_backup_show_secret)
                    )
                }
            },
        )
        OutlinedTextField(
            value = draft.s3KeyPrefix,
            onValueChange = { onDraftChange(draft.copy(s3KeyPrefix = it)) },
            label = { Text(stringResource(R.string.settings_backup_key_prefix)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MuseShapes.medium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        )
    }
}

/**
 * 加密密码字段(通用,S3/WebDAV 均用)。
 */
@Composable
private fun EncryptionPasswordField(
    password: String,
    onPasswordChange: (String) -> Unit,
    secretVisible: Boolean,
    onToggleSecret: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        Text(
            text = stringResource(R.string.settings_backup_encrypt_password),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.settings_backup_encrypt_password_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MuseShapes.medium,
            visualTransformation = if (secretVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = onToggleSecret) {
                    Text(
                        if (secretVisible) stringResource(R.string.cloud_backup_hide_secret)
                        else stringResource(R.string.cloud_backup_show_secret)
                    )
                }
            },
        )
    }
}

/**
 * 单条远端备份行 — 文件名 + 时间 + 大小 + 恢复 / 删除按钮。
 */
@Composable
private fun RemoteBackupRow(
    backup: RemoteBackup,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    isDeleting: Boolean,
) {
    val fmt = remember { SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, Locale.getDefault()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
    ) {
        Text(
            text = backup.fileName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(MusePaddings.tightGap))
        Text(
            text = buildString {
                if (backup.lastModified > 0) {
                    append(fmt.format(Date(backup.lastModified)))
                }
                if (backup.size > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(formatSize(backup.size))
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(MusePaddings.contentGap))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onRestore) {
                Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(MusePaddings.tightGap))
                Text(stringResource(R.string.cloud_backup_restore_this))
            }
            Spacer(Modifier.width(MusePaddings.contentGap))
            TextButton(onClick = onDelete, enabled = !isDeleting) {
                if (isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(MusePaddings.tightGap))
                Text(stringResource(R.string.cloud_backup_delete_this))
            }
        }
    }
}

/** 把字节数格式化为 KB/MB/GB。 */
private fun formatSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> String.format(Locale.US, "%.2f GB", gb)
        mb >= 1 -> String.format(Locale.US, "%.2f MB", mb)
        kb >= 1 -> String.format(Locale.US, "%.1f KB", kb)
        else -> "$bytes B"
    }
}
