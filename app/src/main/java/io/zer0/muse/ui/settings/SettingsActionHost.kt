package io.zer0.muse.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.zer0.muse.R
import io.zer0.muse.diagnostic.DiagnosticExporter
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.update.UpdateCheckResult
import io.zer0.muse.update.UpdateNotifier
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 设置条目里「页内动作」的执行器。
 *
 * 检查更新、导出诊断这两类动作需要 Context、协程与进度状态，放在一个地方维护；
 * 设置首页的搜索结果与分类页共用它，点击行为不会两处各写一遍。
 */
data class SettingsActionHost(
    /** 正在执行的动作（按钮显示进度圈）；空闲为 null。 */
    val busyAction: SettingsActionId?,
    val run: (SettingsActionId) -> Unit,
)

@Composable
internal fun rememberSettingsActionHost(): SettingsActionHost {
    val context = LocalContext.current
    val updateNotifier: UpdateNotifier = koinInject()
    val scope = rememberCoroutineScope()
    var busyAction by remember { mutableStateOf<SettingsActionId?>(null) }

    val run: (SettingsActionId) -> Unit = { id ->
        when (id) {
            SettingsActionId.CHECK_UPDATE -> if (busyAction == null) {
                busyAction = id
                scope.launch {
                    val result = try {
                        updateNotifier.checkManually(context)
                    } finally {
                        busyAction = null
                    }
                    when (result) {
                        is UpdateCheckResult.NewVersion -> MuseToast.show(context.getString(R.string.update_found_new))
                        UpdateCheckResult.UpToDate -> MuseToast.show(context.getString(R.string.update_already_latest))
                        is UpdateCheckResult.Failed -> MuseToast.show(
                            context.getString(R.string.settings_about_update_check_failed, result.message),
                        )
                        UpdateCheckResult.Skipped -> Unit
                    }
                }
            }

            // 导出包含 WorkManager 队列查询与 zip 写入，放到协程里等结果，不阻塞点击回调。
            SettingsActionId.EXPORT_DIAGNOSTICS -> scope.launch { DiagnosticExporter.share(context) }
        }
    }

    return SettingsActionHost(busyAction = busyAction, run = run)
}
