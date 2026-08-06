package io.zer0.muse.ui

/** 把字节数格式化为人类可读的文件大小(B / KB / MB)。 */
object DebugFormatters {
    fun formatFileSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            bytes < 1024 -> "$bytes B"
            kb < 1024 -> "%.1f KB".format(kb)
            else -> "%.1f MB".format(mb)
        }
    }
}
