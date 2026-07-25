package io.zer0.muse.ui.common

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * 文件附件芯片 — 显示在工具调用结果下方。
 *
 * 点击用 FileProvider 或 ACTION_VIEW 打开文件。
 *
 * @param filePath 沙盒内文件的绝对路径
 * @param fileSize 文件字节数(可选,显示尺寸)
 * @param modifier 修饰符
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentChip(
    filePath: String,
    fileSize: Long? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val file = File(filePath)
    val fileName = file.name
    val ext = file.extension.lowercase()

    // 根据扩展名选图标
    val icon = when (ext) {
        "txt", "md", "json", "xml", "csv", "log" -> Icons.Default.Description
        "png", "jpg", "jpeg", "gif", "webp", "bmp" -> Icons.Default.Image
        else -> Icons.Default.AttachFile
    }

    // 格式化文件大小
    val sizeText = fileSize?.let { formatFileSize(it) } ?: ""

    Surface(
        onClick = {
            // 尝试用 FileProvider 打开文件
            try {
                val uri = getFileUri(context, file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, getMimeType(ext))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                MuseToast.show(context.getString(R.string.common_open_file_failed, e.message))
            }
        },
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                // H-AC1: 点击区域高度不足 48dp,补 heightIn(min=touchTarget) 满足最小触摸目标。
                .heightIn(min = MuseIconSizes.touchTarget)
                // L-AC2: 裸 12/8/20 替换为 MusePaddings / MuseIconSizes 令牌。
                .padding(horizontal = MusePaddings.inputPadding, vertical = MusePaddings.contentGap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
            Column {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sizeText.isNotBlank()) {
                    Text(
                        text = sizeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

/** 格式化文件大小为可读字符串。 */
private fun formatFileSize(bytes: Long): String {
    // L-AC3: 用 1024 进制(二进制)匹配 KB/MB 标签语义,旧实现误用 1000 进制却显示 KB/MB。
    return when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

/** 根据扩展名返回 MIME 类型。 */
private fun getMimeType(ext: String): String {
    return when (ext) {
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "csv" -> "text/csv"
        "html", "htm" -> "text/html"
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "*/*"
    }
}

/** 通过 FileProvider 拿到可被其它应用读取的 content Uri。 */
private fun getFileUri(context: android.content.Context, file: java.io.File): Uri {
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
}
