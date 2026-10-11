package io.zer0.muse.automation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.settings.SettingsSubPageScaffold
import kotlinx.coroutines.launch

/**
 * UI 自动化设置页。
 *
 * v2.x: 三通道权限配置已收敛到「权限配置向导」页(消除两页逐层重复);
 * 本页保留:功能说明、向导入口、动作编排、屏幕读取测试与开发者诊断。
 */
@Suppress("LongMethod", "FunctionNaming")
@Composable
fun AutomationSettingsPage(
    manager: AutomationManager,
    onBack: () -> Unit = {},
    /** v1.xxx: F-24 编排入口 — 跳到定时任务页编排自动化动作。 */
    onOpenScheduledTasks: () -> Unit = {},
    /** v2.x: 权限配置向导入口。 */
    onOpenPermissionWizard: () -> Unit = {},
    /** Tasker/外部自动化开关；可选以保持 Robolectric 页面测试不依赖 Koin。 */
    settings: SettingsRepository? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val taskerEnabled by settings?.taskerTriggerEnabledFlow?.collectAsStateWithLifecycle(initialValue = false)
        ?: remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ScreenTestResult?>(null) }

    SettingsSubPageScaffold(
        title = stringResource(R.string.automation_settings_title),
        onBack = onBack,
    ) {
        // 顶部说明卡
        item(key = "intro") {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = MuseIcons.computer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.automation_settings_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // v2.x: 三通道权限配置已收敛到「权限配置向导」(消除两页逐层重复)
        item(key = "permissions-entry") {
            SettingsGroup {
                SettingsItemRow(
                    icon = MuseIcons.shieldCheck,
                    title = stringResource(R.string.permission_wizard_title),
                    subtitle = stringResource(R.string.permission_wizard_desc),
                    onClick = onOpenPermissionWizard,
                ) {
                    ChevronRight()
                }
            }
        }

        // AUD-20261011-TASKER-ABUSE: exported broadcast remains opt-in and visibly warns about model cost.
        item(key = "tasker-trigger") {
            SettingsGroup {
                SettingsItemRow(
                    icon = MuseIcons.link,
                    title = stringResource(R.string.automation_tasker_title),
                    subtitle = stringResource(R.string.automation_tasker_desc),
                    onClick = null,
                ) {
                    MuseSwitch(
                        checked = taskerEnabled,
                        onCheckedChange = settings?.let { repository ->
                            { enabled -> scope.launch { repository.saveTaskerTriggerEnabled(enabled) } }
                        },
                        contentDescription = stringResource(R.string.automation_tasker_title),
                    )
                }
            }
        }

        // v1.xxx: F-24 自动化动作如何编排 — 说明 + 一键跳转到定时任务页
        item(key = "orchestration") {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = MuseIcons.clock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.automation_orchestration_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.automation_orchestration_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    MuseCapsuleButton(
                        text = stringResource(R.string.automation_orchestration_action),
                        onClick = onOpenScheduledTasks,
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = MuseIcons.clock,
                    )
                }
            }
        }

        // 测试按钮
        item(key = "test") {
            MuseCapsuleButton(
                text = if (testing) {
                    stringResource(R.string.automation_test_running)
                } else {
                    stringResource(R.string.automation_test_action)
                },
                onClick = {
                    if (testing) return@MuseCapsuleButton
                    scope.launch {
                        testing = true
                        testResult = null
                        val outcome = runCatching {
                            manager.refreshPermissions()
                            val screen = manager.readScreen()
                            if (screen.source == "unavailable") {
                                ScreenTestResult(
                                    context.getString(R.string.automation_test_no_channel),
                                    success = false,
                                )
                            } else {
                                val unknown = context.getString(R.string.automation_test_unknown)
                                // ST-04: 包名与数据来源属开发者诊断信息,收进 devInfo 折叠区
                                ScreenTestResult(
                                    message = buildString {
                                        appendLine(
                                            context.getString(
                                                R.string.automation_test_node_count,
                                                screen.nodes.size,
                                            ),
                                        )
                                        appendLine(
                                            context.getString(
                                                R.string.automation_test_resolution,
                                                screen.screenWidth,
                                                screen.screenHeight,
                                            ),
                                        )
                                    }.trimEnd('\n'),
                                    success = true,
                                    devInfo = buildString {
                                        appendLine(
                                            context.getString(
                                                R.string.automation_test_current_app,
                                                screen.packageName ?: unknown,
                                            ),
                                        )
                                        appendLine(
                                            context.getString(
                                                R.string.automation_test_source,
                                                screen.source,
                                            ),
                                        )
                                    }.trimEnd('\n'),
                                )
                            }
                        }.getOrElse {
                            ScreenTestResult(
                                context.getString(
                                    R.string.automation_test_failed,
                                    it.message ?: context.getString(R.string.automation_test_unknown),
                                ),
                                success = false,
                            )
                        }
                        testResult = outcome
                        testing = false
                    }
                },
                enabled = !testing,
                loading = testing,
                leadingIcon = MuseIcons.play,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 测试结果
        testResult?.let { result ->
            item(key = "test-result") {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (result.success) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            text = result.message,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // ST-04: 开发者诊断信息默认折叠,展开显示包名与数据来源
                        result.devInfo?.let { devInfo ->
                            var showDevInfo by remember { mutableStateOf(false) }
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { showDevInfo = !showDevInfo }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.automation_developer_info),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    imageVector = if (showDevInfo) {
                                        MuseIcons.chevronUp
                                    } else {
                                        MuseIcons.chevronDown
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (showDevInfo) {
                                Text(
                                    text = devInfo,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 一次屏幕读取自测的结果:用户可见文案 + 是否成功(决定提示卡配色)。
 * devInfo 为开发者诊断信息(包名/数据来源),ST-04 起默认折叠展示。
 */
private data class ScreenTestResult(
    val message: String,
    val success: Boolean,
    val devInfo: String? = null,
)
