package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.crash.CrashReporterFactory
import io.zer0.muse.crash.MuseCrashHandler
import io.zer0.muse.crash.SubmitOutcome
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koin.compose.koinInject
import org.koin.core.qualifier.named

/**
 * 崩溃上报页 — 一个按钮，没有需要用户填的东西。
 *
 * ## 这一版改了什么
 *
 * 旧版让用户选「邮件 / Webhook」，再填收件邮箱或 Webhook URL。这是把开发者的基础设施
 * 配置推给了终端用户：他不知道该填谁，填错就静默失败，而设置页看上去像在要求他做技术决策。
 *
 * 现在只有三件事：
 *  1. 看有多少条待上报
 *  2. 点「立即上报」→ 直接发到开发者服务器（museai.ltd），一次点击完成
 *  3. 服务器不可达时自动改用邮件，收件人已经填好，用户只需在邮件应用里点发送
 *
 * ## 隐私边界（没有放宽）
 *
 *  - 上报只发生在用户点按钮时，无任何后台静默上报
 *  - 崩溃堆栈先经 [MuseCrashHandler.redactSensitive] 脱敏（API key / Bearer token 替换为 ***）
 *  - 只收集型号 / 品牌 / Android 版本 / ABI / App 版本 / 时间戳
 *  - 不收集 IMEI / Android ID / MAC / 位置 / 账号
 */
@Composable
fun CrashReportSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings: SettingsRepository = koinInject()
    // 复用 named("chat") OkHttpClient（上报内部会覆盖超时）
    val chatClient: OkHttpClient = koinInject<OkHttpClient>(qualifier = named("chat"))
    val scope = rememberCoroutineScope()

    val anrDetection by settings.anrDetectionFlow.collectAsStateWithLifecycle(initialValue = true)

    var pendingCount by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        pendingCount = MuseCrashHandler.getPendingCrashReports(context).size
    }

    var showConfirm by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }

    SettingsSubPageScaffold(title = stringResource(R.string.settings_crash_page_title), onBack = onBack) {
        // ── 1. 待上报崩溃 + 立即上报 ──
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
                        imageVector = MuseIcons.bug,
                        contentDescription = null,
                        tint = if (pendingCount > 0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        modifier = Modifier.size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_crash_pending_count, pendingCount),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (pendingCount > 0) {
                                stringResource(R.string.settings_crash_pending_hint_active)
                            } else {
                                stringResource(R.string.settings_crash_pending_hint_idle)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (reporting) {
                        MuseSpinner(size = 20.dp)
                    }
                }
                SettingsGroupDivider()
                MuseCapsuleButton(
                    text = stringResource(R.string.settings_crash_report_now_button),
                    onClick = { showConfirm = true },
                    enabled = pendingCount > 0 && !reporting,
                    leadingIcon = MuseIcons.cloudUpload,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                // 目的地是固定的，写在按钮下面一行就够，不是一个需要用户操作的控件。
                Text(
                    text = stringResource(R.string.settings_crash_target_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
        }

        // ── 2. 设置(ANR 检测) ──
        item { SectionLabel(stringResource(R.string.settings_crash_settings_section)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = MuseIcons.bell,
                    title = stringResource(R.string.settings_crash_anr_title),
                    subtitle = stringResource(R.string.settings_crash_anr_subtitle),
                    checked = anrDetection,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveAnrDetection(v) }
                    },
                )
            }
        }

        // ── 3. 隐私说明 ──
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

    // 上报确认（保留这一步：把内容送出去之前，用户有权知道自己送的是什么）
    if (showConfirm) {
        MuseDialog(
            onDismissRequest = { showConfirm = false },
            title = stringResource(R.string.settings_crash_confirm_dialog_title),
            content = {
                Text(
                    text = stringResource(R.string.settings_crash_confirm_dialog_content, pendingCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.settings_crash_confirm_button),
            onConfirm = {
                showConfirm = false
                reporting = true
                scope.launch {
                    val outcome = CrashReporterFactory.submit(
                        context = context,
                        client = chatClient,
                        crashLogs = MuseCrashHandler.getPendingCrashReports(context),
                    )
                    // 服务端收下了才清队列；退回邮件时用户在邮件应用里是否真发了无从得知，
                    // 所以保留记录，避免「点了没发出去、下次却再也看不到」。
                    if (outcome is SubmitOutcome.Uploaded) {
                        MuseCrashHandler.clearCrashQueue(context)
                    }
                    reporting = false
                    pendingCount = MuseCrashHandler.getPendingCrashReports(context).size
                    MuseToast.show(
                        when (outcome) {
                            is SubmitOutcome.Uploaded ->
                                context.getString(R.string.settings_crash_done_uploaded, outcome.count)
                            is SubmitOutcome.MailFallback ->
                                context.getString(R.string.settings_crash_done_mail)
                            SubmitOutcome.NothingToSend, SubmitOutcome.Failed ->
                                context.getString(R.string.settings_crash_done_failed)
                        },
                    )
                    Logger.i("CrashReportSettings", "上报结果: $outcome, 剩余 $pendingCount 条")
                }
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showConfirm = false },
        )
    }
}
