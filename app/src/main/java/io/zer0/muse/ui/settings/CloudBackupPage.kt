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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.FileSizeFormatter
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.backup.BackupService
import io.zer0.muse.backup.CloudBackupConfig
import io.zer0.muse.backup.CloudBackupService
import io.zer0.muse.backup.RemoteBackup
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseDropdown
import io.zer0.muse.ui.common.form.MuseSecretTextField
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.settings.SettingsSliderRow
import io.zer0.muse.ui.common.state.MuseErrorStateBox
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.actionItem
import io.zer0.muse.ui.common.surface.switchItem
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * v1.132: 云备份独立设置页。
 *
 * 页面结构(每个分组是一张 [CardGroup] 卡片,分组标题保留):
 *  1. 云存储配置 — 类型选择 + S3/WebDAV 字段表单 + 加密密码 + 测试连接
 *  2. 操作 — 立即备份 / 从最新备份恢复
 *  3. 远端备份列表 — 刷新 + 按版本恢复 + 删除
 *  4. 自动备份 — 开关 + 间隔天数 slider
 *
 * 设计:
 *  - 复用 [SettingsFormScaffold]:顶栏紧凑「保存」是唯一的落盘入口,草稿与持久值
 *    有差异时才点亮;返回键 / 手势返回在有未保存改动时先弹「放弃修改」确认。
 *  - 配置表单用本地草稿,点击顶栏「保存」后才写入 DataStore。
 *  - 自动备份开关与间隔属于即时生效项:操作完立刻落盘,不参与保存按钮的 dirty 判定,
 *    否则用户会以为要先点保存才会真的开始定时备份。
 *  - 网络操作走 Dispatchers.IO + 进度对话框,防重复点击。
 *  - 密码 / 密钥 / 口令输入统一用 [MuseSecretTextField](自带显隐切换)。
 */
