package io.zer0.muse.ui

import io.zer0.common.FileSizeFormatter

/** 把字节数格式化为人类可读的文件大小(B / KB / MB)。 */
object DebugFormatters {
    fun formatFileSize(bytes: Long): String = FileSizeFormatter.format(bytes)
}
