package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.crash.CrashReporterFactory
import io.zer0.muse.crash.MuseCrashHandler
import io.zer0.muse.crash.SubmitOutcome
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.actionItem
import io.zer0.muse.ui.common.surface.switchItem
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.titleSmallStrong
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koin.compose.koinInject
import org.koin.core.qualifier.named

/**
 * 崩溃上报页 — 一个按钮，没有需要用户填的东西。
 *
 * 三件事：看有多少条待上报、点「立即上报」直接发到服务器、服务器不可达时自动改用邮件。
 *
 * 隐私边界：
 *  - 上报只发生在用户点按钮时，无任何后台静默上报
 *  - 崩溃堆栈先经 [MuseCrashHandler.redactSensitive] 脱敏
 *  - 只收集型号 / 品牌 / Android 版本 / ABI / App 版本 / 时间戳
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
        item(key = "crash_pending") {
            CardGroup(title = { Text(stringResource(R.string.settings_crash_pending_section)) }) {
                content(key = "crash_pending_count") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.md),
                    ) {
                        Icon(
                            imageVector = MuseIcons.bug,
                            contentDescription = null,
                            tint = if (pendingCount > 0) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_crash_pending_count, pendingCount),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = if (pendingCount > 0) {
                                    stringResource(R.string.settings_crash_pending_hint_active)
                                } else {
                                    stringResource(R.string.settings_crash_pending_hint_idle)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (reporting) {
                            MuseSpinner(size = MuseIconSizes.iconSmall)
                        }
                    }
                }
                actionItem(
                    key = "crash_report_now",
                    title = stringResource(R.string.settings_crash_report_now_button),
                    loading = reporting,
                    enabled = pendingCount > 0 && !reporting,
                    onClick = { showConfirm = true },
                )
                content(key = "crash_target_hint") {
                    Text(
                        text = stringResource(R.string.settings_crash_target_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(MusePaddings.cardInner),
                    )
                }
            }
        }

        item(key = "crash_settings") {
            CardGroup(title = { Text(stringResource(R.string.settings_crash_settings_section)) }) {
                switchItem(
                    key = "crash_anr_detection",
                    title = stringResource(R.string.settings_crash_anr_title),
                    checked = anrDetection,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveAnrDetection(v) }
                    },
                    leadingContent = {
                        Icon(
                            imageVector = MuseIcons.bell,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    },
                )
            }
        }

        item(key = "crash_privacy") {
            CardGroup(title = { Text(stringResource(R.string.settings_crash_privacy_section)) }) {
                content(key = "crash_privacy_body") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                        verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_crash_privacy_title),
                            style = MaterialTheme.typography.titleSmallStrong,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.settings_crash_privacy_content),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showConfirm = false },
        )
    }
}
