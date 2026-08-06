@file:Suppress("FunctionNaming", "LongMethod")

package io.zer0.muse.ui

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.zer0.muse.R
import io.zer0.muse.crash.MuseCrashHandler
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// P1-4:崩溃日志面板 — 把 MuseCrashHandler 已有但仅 SafeMode 使用的崩溃日志
//       列表 / ZIP 打包 / 单条分享能力,在正常模式下也透出给用户。
//
//  数据源:MuseCrashHandler.listCrashLogs(context) → List<File>
//  打包  :MuseCrashHandler.packageCrashLogsToZip(context) → File?(cacheDir/zip)
//  分享  :FileProvider + ACTION_SEND(走 file_paths.xml 中已声明的 files-path / cache-path)
// ════════════════════════════════════════════════════════════════════════════

/**
 * 崩溃日志底部面板。
 *
 * 行为:
 *  - 打开时异步拉取 [MuseCrashHandler.listCrashLogs],按 mtime 降序展示
 *  - 每条崩溃日志可点击展开,内联预览前 [CRASH_PREVIEW_CHARS] 字符
 *  - 顶部"导出 ZIP 并分享"按钮调用 [MuseCrashHandler.packageCrashLogsToZip]
 *    打包全部崩溃日志 + 设备信息,通过 ACTION_SEND 分享
 *  - 每条日志右侧"分享"按钮单独分享该 .txt 文件
 *
 * 设计说明:SafeModeScreen 已有的 shareCrashLog,这里把同一能力在正常模式下复用。
 */
@Composable
internal fun CrashLogSheet(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    // ── 数据状态 ─────────────────────────────────────────────────────────
    var logs by remember { mutableStateOf<List<File>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // 当前展开的崩溃日志文件名(null = 全部折叠)
    var expandedFile by remember { mutableStateOf<String?>(null) }
    // 展开后懒加载的文件内容(避免一次性把所有日志读入内存)
    var expandedContent by remember { mutableStateOf<String?>(null) }

    // 打开时拉一次崩溃日志列表(读 filesDir 是 IO,放 Dispatchers.IO)
    LaunchedEffect(Unit) {
        logs = withContext(Dispatchers.IO) { MuseCrashHandler.listCrashLogs(context) }
        loading = false
    }

    // expandedFile 变化时,异步读取对应文件内容(截断到预览长度,避免大文件 OOM)
    LaunchedEffect(expandedFile) {
        val name = expandedFile
        if (name == null) {
            expandedContent = null
        } else {
            val file = logs.firstOrNull { it.name == name }
            expandedContent = if (file != null) {
                withContext(Dispatchers.IO) {
                    runCatching { file.readText().take(CRASH_PREVIEW_CHARS) }.getOrNull()
                }
            } else {
                null
            }
        }
    }

    MuseBottomSheet(onDismissRequest = onDismiss) {
        // ── 标题行 + ZIP 导出按钮 ────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.debug_crash_log_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            // ZIP 打包分享:即使只有 1 条崩溃日志也走 zip 路径,统一带 device_info
            Button(onClick = { shareCrashZip(context) }) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.debug_export_zip_and_share))
            }
        }

        Spacer(Modifier.height(MusePaddings.contentGap))

        // ── 计数 + 状态分支 ──────────────────────────────────────────────
        when {
            loading -> {
                Text(
                    text = stringResource(R.string.debug_loading_crash_logs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = MusePaddings.contentGap),
                )
            }
            logs.isEmpty() -> {
                Text(
                    text = stringResource(R.string.debug_no_crash_logs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = MusePaddings.contentGap),
                )
            }
            else -> {
                Text(
                    text = stringResource(R.string.debug_crash_log_count, logs.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(MusePaddings.tightGap))
                // 限制最大高度,避免列表过长撑爆 BottomSheet
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    items(
                        items = logs,
                        key = { it.name },
                    ) { file ->
                        CrashLogItem(
                            file = file,
                            expanded = expandedFile == file.name,
                            expandedContent = if (expandedFile == file.name) expandedContent else null,
                            onToggleExpand = {
                                expandedFile = if (expandedFile == file.name) null else file.name
                            },
                            onShare = { shareCrashFile(context, file) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单条崩溃日志条目。
 *
 * 折叠时:文件名 + 大小 + 修改时间 + 分享按钮
 * 展开时:在折叠信息下方追加内联预览(等宽字体,可垂直滚动)
 */
@Composable
private fun CrashLogItem(
    file: File,
    expanded: Boolean,
    expandedContent: String?,
    onToggleExpand: () -> Unit,
    onShare: () -> Unit,
) {
    // 文件名形如 crash-20260721-153012.txt;直接展示原文件名,保持与磁盘一致便于排查
    val sizeStr = remember(file.length()) { DebugFormatters.formatFileSize(file.length()) }
    val timeStr = remember(file.lastModified()) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(file.lastModified()))
    }

    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpand),
    ) {
        Column(modifier = Modifier.padding(MusePaddings.cardInnerAux)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = MuseMonoFontFamily,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "$timeStr · $sizeStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onShare) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = stringResource(R.string.debug_cd_share_crash_log),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 展开后:内联预览(等宽字体 + 垂直滚动)
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    val preview = expandedContent
                    Surface(
                        shape = MuseShapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = preview ?: stringResource(R.string.debug_loading),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(MusePaddings.contentGap)
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                    if (preview != null && preview.length >= CRASH_PREVIEW_CHARS) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.debug_crash_preview_truncated, CRASH_PREVIEW_CHARS),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 打包全部崩溃日志为 ZIP 并通过 ACTION_SEND 分享。
 *
 * ZIP 内容由 [MuseCrashHandler.packageCrashLogsToZip] 决定:device_info.txt + 各 crash-*.txt。
 * 文件位于 cacheDir(已在 file_paths.xml 中通过 cache-path 暴露给 FileProvider)。
 */
private fun shareCrashZip(context: Context) {
    val zipFile = MuseCrashHandler.packageCrashLogsToZip(context)
    if (zipFile == null) {
        MuseToast.show(context.getString(R.string.debug_export_failed_no_crash_logs))
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            zipFile,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "muse crash logs — ${zipFile.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.debug_share_crash_zip_chooser))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        MuseToast.show(context.getString(R.string.debug_share_failed_no_uri))
    }
}

/**
 * 分享单条崩溃日志文件(.txt)。
 *
 * 文件位于 filesDir/crash/(已在 file_paths.xml 中通过 files-path 暴露给 FileProvider)。
 */
private fun shareCrashFile(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "muse crash log — ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.debug_share_crash_log_chooser))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        MuseToast.show(context.getString(R.string.debug_share_failed_no_uri))
    }
}


/** 崩溃日志内联预览的最大字符数,避免一次性把超大堆栈读入 Compose 状态。 */
private const val CRASH_PREVIEW_CHARS = 2000
