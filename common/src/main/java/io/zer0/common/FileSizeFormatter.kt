package io.zer0.common

/**
 * v2.6.7 fix (AUDIT F24): 文件大小格式化的单一实现。
 *
 * 背景：改动前全仓有 8 份各自实现的 `formatFileSize` / `formatSize` / `formatBytes`，
 * 差异包括：空格（"93.1 MB" vs "93.1MB"）、单位上限（到 GB 还是到 TB）、
 * 零值写法（"0 B" vs "0B"）。同一份数据在不同页面显示不同，
 * 用户观感像“几个应用拼起来的”。
 *
 * 口径统一为：
 *  - 1024 进制（与 KB/MB 标签语义一致）
 *  - 一位小数，整数部分不留 `.0`
 *  - 单位到 TB
 *  - 数字与单位之间一个空格
 *  - 0 或负数显示 "0 B"
 */
object FileSizeFormatter {

    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

    /** 人类可读文件大小，例如 "1.5 MB"、"512 KB"、"0 B"。 */
    fun format(bytes: Long, decimals: Int = 1): String {
        if (bytes <= 0L) return "0 B"
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < UNITS.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        // B 级不带小数；其余保留指定小数位，但去掉多余的尾随 0。
        if (unitIndex == 0) return "$bytes B"
        val rounded = String.format("%.${decimals}f", value)
        val trimmed = if (rounded.contains('.')) rounded.trimEnd('0').trimEnd('.') else rounded
        return "$trimmed ${UNITS[unitIndex]}"
    }
}
