package io.zer0.muse.ui.settings

import android.os.StatFs
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.asr.local.LocalModelDownloader
import io.zer0.muse.asr.local.LocalModelStore
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 语音识别设置 →「模型下载」三级页。
 *
 * 下载是应用级后台任务（[LocalModelDownloader] 自带协程作用域），本页只是它的观察窗：
 * 进页面看到进度、离开页面下载继续，回到页面状态原样呈现。进度与失败原因订阅下载器的
 * StateFlow，就绪与否读文件系统 —— 两处都不落 DataStore，避免状态漂移。
 */
@Composable
fun SettingsLocalAsrModelPage(onBack: () -> Unit) {
    SettingsSubPageScaffold(
        title = stringResource(R.string.asr_local_download_title),
        onBack = onBack,
    ) {
        // 页面容器已带 pageMargin 水平内边距，卡片不再叠加
        item { LocalModelDownloadCard() }
        item {
            Text(
                text = stringResource(R.string.asr_local_model_page_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 语音识别设置页里的「模型下载」入口行。
 *
 * 任何引擎下都显示：用户先看见模型才能先下模型，而不是先选中本地引擎再被提示「模型缺失」。
 * 副标题实时反映状态（未下载 / 下载中百分比 / 就绪大小 / 失败），不用点进去才知道。
 */
@Composable
internal fun LocalModelEntryRow(onClick: () -> Unit) {
    val context = LocalContext.current
    val downloadState by LocalModelDownloader.state.collectAsStateWithLifecycle()
    var fsStatus by remember { mutableStateOf(LocalModelStore.status(context)) }
    var fsBytes by remember { mutableStateOf(LocalModelStore.totalBytes(context)) }
    // 下载结束（成功/失败/取消）后重读文件系统，副标题不能停在旧状态
    LaunchedEffect(dsCompletedKey(downloadState)) {
        fsStatus = LocalModelStore.status(context)
        fsBytes = LocalModelStore.totalBytes(context)
    }

    val subtitle = when (val ds = downloadState) {
        is LocalModelDownloader.DownloadState.Downloading -> {
            val percent = if (ds.bytesTotal > 0) {
                (ds.bytesDone * 100 / ds.bytesTotal).toInt()
            } else {
                0
            }
            stringResource(R.string.settings_asr_local_model_entry_downloading, percent)
        }

        is LocalModelDownloader.DownloadState.Failed -> when (fsStatus) {
            LocalModelStore.ModelStatus.READY -> stringResource(
                R.string.settings_asr_local_model_entry_ready,
                Formatter.formatShortFileSize(context, fsBytes),
            )
            LocalModelStore.ModelStatus.INCOMPLETE -> stringResource(R.string.settings_asr_local_model_entry_incomplete)
            LocalModelStore.ModelStatus.NOT_DOWNLOADED -> stringResource(R.string.settings_asr_local_model_entry_failed)
        }

        else -> when (fsStatus) {
            LocalModelStore.ModelStatus.READY -> stringResource(
                R.string.settings_asr_local_model_entry_ready,
                Formatter.formatShortFileSize(context, fsBytes),
            )
            LocalModelStore.ModelStatus.INCOMPLETE -> stringResource(R.string.settings_asr_local_model_entry_incomplete)
            LocalModelStore.ModelStatus.NOT_DOWNLOADED -> stringResource(R.string.settings_asr_local_model_entry_not_downloaded)
        }
    }

    Surface(
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MusePaddings.itemGap),
    ) {
        MuseListItem(
            onClick = onClick,
            leadingContent = {
                Icon(
                    imageVector = MuseIcons.download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            headlineContent = { Text(stringResource(R.string.settings_asr_local_model_entry_title)) },
            supportingContent = { Text(subtitle) },
            trailingContent = {
                Icon(
                    imageVector = MuseIcons.chevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }
}

/**
 * 本地离线语音模型卡片：未下载 / 下载中 / 就绪 / 失败四态。
 *
 * 交互门槛：计费网络与磁盘空间不足在点「下载」时确认/阻止；删除需要二次确认；
 * 下载失败不自动重试，由用户点「重试」——移动流量下自动循环会烧流量还不被察觉。
 */
@Composable
internal fun LocalModelDownloadCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloadState by LocalModelDownloader.state.collectAsStateWithLifecycle()
    val settings: io.zer0.muse.data.SettingsRepository = koinInject()
    val asrConfig by settings.asrConfigFlow.collectAsStateWithLifecycle(
        initialValue = io.zer0.muse.asr.AsrConfig(),
    )
    val usingLocal = asrConfig.provider == io.zer0.muse.asr.AsrProviderType.LOCAL_SENSEVOICE

    var showMeteredConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // 进入页面时读一次文件系统状态；下载完成/删除后用 key 触发重读
    var fsStatus by remember { mutableStateOf(LocalModelStore.status(context)) }
    var fsBytes by remember { mutableStateOf(LocalModelStore.totalBytes(context)) }
    fun refreshFs() {
        fsStatus = LocalModelStore.status(context)
        fsBytes = LocalModelStore.totalBytes(context)
    }

    val formattedTotal = remember(fsBytes) {
        Formatter.formatShortFileSize(context, fsBytes)
    }

    fun startDownloadChecked() {
        val freeBytes = StatFs(context.filesDir.path).availableBytes
        val remaining = LocalModelDownloader.TOTAL_BYTES - LocalModelStore.totalBytes(context)
        if (remaining > 0 && freeBytes < remaining) {
            MuseToast.show(
                context.getString(
                    R.string.asr_local_download_insufficient_space,
                    Formatter.formatShortFileSize(context, remaining),
                ),
            )
            return
        }
        val conn = context.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = conn?.getNetworkCapabilities(conn.activeNetwork)
        val metered = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
        if (metered) {
            showMeteredConfirm = true
        } else {
            scope.launch { LocalModelDownloader.start(context) }
        }
    }

    Surface(
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            ) {
                ModelIconTile()
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.asr_local_download_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.asr_local_download_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when (val ds = downloadState) {
                is LocalModelDownloader.DownloadState.Downloading -> {
                    val fraction = if (ds.bytesTotal > 0) {
                        ds.bytesDone.toFloat() / ds.bytesTotal.toFloat()
                    } else {
                        0f
                    }
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${Formatter.formatShortFileSize(context, ds.bytesDone)} / " +
                            Formatter.formatShortFileSize(context, ds.bytesTotal),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        MuseCapsuleButton(
                            text = stringResource(R.string.asr_local_download_cancel),
                            onClick = { LocalModelDownloader.cancel() },
                            variant = IosCapsuleButtonVariant.Text,
                            fillWidth = false,
                        )
                    }
                }

                else -> {
                    if (ds is LocalModelDownloader.DownloadState.Failed) {
                        CardError(ds.message)
                    }
                    when (fsStatus) {
                        LocalModelStore.ModelStatus.READY -> {
                            Text(
                                text = stringResource(R.string.asr_local_download_ready, formattedTotal),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                MuseCapsuleButton(
                                    text = stringResource(R.string.asr_local_download_delete),
                                    onClick = { showDeleteConfirm = true },
                                    variant = IosCapsuleButtonVariant.Text,
                                    fillWidth = false,
                                )
                                // 模型就绪不等于正在用：用户可能还没把引擎切过来。
                                // 给一键切换而不是静默改设置 —— 引擎选择是用户的决定。
                                if (!usingLocal) {
                                    MuseCapsuleButton(
                                        text = stringResource(R.string.asr_local_download_use),
                                        onClick = {
                                            scope.launch {
                                                // 本地模型名固定（对上文件名），必须覆盖旧的云端模型名；
                                                // 否则请求会带着 whisper-1 之类的名字进入本地控制器。
                                                val local = io.zer0.muse.asr.AsrConfig(
                                                    provider = io.zer0.muse.asr.AsrProviderType.LOCAL_SENSEVOICE,
                                                )
                                                settings.saveAsrConfig(
                                                    asrConfig.copy(
                                                        provider = local.provider,
                                                        model = local.defaultModel(),
                                                    ),
                                                )
                                                MuseToast.show(context.getString(R.string.asr_local_download_use_done))
                                            }
                                        },
                                        variant = IosCapsuleButtonVariant.Primary,
                                        fillWidth = false,
                                    )
                                }
                            }
                        }

                        LocalModelStore.ModelStatus.INCOMPLETE -> {
                            Text(
                                text = stringResource(R.string.asr_local_download_incomplete),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                MuseCapsuleButton(
                                    text = stringResource(
                                        if (ds is LocalModelDownloader.DownloadState.Failed) {
                                            R.string.asr_local_download_retry
                                        } else {
                                            R.string.asr_local_download_action
                                        },
                                    ),
                                    onClick = { startDownloadChecked() },
                                    variant = IosCapsuleButtonVariant.Primary,
                                    fillWidth = false,
                                )
                            }
                        }

                        LocalModelStore.ModelStatus.NOT_DOWNLOADED -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                MuseCapsuleButton(
                                    text = stringResource(
                                        if (ds is LocalModelDownloader.DownloadState.Failed) {
                                            R.string.asr_local_download_retry
                                        } else {
                                            R.string.asr_local_download_action
                                        },
                                    ),
                                    onClick = { startDownloadChecked() },
                                    variant = IosCapsuleButtonVariant.Primary,
                                    fillWidth = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 下载结束（成功或失败）后刷新文件系统状态，让卡片回到就绪/失败展示
    LaunchedEffect(dsCompletedKey(downloadState)) {
        refreshFs()
    }

    if (showMeteredConfirm) {
        AlertDialog(
            onDismissRequest = { showMeteredConfirm = false },
            title = { Text(stringResource(R.string.asr_local_download_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.asr_local_download_metered_confirm,
                        Formatter.formatShortFileSize(context, LocalModelDownloader.TOTAL_BYTES),
                    ),
                )
            },
            confirmButton = {
                MuseCapsuleButton(
                    text = stringResource(R.string.asr_local_download_action),
                    onClick = {
                        showMeteredConfirm = false
                        scope.launch { LocalModelDownloader.start(context) }
                    },
                    variant = IosCapsuleButtonVariant.Primary,
                    fillWidth = false,
                )
            },
            dismissButton = {
                MuseCapsuleButton(
                    text = stringResource(R.string.asr_local_download_cancel),
                    onClick = { showMeteredConfirm = false },
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                )
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.asr_local_download_title)) },
            text = { Text(stringResource(R.string.asr_local_download_delete_confirm)) },
            confirmButton = {
                MuseCapsuleButton(
                    text = stringResource(R.string.asr_local_download_delete),
                    onClick = {
                        showDeleteConfirm = false
                        LocalModelStore.delete(context)
                        refreshFs()
                    },
                    variant = IosCapsuleButtonVariant.Primary,
                    fillWidth = false,
                )
            },
            dismissButton = {
                MuseCapsuleButton(
                    text = stringResource(R.string.asr_local_download_cancel),
                    onClick = { showDeleteConfirm = false },
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                )
            },
        )
    }
}

/** 卡片内联错误提示：失败原因直接显示在操作按钮上方，不弹窗打断。 */
@Composable
private fun CardError(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/** 模型卡片图标砖 — 与引擎卡片的视觉语言一致（主色浅底圆角方块）。 */
@Composable
private fun ModelIconTile() {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(MuseShapes.large)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MuseIcons.download,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(MuseIconSizes.iconMedium),
        )
    }
}

/** 把下载状态收敛成一个「这轮下载结束了吗」的 key：进行中与结束态各自区分，驱动文件系统重读。 */
internal fun dsCompletedKey(state: LocalModelDownloader.DownloadState): Int = when (state) {
    LocalModelDownloader.DownloadState.Idle -> 0
    is LocalModelDownloader.DownloadState.Downloading -> 1
    is LocalModelDownloader.DownloadState.Done -> 2
    is LocalModelDownloader.DownloadState.Failed -> 3
}
