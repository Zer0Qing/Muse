package io.zer0.muse.ui.artifact

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.TablerIcons
import compose.icons.tablericons.DeviceFloppy
import compose.icons.tablericons.Share
import io.zer0.muse.R
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.util.ShareIntentHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 另存为文本时,标题清洗后为空时的兜底文件名。 */
internal const val DEFAULT_ARTIFACT_FILE_NAME = "artifact"

/**
 * 由产物标题生成 SAF 另存文件名(非法字符替换为下划线,保留中英文与数字)。
 */
internal fun artifactExportFileName(title: String): String {
    val sanitized = title.trim()
        .map { ch -> if (ch.isLetterOrDigit() || ch in "-_") ch else '_' }
        .joinToString("")
        .trim('_')
        .take(40)
    return "${sanitized.ifBlank { DEFAULT_ARTIFACT_FILE_NAME }}.txt"
}

/**
 * 产物查看器弹窗 —— 显示标题、完整内容与复制按钮。
 *
 * Phase 2 补齐: 标题行增加「分享」「另存为文本」动作,
 * 分享复用 [ShareIntentHelper],另存走 SAF CreateDocument(app 已有保存模式)。
 */
@Composable
fun ArtifactViewerDialog(
    artifact: ArtifactEntity,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isMonospace = when (artifact.type.lowercase()) {
        "code" -> true
        else -> false
    }
    // v1.62: svg/html 用 RichContentCard 渲染(WebView),不再显示纯代码
    val isRichContent = artifact.type.lowercase() in listOf("svg", "html", "chart", "mermaid")
    val exportFileName = remember(artifact.title) { artifactExportFileName(artifact.title) }

    // 另存为文本:SAF 选择目标位置后写入(与通知导出/备份导出同一模式)
    val saveTextLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            output.write(artifact.content.toByteArray(Charsets.UTF_8))
                        } != null
                    }.getOrDefault(false)
                }
                MuseToast.show(
                    context.getString(
                        if (saved) R.string.artifact_saved else R.string.artifact_save_failed,
                    ),
                )
            }
        }
    }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = null,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 标题行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = artifactTypeIcon(artifact.type),
                        contentDescription = artifactTypeLabel(artifact.type),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = artifact.title.ifBlank { stringResource(R.string.artifact_untitled) }, // 前端修复 (i18n-3)
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // Phase 2: 分享产物文本
                    IconButton(
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_SUBJECT,
                                    artifact.title.ifBlank {
                                        context.getString(R.string.artifact_untitled)
                                    },
                                )
                                putExtra(Intent.EXTRA_TEXT, artifact.content)
                            }
                            ShareIntentHelper.startChooserSafely(
                                context = context,
                                shareIntent = shareIntent,
                                chooserTitle = context.getString(R.string.artifact_share_chooser),
                            )
                        },
                        modifier = Modifier.size(MuseIconSizes.touchTarget),
                    ) {
                        Icon(
                            imageVector = TablerIcons.Share,
                            contentDescription = stringResource(R.string.action_share),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    // Phase 2: 另存为文本文件
                    IconButton(
                        onClick = { saveTextLauncher.launch(exportFileName) },
                        modifier = Modifier.size(MuseIconSizes.touchTarget),
                    ) {
                        Icon(
                            imageVector = TablerIcons.DeviceFloppy,
                            contentDescription = stringResource(R.string.artifact_save_text),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(MuseIconSizes.touchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.artifact_close), // 前端修复 (i18n-3)
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                // 内容区
                Surface(
                    color = if (isMonospace) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    shape = MuseShapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                ) {
                    if (isRichContent) {
                        // v1.62: svg/html/chart/mermaid 用 RichContentCard 渲染
                        // 弹窗内无 navController,禁用全屏预览按钮(避免无效点击)
                        io.zer0.muse.ui.markdown.RichContentCard(
                            language = artifact.type.lowercase(),
                            content = artifact.content,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp),
                            showPreviewButton = false,
                        )
                    } else {
                        // 长文本产物：内容区自身滚动（Surface 限高 320dp），
                        // 避免超长内容被 Surface 裁剪后外层滚动只能移动整个面板、裁掉部分永远不可见。
                        Text(
                            text = artifact.content,
                            style = if (isMonospace) {
                                MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = MuseMonoFontFamily,
                                )
                            } else {
                                MaterialTheme.typography.bodyMedium
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                        )
                    }
                }
            }
        },
        confirmText = stringResource(R.string.artifact_copy), // 前端修复 (i18n-3)
        // L6 已知限制: 复制未标注敏感(artifact 内容可能含敏感信息),暂不区分;后续可结合内容检测判断。
        onConfirm = { onCopy(artifact.content) },
        dismissText = null,
    )
}
