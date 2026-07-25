package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Webhook
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import io.zer0.muse.ui.common.IosChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.crash.CrashReporter
import io.zer0.muse.crash.CrashReporterFactory
import io.zer0.muse.crash.MuseCrashHandler
import io.zer0.muse.crash.NoopCrashReporter
import io.zer0.muse.crash.buildStandardMetadata
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.common.SectionLabel
import io.zer0.muse.ui.common.SettingsGroup
import io.zer0.muse.ui.common.SettingsGroupDivider
import io.zer0.muse.ui.common.SettingsSwitchRow
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koin.compose.koinInject
import org.koin.core.qualifier.named

/**
 * v2.0+: 崩溃上报设置页 — 隐私优先的可选崩溃上报配置。
 *
 * 功能:
 *  - 启用/关闭崩溃上报(默认关闭,绝不默认上报)
 *  - 选择上报方式:邮件(Intent.ACTION_SEND)/ Webhook(HTTP POST)
 *  - 配置邮件收件人 / Webhook URL
 *  - 查看待上报崩溃数量,手动触发上报
 *
 * 隐私约束:
 *  - 上报前必须用户主动开启开关(默认 false)
 *  - 上报前必须用户在弹窗中确认(见 [showReportConfirmDialog])
 *  - 崩溃内容已脱敏(去掉 API key / Bearer token,见 [MuseCrashHandler.redactSensitive])
 *  - 仅收集型号/Android 版本/App 版本/时间戳,不收集唯一设备标识
 *
 * 接入 SettingsRepository 持久化:开关/方式/邮箱/Webhook URL 走 DataStore。
 */
