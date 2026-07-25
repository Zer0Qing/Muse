package io.zer0.muse.util

import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * v1.131: 统一 ZipInputStream 限量读取工具。
 *
 * 原先 [readEntryWithLimit] / [readLimitedBytes] 模式散落在 4 个文件:
 *  - data/import/ThirdPartyImporter.kt
 *  - data/assistant/AssistantCardExporter.kt
 *  - data/sticker/StickerLibraryRepository.kt
 *  - doc/DocumentParser.kt (特殊:按 entryName 查找,保留独立实现)
 *
 * 防 ZIP 炸弹:读取过程中累计字节数,超过 [maxBytes] 抛 [IOException] 中止读取。
 *
 * 注意:[ZipInputStream] 的 [ZipInputStream.read] 在到达 entry 末尾时返回 -1,
 * 而非抛异常,因此循环退出条件是 `read <= 0`(包含 -1)。
 *
 * @param zis 已调用 [ZipInputStream.getNextEntry] 并定位到目标 entry 的 ZipInputStream
 * @param maxBytes 单个 entry 最大解压字节数,超出抛 [IOException]
 * @param entryName 仅供错误信息展示,可为 null
 * @return 当前 entry 的完整字节数组
 * @throws IOException 解压大小超过 [maxBytes]
 */
fun readZipEntryWithLimit(
    zis: ZipInputStream,
    maxBytes: Long,
    entryName: String? = null,
): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
        val read = zis.read(buf)
        if (read <= 0) break
        total += read
        if (total > maxBytes) {
            val label = entryName?.let { "条目 '$it' " } ?: "条目 "
            throw IOException(
                "${label}解压大小超过限制(${maxBytes / 1024 / 1024}MB),可能是恶意压缩包或 ZIP 炸弹"
            )
        }
        out.write(buf, 0, read)
    }
    return out.toByteArray()
}
