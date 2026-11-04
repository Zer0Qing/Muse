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
        // 显式指定 Locale.US：默认区域下小数点可能是逗号（如 de-DE 的 1,5），
        // 会让 "1,5 MB" 这类文本在设置页/日志里出现两种写法，也不好解析。
        var shown = value
        var shownIndex = unitIndex
        var rounded = String.format(java.util.Locale.US, "%.${decimals}f", shown)
        // 四舍五入能把 1023.95 MB 推成 "1024.0" —— 以前会原样显示 "1024 MB"，
        // 用户看到的是"为什么不是 1 GB"。这里进位到下一级单位再格式化一次。
        while (rounded.toDouble() >= 1024.0 && shownIndex < UNITS.lastIndex) {
            shown /= 1024.0
            shownIndex++
            rounded = String.format(java.util.Locale.US, "%.${decimals}f", shown)
        }
        val trimmed = if (rounded.contains('.')) rounded.trimEnd('0').trimEnd('.') else rounded
        return "$trimmed ${UNITS[shownIndex]}"
    }
}
