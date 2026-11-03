package io.zer0.muse.data.sticker

import io.zer0.common.Logger
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 把 Office 容器（docx / pptx）读成"条目名 → 字节"的表（表情包重做 P2）。
 *
 * 素材包是**别人给的文件**，用户随手选中的东西不能把应用撑爆，所以三重限制都要有：
 * 单条目、累计媒体、条目数（与压缩包导入那套限制同源）。
 *
 * 一趟流式读完，不能回头：内容 Uri 给的流不一定可 seek，
 * 所以结构文件（document.xml / rels）与媒体文件必须在同一趟里一起收走。
 */
object StickerContainerReader {

    private const val TAG = "StickerContainerReader"

    /** 单条目上限（10MB）：表情图不可能更大。 */
    const val MAX_ENTRY_BYTES = 10L * 1024 * 1024

    /** 累计媒体上限（64MB）。 */
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    /** 条目数上限：超过就是包本身有问题。 */
    const val MAX_ENTRIES = 4000

    /** 读取结果。 */
    class Container(val kind: StickerSourceKind, val entries: Map<String, ByteArray>)

    /** @return 容器；不是 docx/pptx 时返回 null，调用方据此回落到普通 zip 路径 */
    fun read(stream: InputStream): Container? {
        val collected = ZipInputStream(stream).use { it.drain() }
        if (collected.oversized > 0) Logger.w(TAG, "跳过 ${collected.oversized} 个超单条目上限的文件")
        val kind = StickerImportFormat.refineOfficeContainer(collected.entries.keys.toList())
        return if (kind == StickerSourceKind.DOCX || kind == StickerSourceKind.PPTX) {
            Container(kind, collected.entries)
        } else {
            null
        }
    }

    /** 一趟读完所有值得留的条目。用状态位控制停止，而不是在循环里塞 break/continue。 */
    private fun ZipInputStream.drain(): Collected {
        val collected = Collected()
        var entry = nextEntry
        while (entry != null && !collected.stopped) {
            val name = entry.name
            if (!entry.isDirectory && isWorthKeeping(name)) collected.absorb(name, readBytesLimited())
            closeEntry()
            entry = nextEntry
        }
        return collected
    }

    /** 收集中的条目与两个计数（超上限的条目数、是否已经收够）。 */
    private class Collected {
        val entries = LinkedHashMap<String, ByteArray>()
        var oversized = 0
        private var mediaBytes = 0L

        /** 条目数或累计媒体超上限就停止收 —— 但已收到的仍然有效，不让一个坏包变成全失败。 */
        var stopped = false
            private set

        fun absorb(name: String, bytes: ByteArray?) {
            if (bytes == null) {
                oversized++
            } else {
                if (isMedia(name)) mediaBytes += bytes.size
                entries[name] = bytes
            }
            if (entries.size >= MAX_ENTRIES || mediaBytes > MAX_TOTAL_BYTES) {
                if (!stopped) Logger.w(TAG, "容器读取到上限提前停止（条目 ${entries.size}，媒体 $mediaBytes 字节）")
                stopped = true
            }
        }
    }

    private fun isMedia(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("word/media/") || lower.startsWith("ppt/media/")
    }

    /** 只收结构文件与媒体：主题/字体/设置那些 XML 对导入没用。 */
    private fun isWorthKeeping(name: String): Boolean {
        val lower = name.lowercase()
        return isMedia(lower) ||
            lower == "word/document.xml" ||
            lower == "word/_rels/document.xml.rels" ||
            (lower.startsWith("ppt/slides/slide") && lower.endsWith(".xml")) ||
            (lower.startsWith("ppt/slides/_rels/") && lower.endsWith(".rels"))
    }

    /**
     * 带上限地读完当前条目。
     *
     * 超限返回 null 而不是抛异常：一张 30MB 的原画稿不该让整份文档导不进来；
     * 剩下的字节交给 closeEntry 跳过，不影响下一个条目。
     */
    private fun ZipInputStream.readBytesLimited(): ByteArray? {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var total = 0L
        var read = read(chunk)
        while (read >= 0) {
            total += read
            if (total > MAX_ENTRY_BYTES) return null
            out.write(chunk, 0, read)
            read = read(chunk)
        }
        return out.toByteArray()
    }
}