@Composable
fun CrashReportSettingsPage(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings: SettingsRepository = koinInject()
    // 复用 named("chat") OkHttpClient(Webhook 上报时传入,内部会覆盖超时)
    val chatClient: OkHttpClient = koinInject<OkHttpClient>(qualifier = named("chat"))
    val scope = rememberCoroutineScope()

    // 订阅崩溃上报配置流
    val enabled by settings.crashReportEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val method by settings.crashReportMethodFlow.collectAsStateWithLifecycle(initialValue = "email")
    val email by settings.crashReportEmailFlow.collectAsStateWithLifecycle(initialValue = "")
    val webhookUrl by settings.crashReportWebhookUrlFlow.collectAsStateWithLifecycle(initialValue = "")

    // 待上报崩溃列表(启动时加载一次,上报后刷新)
    var pendingCount by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        pendingCount = MuseCrashHandler.getPendingCrashReports(context).size
    }

    // 邮箱/Webhook 输入草稿(用户编辑时暂存,失焦或点保存才落盘)
    var emailDraft by remember(email) { mutableStateOf(email) }
    var webhookDraft by remember(webhookUrl) { mutableStateOf(webhookUrl) }

    // 上报确认弹窗 + 上报中状态
    var showReportConfirmDialog by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }

    SettingsSubPageScaffold(title = stringResource(R.string.settings_crash_page_title), onBack = onBack) {
        // ── 1. 待上报崩溃 ──
        item { SectionLabel(stringResource(R.string.settings_crash_pending_section)) }
        item {
            SettingsGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.BugReport,
                        contentDescription = null,
                        tint = if (pendingCount > 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_crash_pending_count, pendingCount),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (pendingCount > 0) stringResource(R.string.settings_crash_pending_hint_active)
                            else stringResource(R.string.settings_crash_pending_hint_idle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (reporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
                SettingsGroupDivider()
                Button(
                    onClick = { showReportConfirmDialog = true },
                    enabled = pendingCount > 0 && enabled && !reporting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = MuseShapes.medium,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.settings_crash_report_now_button))
                }
            }
        }

        // ── 2. 上报开关 ──
        item { SectionLabel(stringResource(R.string.settings_crash_settings_section)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = Icons.Outlined.CloudUpload,
                    title = stringResource(R.string.settings_crash_enable_title),
                    subtitle = stringResource(R.string.settings_crash_enable_subtitle),
                    checked = enabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveCrashReportEnabled(v) }
                    },
                )
            }
        }

        // ── 3. 上报方式选择(仅启用后显示) ──
        if (enabled) {
            item { SectionLabel(stringResource(R.string.settings_crash_method_section)) }
            item {
                SettingsGroup {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = if (method == "email") Icons.Outlined.Mail else Icons.Outlined.Webhook,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_crash_method_label),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                listOf(
                                    CrashReporterFactory.METHOD_EMAIL to stringResource(R.string.settings_crash_method_email),
                                    CrashReporterFactory.METHOD_WEBHOOK to "Webhook",
                                ).forEach { (value, label) ->
                                    IosChip(
                                        selected = method == value,
                                        onClick = {
                                            scope.launch { settings.saveCrashReportMethod(value) }
                                        },
                                        label = label,
                                    )
                                }
                            }
                        }
                    }
                    // ── 邮件配置 ──
                    if (method == CrashReporterFactory.METHOD_EMAIL) {
                        SettingsGroupDivider()
                        OutlinedTextField(
                            value = emailDraft,
                            onValueChange = { emailDraft = it },
                            label = { Text(stringResource(R.string.settings_crash_email_label)) },
                            placeholder = { Text(stringResource(R.string.settings_crash_email_placeholder)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            shape = MuseShapes.medium,
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    settings.saveCrashReportEmail(emailDraft.trim())
                                    MuseToast.show(context.getString(R.string.settings_crash_email_saved_toast))
                                }
                            },
                            enabled = emailDraft.trim() != email && emailDraft.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            shape = MuseShapes.medium,
                        ) {
                            Text(stringResource(R.string.settings_crash_email_save_button))
                        }
                    }
                    // ── Webhook 配置 ──
                    if (method == CrashReporterFactory.METHOD_WEBHOOK) {
                        SettingsGroupDivider()
                        OutlinedTextField(
                            value = webhookDraft,
                            onValueChange = { webhookDraft = it },
                            label = { Text("Webhook URL") },
                            placeholder = { Text("https://example.com/crash-report") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            shape = MuseShapes.medium,
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    settings.saveCrashReportWebhookUrl(webhookDraft.trim())
                                    MuseToast.show(context.getString(R.string.settings_crash_webhook_saved_toast))
                                }
                            },
                            enabled = webhookDraft.trim() != webhookUrl && webhookDraft.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            shape = MuseShapes.medium,
                        ) {
                            Text(stringResource(R.string.settings_crash_webhook_save_button))
                        }
                    }
                }
            }
        }

        // ── 4. 隐私说明 ──
        item { SectionLabel(stringResource(R.string.settings_crash_privacy_section)) }
        item {
            SettingsGroup {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.screen),
                ) {
                    Text(
                        text = stringResource(R.string.settings_crash_privacy_title),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.settings_crash_privacy_content),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // ── 上报确认弹窗(隐私门禁 — 即使用户已开启开关,也要每次确认) ──
    if (showReportConfirmDialog) {
        MuseDialog(
            onDismissRequest = { showReportConfirmDialog = false },
            title = stringResource(R.string.settings_crash_confirm_dialog_title),
            content = {
                val target = if (method == CrashReporterFactory.METHOD_EMAIL) {
                    stringResource(R.string.settings_crash_confirm_target_email, email)
                } else {
                    stringResource(R.string.settings_crash_confirm_target_webhook, webhookUrl)
                }
                Text(
                    text = stringResource(R.string.settings_crash_confirm_dialog_content, pendingCount, target),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.settings_crash_confirm_button),
            onConfirm = {
                showReportConfirmDialog = false
                reporting = true
                scope.launch {
                    performCrashReport(
                        context = context,
                        method = method,
                        email = email,
                        webhookUrl = webhookUrl,
                        client = chatClient,
                        onComplete = { success, message ->
                            reporting = false
                            // 上报后刷新待上报数量(成功则清空队列)
                            pendingCount = MuseCrashHandler.getPendingCrashReports(context).size
                            MuseToast.show(message)
                            Logger.i("CrashReportSettings", "上报完成: success=$success, msg=$message")
                        },
                    )
                }
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showReportConfirmDialog = false },
        )
    }
}

/**
 * 执行崩溃上报 — 遍历待上报队列逐条上报,完成后清空队列。
 *
 * @param context 应用上下文
 * @param method 上报方式("email" / "webhook")
 * @param email 邮件收件人(method=email 时使用)
 * @param webhookUrl Webhook URL(method=webhook 时使用)
 * @param client OkHttpClient(Webhook 上报用)
 * @param onComplete 上报完成回调(success / 提示消息)
 */
private fun performCrashReport(
    context: android.content.Context,
    method: String,
    email: String,
    webhookUrl: String,
    client: OkHttpClient,
    onComplete: (success: Boolean, message: String) -> Unit,
) {
    val pending = MuseCrashHandler.getPendingCrashReports(context)
    if (pending.isEmpty()) {
        onComplete(false, context.getString(R.string.settings_crash_no_pending))
        return
    }
    // 参数校验 — 方式不匹配或参数为空时给用户明确提示
    val reporter: CrashReporter = when {
        method == CrashReporterFactory.METHOD_EMAIL && email.isBlank() -> {
            onComplete(false, context.getString(R.string.settings_crash_no_email))
            return
        }
        method == CrashReporterFactory.METHOD_WEBHOOK &&
            (webhookUrl.isBlank() || !webhookUrl.startsWith("http")) -> {
            onComplete(false, context.getString(R.string.settings_crash_no_webhook))
            return
        }
        else -> CrashReporterFactory.create(
            context = context,
            method = method,
            emailRecipient = email,
            webhookUrl = webhookUrl,
            client = client,
        )
    }
    if (reporter is NoopCrashReporter) {
        onComplete(false, context.getString(R.string.settings_crash_no_method))
        return
    }
    // 构建标准元数据(设备信息 + 时间戳,上报时现取,保证版本信息最新)
    val metadata = buildStandardMetadata(context)
    // 逐条上报(邮件方式会调起多次 Intent,Webhook 方式并发 POST)
    pending.forEach { crashLog -> reporter.report(crashLog, metadata) }
    // 清空队列(邮件方式无法确认用户是否真正发送,但队列已消费避免重复提示)
    MuseCrashHandler.clearCrashQueue(context)
    onComplete(true, context.getString(R.string.settings_crash_reported_count, pending.size))
}