@Composable
fun CloudBackupPage(includeSecrets: Boolean = false, onBack: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val backupService: BackupService = koinInject()
    val cloudBackupService: CloudBackupService = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val cloudConfig by settings.cloudBackupConfigFlow.collectAsStateWithLifecycle(
        initialValue = CloudBackupConfig(),
    )

    // 表单草稿:与"上次采纳的持久值"没有差异时跟随持久值刷新。
    // 无条件 remember(cloudConfig) 覆盖草稿会让后台写入(上传完成写 lastSyncAt、
    // 自动备份开关落盘)顺手把用户正在编辑的端点与密码回滚成已保存值;
    // 而完全不跟随,又会让页面首帧拿到的默认空配置永远盖不掉真实配置。
    var adopted by remember { mutableStateOf(cloudConfig) }
    var draft by remember { mutableStateOf(cloudConfig) }
    LaunchedEffect(cloudConfig) {
        if (!cloudBackupDraftDirty(draft, adopted)) draft = cloudConfig
        adopted = cloudConfig
    }
    // 用户是否主动编辑过备份密码字段 — 区分"显式清除密码"(清空后保存 → 复位
    // backupPasswordSet)与"未触碰字段"(Keystore 失效读回为空 → 保留标志,守卫不丢)
    var passwordEdited by remember { mutableStateOf(false) }

    // 自动备份间隔(天):滑块的显示值与拖动写入必须是同一份状态。
    // 只读持久值会让拇指在拖动时纹丝不动 —— 落盘发生在松手之后,期间重组又把
    // 显示值拉回旧值,于是滑块看起来"卡住",读数也与手指位置对不上。
    var intervalDays by remember(cloudConfig.autoSyncIntervalHours) {
        mutableStateOf(intervalDaysOf(cloudConfig))
    }

    // 操作进度状态
    var testing by remember { mutableStateOf(false) }
    var backingUp by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var cloudBackupDialogVisible by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }

    // U-4: 云端恢复覆盖确认开关(恢复最新 / 按版本,RESTORE 前先弹确认,确认后才执行)
    var confirmRestoreLatest by remember { mutableStateOf(false) }
    var confirmRestoreFile by remember { mutableStateOf<String?>(null) }
    var confirmDeleteFile by remember { mutableStateOf<String?>(null) }

    // v1.141 F1: 恢复失败专项 UX — 记录失败的操作类型与目标文件,弹出可重试的失败对话框
    var restoreFailKind by remember { mutableStateOf<RestoreFailKind?>(null) }
    var failedRestoreFile by remember { mutableStateOf<String?>(null) }

    // 远端备份列表
    var remoteBackups by remember { mutableStateOf<List<RemoteBackup>>(emptyList()) }
    var listLoading by remember { mutableStateOf(false) }
    var listLoadFailed by remember { mutableStateOf(false) }

    // 显式清空已设置的备份密码时,字段本身回到空串(与持久值相同),但 backupPasswordSet
    // 需要跟着复位 —— 这也是一次真实的待保存改动,否则用户清空后看不出还有东西没落盘。
    val clearingPassword = passwordEdited && draft.backupPassword.isEmpty() && cloudConfig.backupPasswordSet
    val draftDirty = clearingPassword || cloudBackupDraftDirty(draft, cloudConfig)

    // 配置变化或保存后,自动刷新远端备份列表
    LaunchedEffect(cloudConfig.type, cloudConfig.isConfigured) {
        if (cloudConfig.isConfigured) {
            listLoading = true
            listLoadFailed = false
            val result = resultOf { cloudBackupService.listBackups(cloudConfig) }
            result.onSuccess { remoteBackups = it }
                .onError { _, _ -> listLoadFailed = true }
            listLoading = false
        } else {
            remoteBackups = emptyList()
            listLoadFailed = false
        }
    }

    // 顶栏「保存」= 唯一落盘入口。密码标志按用户行为确定:
    // 密码非空 → 置 true;显式清空 → 置 false(允许撤销);未触碰 → 保留既有标志
    // (Keystore 失效读回为空时守卫不丢)。
    // 写的是整份配置,但自动备份开关、间隔与上次同步时间由即时操作/后台任务维护,
    // 草稿里这几项可能已经过期 —— 一律透传持久值,否则保存表单会顺手把它们改回去。
    fun persistDraft() {
        val withPasswordFlag = when {
            draft.backupPassword.isNotEmpty() -> draft.copy(backupPasswordSet = true)
            passwordEdited -> draft.copy(backupPasswordSet = false)
            else -> draft
        }
        val finalDraft = withPasswordFlag.copy(
            autoSync = cloudConfig.autoSync,
            autoSyncIntervalHours = cloudConfig.autoSyncIntervalHours,
            lastSyncAt = cloudConfig.lastSyncAt,
        )
        scope.launch {
            resultOf { settings.saveCloudBackupConfig(finalDraft) }
                .onSuccess {
                    // 本地草稿跟着落盘值收敛:否则 backupPasswordSet 的差异会让页面
                    // 一直显示"有未保存改动",保存按钮永远处于点亮态。
                    draft = finalDraft
                    passwordEdited = false
                    MuseToast.show(context.getString(R.string.cloud_backup_config_saved))
                }
                .onError { _, t ->
                    // 兜底文案不能是英文裸串:中文环境里"Save failed"既读不出原因也不合语境。
                    MuseToast.show(
                        t?.message ?: context.getString(R.string.cloud_backup_config_save_failed),
                        2500,
                    )
                }
        }
    }

    SettingsFormScaffold(
        title = stringResource(R.string.cloud_backup_page_title),
        dirty = draftDirty,
        onSave = { persistDraft() },
        onDismiss = onBack,
    ) {
        // ── 顶部说明 ──
        item(key = "cloud_backup_desc") {
            Text(
                text = stringResource(R.string.cloud_backup_page_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = MusePaddings.tightGap),
            )
        }

        // ── 1. 云存储配置 ──
        item(key = "cloud_backup_config_group") {
            CardGroup(title = { Text(stringResource(R.string.cloud_backup_section_config)) }) {
                // 启用开关(切换 type 在 none / 之前类型之间)
                switchItem(
                    key = "cloud_backup_enable",
                    title = stringResource(R.string.cloud_backup_enable),
                    subtitle = when (draft.type) {
                        "s3" -> stringResource(R.string.cloud_backup_type_s3)
                        "webdav" -> stringResource(R.string.cloud_backup_type_webdav)
                        else -> stringResource(R.string.cloud_backup_type_none)
                    },
                    checked = draft.type != "none",
                    onCheckedChange = { enabled ->
                        // 启用时回退到之前用过的类型(默认 webdav);禁用时置 none 但保留字段
                        draft = draft.copy(
                            type = if (enabled) {
                                if (draft.s3Endpoint.isNotBlank() || draft.s3Bucket.isNotBlank()) "s3" else "webdav"
                            } else {
                                "none"
                            },
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.cloud,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                // 类型选择(只在启用时显示)
                if (draft.type != "none") {
                    content(key = "cloud_backup_type") {
                        CloudTypePicker(
                            value = draft.type,
                            onValueChange = { draft = draft.copy(type = it) },
                        )
                    }
                    // 按类型显示字段表单。两种类型共用同一个 content 块:
                    // 各占一个条目时,切换类型会在卡片里留下一条多余的分隔线。
                    // 走到这里 type 必然是 webdav 或 s3(外层已排除 none)。
                    content(key = "cloud_backup_fields") {
                        if (draft.type == "webdav") {
                            WebDavFields(draft = draft, onDraftChange = { draft = it })
                        } else {
                            S3Fields(draft = draft, onDraftChange = { draft = it })
                        }
                    }
                    // 加密密码(通用)
                    content(key = "cloud_backup_encrypt_password") {
                        EncryptionPasswordField(
                            password = draft.backupPassword,
                            onPasswordChange = {
                                draft = draft.copy(backupPassword = it)
                                passwordEdited = true
                            },
                        )
                    }
                }
                // 测试连接:并入配置卡片,loading 期间忽略点击,避免并发探测同一份配置。
                actionItem(
                    key = "cloud_backup_test",
                    title = stringResource(R.string.cloud_backup_test_connection),
                    onClick = testConnection@{
                        // 配置不全时给出提示而不是静默无反应:禁用的行既点不动也不说明原因。
                        if (!draft.isConfigured) {
                            MuseToast.show(context.getString(R.string.cloud_backup_test_failed))
                            return@testConnection
                        }
                        testing = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { cloudBackupService.testConnection(draft) }
                            testing = false
                            MuseToast.show(
                                context.getString(
                                    if (ok) R.string.cloud_backup_test_success else R.string.cloud_backup_test_failed,
                                ),
                            )
                        }
                    },
                    enabled = !testing,
                    loading = testing,
                )
            }
        }

        // ── 2. 操作分组 ──
        item(key = "cloud_backup_actions_group") {
            CardGroup(title = { Text(stringResource(R.string.cloud_backup_section_actions)) }) {
                item(
                    key = "cloud_backup_now",
                    onClick = backupNow@{
                        if (!cloudConfig.isConfigured) {
                            MuseToast.show(context.getString(R.string.settings_backup_configure_first))
                            return@backupNow
                        }
                        if (backingUp || restoring) return@backupNow
                        backingUp = true
                        cloudBackupDialogVisible = true
                        scope.launch {
                            val outcome = backupService.exportToCloud(includeSecrets = includeSecrets)
                            val ok = outcome == io.zer0.muse.backup.BackupService.CloudBackupOutcome.SUCCESS
                            backingUp = false
                            cloudBackupDialogVisible = false
                            MuseToast.show(
                                context.getString(
                                    when {
                                        ok -> R.string.cloud_backup_backup_success
                                        outcome == io.zer0.muse.backup.BackupService.CloudBackupOutcome.PASSWORD_UNAVAILABLE ->
                                            if (includeSecrets && !cloudConfig.backupPasswordSet) {
                                                R.string.cloud_backup_provider_secrets_require_password
                                            } else {
                                                R.string.settings_backup_password_unavailable
                                            }
                                        else -> R.string.cloud_backup_backup_failed
                                    },
                                ),
                            )
                            // 上传成功后刷新远端列表
                            if (ok) {
                                val list = resultOf { cloudBackupService.listBackups(cloudConfig) }.getOrNull() ?: emptyList()
                                remoteBackups = list
                            }
                        }
                    },
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.cloudUpload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.cloud_backup_backup_now)) },
                    supportingContent = {
                        Text(
                            if (cloudConfig.isConfigured) {
                                stringResource(R.string.settings_backup_upload_subtitle_configured)
                            } else {
                                stringResource(R.string.settings_backup_upload_subtitle_unconfigured)
                            },
                        )
                        if (cloudConfig.isConfigured && includeSecrets) {
                            Text(stringResource(R.string.settings_backup_include_secrets))
                        }
                    },
                )
                item(
                    key = "cloud_backup_restore_latest",
                    onClick = restoreLatest@{
                        if (!cloudConfig.isConfigured) {
                            MuseToast.show(context.getString(R.string.settings_backup_configure_first))
                            return@restoreLatest
                        }
                        if (backingUp || restoring) return@restoreLatest
                        // U-4: 覆盖式恢复,先弹确认框,确认后再在 onConfirm 里执行实际恢复
                        confirmRestoreLatest = true
                    },
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.cloudDownload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.cloud_backup_restore_latest)) },
                    supportingContent = {
                        Text(
                            if (cloudConfig.isConfigured) {
                                stringResource(R.string.settings_backup_restore_subtitle_configured)
                            } else {
                                stringResource(R.string.settings_backup_upload_subtitle_unconfigured)
                            },
                        )
                    },
                )
            }
        }

        // ── 3. 远端备份列表 ──
        item(key = "cloud_backup_remote_group") {
            // 刷新中的副标题用显式类型声明:列表加载态是唯一需要副标题的时刻,
            // 显式标注可合成 lambda 类型,避免 `if` 分支的 lambda 被推断成普通函数类型。
            val refreshSubtitle: (@Composable () -> Unit)? = if (listLoading) {
                { Text(stringResource(R.string.cloud_backup_list_loading)) }
            } else {
                null
            }
            CardGroup(title = { Text(stringResource(R.string.cloud_backup_section_remote)) }) {
                // 刷新按钮(顶部独立行)
                item(
                    key = "cloud_backup_refresh",
                    onClick = refreshList@{
                        if (!cloudConfig.isConfigured) {
                            MuseToast.show(context.getString(R.string.settings_backup_configure_first))
                            return@refreshList
                        }
                        if (listLoading) return@refreshList
                        listLoading = true
                        listLoadFailed = false
                        scope.launch {
                            val result = resultOf { cloudBackupService.listBackups(cloudConfig) }
                            result.onSuccess { remoteBackups = it }
                                .onError { _, _ -> listLoadFailed = true }
                            listLoading = false
                        }
                    },
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.cloud_backup_refresh_list)) },
                    supportingContent = refreshSubtitle,
                )
                when {
                    !cloudConfig.isConfigured -> content(key = "cloud_backup_list_unconfigured") {
                        CloudListNotice(stringResource(R.string.settings_backup_status_unconfigured))
                    }
                    listLoading -> content(key = "cloud_backup_list_loading") {
                        CloudListLoading()
                    }
                    listLoadFailed -> content(key = "cloud_backup_list_failed") {
                        MuseErrorStateBox(
                            message = stringResource(R.string.cloud_backup_list_load_failed),
                            onRetry = {
                                scope.launch {
                                    listLoading = true
                                    listLoadFailed = false
                                    val result = resultOf { cloudBackupService.listBackups(cloudConfig) }
                                    result.onSuccess { remoteBackups = it }
                                        .onError { _, _ -> listLoadFailed = true }
                                    listLoading = false
                                }
                            },
                        )
                    }
                    remoteBackups.isEmpty() -> content(key = "cloud_backup_list_empty") {
                        CloudListNotice(stringResource(R.string.cloud_backup_list_empty))
                    }
                    else -> remoteBackups.forEach { backup ->
                        content(key = "cloud_backup_file_${backup.fileName}") {
                            RemoteBackupRow(
                                backup = backup,
                                // 上传 / 恢复 / 删除互斥:任一进行中都不接受另一项操作,
                                // 否则同一份归档会被并发改写或删除。
                                actionsEnabled = deleting == null && !restoring && !backingUp,
                                onDelete = { confirmDeleteFile = backup.fileName },
                                onRestore = { confirmRestoreFile = backup.fileName },
                                isDeleting = deleting == backup.fileName,
                            )
                        }
                    }
                }
            }
        }

        // ── 4. 自动备份 ──
        item(key = "cloud_backup_auto_group") {
            CardGroup(title = { Text(stringResource(R.string.cloud_backup_section_auto)) }) {
                switchItem(
                    key = "cloud_backup_auto_enable",
                    title = stringResource(R.string.cloud_backup_auto_enable),
                    checked = cloudConfig.autoSync,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            settings.saveCloudBackupConfig(cloudConfig.copy(autoSync = enabled))
                        }
                    },
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.calendarTime,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
                if (cloudConfig.autoSync) {
                    // 1..30 共 30 个取值,steps = 30-2 = 28。
                    // 单位走资源:硬编码"天"会让英文界面出现"3 天"这种半中半英的读数。
                    // 松手才落盘:拖动中每帧写 DataStore 会持续触发配置流重组,
                    // 拖动手感与滑块位置都会一起抖。
                    content(key = "auto_sync_interval") {
                        SettingsSliderRow(
                            icon = MuseIcons.clock,
                            title = stringResource(R.string.cloud_backup_interval_label),
                            value = intervalDays,
                            valueRange = IntervalDaysRange,
                            steps = INTERVAL_STEPS,
                            valueText = pluralStringResource(
                                R.plurals.cloud_backup_interval_days,
                                intervalDays.roundToInt(),
                                intervalDays.roundToInt(),
                            ),
                            onValueChange = { intervalDays = it },
                            onValueChangeFinished = {
                                val days = intervalDays.roundToInt().coerceIn(MIN_INTERVAL_DAYS, MAX_INTERVAL_DAYS)
                                val hours = days * DAY_HOURS
                                if (hours != cloudConfig.autoSyncIntervalHours) {
                                    scope.launch {
                                        settings.saveCloudBackupConfig(
                                            cloudConfig.copy(autoSyncIntervalHours = hours),
                                        )
                                    }
                                }
                            },
                        )
                    }
                    // v1.134 P2-3: 短间隔警示 — 间隔 ≤ 2 天时提示流量/电量代价(仅提示,不拦截)。
                    if (intervalDays.roundToInt() <= SHORT_INTERVAL_WARNING_DAYS) {
                        content(key = "auto_sync_interval_hint") {
                            Text(
                                text = stringResource(R.string.cloud_backup_interval_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(MusePaddings.cardInner),
                            )
                        }
                    }
                }
            }
        }
    }

    // 操作进度对话框(测试连接不弹窗,只显示按钮内 spinner)
    if (cloudBackupDialogVisible) {
        MuseDialog(
            // 返回只关闭进度展示，云端备份/恢复任务继续运行。
            onDismissRequest = { cloudBackupDialogVisible = false },
            title = if (backingUp) {
                stringResource(
                    R.string.settings_backup_uploading,
                )
            } else {
                stringResource(R.string.settings_backup_restoring)
            },
            content = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MuseSpinner()
                    Spacer(modifier = Modifier.width(MuseSpacing.lg))
                    // 取剩余宽度 + 居中 + 限行:大字号下文案不逐字换行,
                    // 其他语言的超长文案也会省略而不是把行撑出卡片。
                    Text(
                        text = if (backingUp) {
                            stringResource(R.string.settings_backup_uploading_cloud)
                        } else {
                            stringResource(R.string.settings_backup_restoring_cloud)
                        },
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            onConfirm = null,
            dismissText = null,
        )
    }

    val pendingDeleteFile = confirmDeleteFile
    if (pendingDeleteFile != null) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.common_delete),
            itemName = pendingDeleteFile,
            consequence = stringResource(R.string.cloud_backup_delete_consequence),
            onConfirm = {
                confirmDeleteFile = null
                if (deleting == null && !restoring && !backingUp) {
                    deleting = pendingDeleteFile
                    scope.launch {
                        val ok = cloudBackupService.deleteBackup(cloudConfig, pendingDeleteFile)
                        deleting = null
                        MuseToast.show(
                            context.getString(
                                if (ok) {
                                    R.string.cloud_backup_delete_success
                                } else {
                                    R.string.cloud_backup_delete_failed
                                },
                            ),
                        )
                        if (ok) {
                            remoteBackups = resultOf {
                                cloudBackupService.listBackups(cloudConfig)
                            }.getOrNull() ?: emptyList()
                        }
                    }
                }
            },
            onDismiss = { confirmDeleteFile = null },
        )
    }

    // U-4: 云端恢复覆盖确认 — 恢复最新 / 按版本,确认后才发起恢复(恢复前自动生成保护副本)
    val restoreFilePending = confirmRestoreFile
    if (restoreFilePending != null) {
        MuseDialog(
            onDismissRequest = { confirmRestoreFile = null },
            title = stringResource(R.string.cloud_backup_restore_this),
            content = { Text(stringResource(R.string.settings_backup_overwrite_confirm)) },
            confirmText = stringResource(R.string.settings_backup_overwrite_confirm_action),
            onConfirm = {
                val file = restoreFilePending
                confirmRestoreFile = null
                restoring = true
                cloudBackupDialogVisible = true
                scope.launch {
                    val result = backupService.importFromCloudFile(file)
                    restoring = false
                    cloudBackupDialogVisible = false
                    if (result == null) {
                        // v1.141 F1: 失败不再仅 Toast,弹专项对话框(可重试)
                        restoreFailKind = RestoreFailKind.FILE
                        failedRestoreFile = file
                    } else {
                        val (s, m) = result
                        MuseToast.show(context.getString(R.string.cloud_backup_restore_success, s, m))
                    }
                }
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { confirmRestoreFile = null },
        )
    } else if (confirmRestoreLatest) {
        MuseDialog(
            onDismissRequest = { confirmRestoreLatest = false },
            title = stringResource(R.string.cloud_backup_restore_latest),
            content = { Text(stringResource(R.string.settings_backup_overwrite_confirm)) },
            confirmText = stringResource(R.string.settings_backup_overwrite_confirm_action),
            onConfirm = {
                confirmRestoreLatest = false
                restoring = true
                cloudBackupDialogVisible = true
                scope.launch {
                    val result = backupService.importFromCloud()
                    restoring = false
                    cloudBackupDialogVisible = false
                    if (result == null) {
                        // v1.141 F1: 失败不再仅 Toast,弹专项对话框(可重试)
                        restoreFailKind = RestoreFailKind.LATEST
                    } else {
                        val (s, m) = result
                        MuseToast.show(context.getString(R.string.cloud_backup_restore_success, s, m))
                    }
                }
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { confirmRestoreLatest = false },
        )
    }

    // v1.141 F1: 恢复失败专项 UX — 错误对话框 + 重试入口(重试再失败会再次弹出)
    val failKind = restoreFailKind
    if (failKind != null) {
        MuseDialog(
            onDismissRequest = { restoreFailKind = null },
            title = stringResource(R.string.cloud_backup_restore_failed_title),
            content = {
                Text(
                    text = stringResource(R.string.cloud_backup_restore_failed_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.common_retry),
            onConfirm = {
                val kind = failKind
                val file = failedRestoreFile
                restoreFailKind = null
                failedRestoreFile = null
                scope.launch {
                    restoring = true
                    cloudBackupDialogVisible = true
                    val result: Pair<Int, Int>? = when (kind) {
                        RestoreFailKind.LATEST -> backupService.importFromCloud()
                        RestoreFailKind.FILE -> {
                            // 文件名不应为空(失败时已记录);防御性为空视为失败重新弹出
                            if (file == null) null else backupService.importFromCloudFile(file)
                        }
                    }
                    restoring = false
                    cloudBackupDialogVisible = false
                    if (result == null) {
                        restoreFailKind = kind
                        failedRestoreFile = file
                    } else {
                        val (s, m) = result
                        MuseToast.show(context.getString(R.string.cloud_backup_restore_success, s, m))
                    }
                }
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { restoreFailKind = null },
        )
    }
}

/**
 * 云端恢复失败类型 — 区分"恢复最新备份"与"按版本恢复",供失败对话框按原操作重试。
 */
private enum class RestoreFailKind { LATEST, FILE }

/** 自动备份间隔下限(天)。 */
private const val MIN_INTERVAL_DAYS = 1

/** 自动备份间隔上限(天)。 */
private const val MAX_INTERVAL_DAYS = 30

/** 一天的小时数 —— 滑块的"天"读数要落盘成 [CloudBackupConfig.autoSyncIntervalHours]。 */
private const val DAY_HOURS = 24

/** 间隔档位:1..30 天共 30 个取值,Material steps 语义下中间停点 = 30-2。 */
private const val INTERVAL_STEPS = MAX_INTERVAL_DAYS - 2

/** 低于等于该天数时给出流量 / 电量警示。 */
private const val SHORT_INTERVAL_WARNING_DAYS = 2

/** 滑块的取值区间(天)。 */
private val IntervalDaysRange = MIN_INTERVAL_DAYS.toFloat()..MAX_INTERVAL_DAYS.toFloat()

/**
 * 把持久化的间隔小时数换算成滑块的天数读数。
 *
 * 必须取整到"天"这一档:滑块只有 1..30 的整档停点,而历史配置可能存成 36 小时
 * 这类非整天值 —— 不取整就会出现"拇指停在第 1 档、右侧却读作 2 天"的自相矛盾。
 */
private fun intervalDaysOf(config: CloudBackupConfig): Float = (config.autoSyncIntervalHours / DAY_HOURS.toFloat())
    .roundToInt()
    .coerceIn(MIN_INTERVAL_DAYS, MAX_INTERVAL_DAYS)
    .toFloat()

/**
 * 云存储类型选择(WebDAV / S3 兼容存储)。
 */
@Composable
private fun CloudTypePicker(value: String, onValueChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
    ) {
        MuseDropdown(
            value = value,
            onValueChange = onValueChange,
            label = stringResource(R.string.cloud_backup_type_label),
            options = listOf(
                "webdav" to stringResource(R.string.cloud_backup_type_webdav),
                "s3" to stringResource(R.string.cloud_backup_type_s3),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 远端备份列表的空态 / 未配置提示。
 */
@Composable
private fun CloudListNotice(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(MusePaddings.cardInner),
    )
}

/**
 * 远端备份列表的加载态(转圈 + 文字,居中)。
 */
@Composable
private fun CloudListLoading() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        MuseSpinner(size = MuseIconSizes.iconMedium)
        Spacer(Modifier.width(MusePaddings.contentGap))
        // 取剩余宽度 + 限行:行内文字在大字号下不逐字换行,居中对齐保持原视觉。
        Text(
            text = stringResource(R.string.cloud_backup_list_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 表单草稿是否与已保存配置有差异。
 *
 * 只比较用户在本页真正能改的字段,所以先抹掉三类由系统维护的值再比:
 *  - [CloudBackupConfig.backupPasswordSet] 由保存逻辑按密码内容派生,不是用户输入;
 *  - [CloudBackupConfig.lastSyncAt] 由备份完成时后台写入,不属于表单内容;
 *  - [CloudBackupConfig.autoSync] / [CloudBackupConfig.autoSyncIntervalHours] 是即时生效项,
 *    改动立刻落盘,不该让顶栏「保存」亮起来。
 * 若直接比较整份 data class,一次成功的云上传就会让页面误以为用户改了东西,
 * 返回时还会弹"放弃修改"。
 */
private fun CloudBackupConfig.normalizedForFormComparison(): CloudBackupConfig = copy(
    autoSync = false,
    autoSyncIntervalHours = 0,
    lastSyncAt = 0,
    backupPasswordSet = false,
)

private fun cloudBackupDraftDirty(draft: CloudBackupConfig, persisted: CloudBackupConfig): Boolean =
    draft.normalizedForFormComparison() != persisted.normalizedForFormComparison()

/**
 * WebDAV 字段表单(URL / 用户名 / 密码 / 远程目录)。
 *
 * 外层是单层 [Column](只加一份卡片内边距):字段直接铺在卡片里,
 * 不再套一层带内边距的列表行,避免双重缩进。
 */
@Composable
private fun WebDavFields(draft: CloudBackupConfig, onDraftChange: (CloudBackupConfig) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        MuseTextField(
            value = draft.webdavUrl,
            onValueChange = { onDraftChange(draft.copy(webdavUrl = it)) },
            label = { Text(stringResource(R.string.settings_backup_webdav_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        MuseTextField(
            value = draft.webdavUsername,
            onValueChange = { onDraftChange(draft.copy(webdavUsername = it)) },
            label = { Text(stringResource(R.string.settings_backup_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // 密码走统一的密钥输入组件:掩码 + 眼睛按钮由组件自己持有,不再由页面传显隐开关。
        MuseSecretTextField(
            value = draft.webdavPassword,
            onValueChange = { onDraftChange(draft.copy(webdavPassword = it)) },
            label = stringResource(R.string.settings_backup_password),
            modifier = Modifier.fillMaxWidth(),
        )
        MuseTextField(
            value = draft.webdavPath,
            onValueChange = { onDraftChange(draft.copy(webdavPath = it)) },
            label = { Text(stringResource(R.string.settings_backup_remote_dir)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * S3 字段表单(Endpoint / Region / Bucket / AccessKey / SecretKey / KeyPrefix)。
 */
@Composable
private fun S3Fields(draft: CloudBackupConfig, onDraftChange: (CloudBackupConfig) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        MuseTextField(
            value = draft.s3Endpoint,
            onValueChange = { onDraftChange(draft.copy(s3Endpoint = it)) },
            label = { Text(stringResource(R.string.settings_backup_endpoint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        MuseTextField(
            value = draft.s3Region,
            onValueChange = { onDraftChange(draft.copy(s3Region = it)) },
            label = { Text(stringResource(R.string.settings_backup_region)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        MuseTextField(
            value = draft.s3Bucket,
            onValueChange = { onDraftChange(draft.copy(s3Bucket = it)) },
            label = { Text(stringResource(R.string.settings_backup_bucket)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // AccessKey 是账号标识,可读无妨;SecretKey 才是凭据,必须掩码。
        MuseTextField(
            value = draft.s3AccessKey,
            onValueChange = { onDraftChange(draft.copy(s3AccessKey = it)) },
            label = { Text(stringResource(R.string.settings_backup_access_key)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        MuseSecretTextField(
            value = draft.s3SecretKey,
            onValueChange = { onDraftChange(draft.copy(s3SecretKey = it)) },
            label = stringResource(R.string.settings_backup_secret_key),
            modifier = Modifier.fillMaxWidth(),
        )
        MuseTextField(
            value = draft.s3KeyPrefix,
            onValueChange = { onDraftChange(draft.copy(s3KeyPrefix = it)) },
            label = { Text(stringResource(R.string.settings_backup_key_prefix)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        )
    }
}

/**
 * 加密密码字段(通用,S3/WebDAV 均用)。
 *
 * 标题与提示合并到同一个输入框:label 是字段名,placeholder 才是"留空则明文上传"的说明,
 * 这样空态能看到后果说明,填了值之后又不会被长句挤占标题位置。
 */
@Composable
private fun EncryptionPasswordField(password: String, onPasswordChange: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
    ) {
        MuseSecretTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = stringResource(R.string.settings_backup_encrypt_password),
            placeholder = stringResource(R.string.settings_backup_encrypt_password_hint),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 单条远端备份行 — 文件名 + 时间 + 大小 + 恢复 / 删除按钮。
 *
 * @param actionsEnabled 为 false 时两个动作都不可点(另一项操作正在进行)。
 */
@Composable
private fun RemoteBackupRow(
    backup: RemoteBackup,
    actionsEnabled: Boolean,
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
            // 远端文件名由时间戳拼成,可能很长;限两行并省略,避免单行撑高整张卡片。
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(MusePaddings.contentGap))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 图标按钮必须自带描述:否则无障碍只剩一个"按钮",读不出是恢复还是删除。
            MuseTactileButton(
                icon = MuseIcons.cloudDownload,
                onClick = onRestore,
                contentDescription = stringResource(R.string.cloud_backup_restore_this),
                enabled = actionsEnabled,
            )
            Spacer(Modifier.width(MusePaddings.contentGap))
            MuseCapsuleButton(
                text = stringResource(R.string.common_delete),
                onClick = onDelete,
                enabled = actionsEnabled && !isDeleting,
                loading = isDeleting,
                variant = IosCapsuleButtonVariant.Text,
                fillWidth = false,
            )
        }
    }
}

/** 把字节数格式化为 KB/MB/GB。 */
private fun formatSize(bytes: Long): String = FileSizeFormatter.format(bytes)
