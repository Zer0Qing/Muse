package io.zer0.muse.data.sticker

/**
 * 素材来源的形状（表情包重做 P1）。
 *
 * 判据只用**文件名 + 头几个字节**，不猜扩展名：用户从微信存下来的文件经常没有扩展名，
 * 或者扩展名与实际内容不一致（改了后缀的 docx、被加过 BOM 的文本）。
 *
 * ZIP 容器只判到"是个 zip"，具体是压缩包还是 Office 文档要等读到条目名才能定 ——
 * 所以下面单独给一个 [refineOfficeContainer]。
 */
enum class StickerSourceKind {
    /** 素材压缩包（既有路径）。 */
    ZIP,

    /** Word 文档：图片在 `word/media/`，正文里带配文与图床链接。 */
    DOCX,

    /** PPT：图片在 `ppt/media/`，形状类似 docx。 */
    PPTX,

    /** PDF：可能是整页格子图，也可能只有图床链接清单。 */
    PDF,

    /** 单张图片。 */
    IMAGE,

    /** 纯文本，里面有若干图片 URL。 */
    URL_LIST,

    /** 认不出来。 */
    UNKNOWN,
    ;
}

/** 认格式只需要文件名与头部字节，因此这里全部是纯函数（可 JVM 单测）。 */
object StickerImportFormat {

    /** 判定用的头部长度：够装下所有已知魔数。 */
    const val HEAD_BYTES = 32

    /** 认图片 URL 时最多看多少文本字节（再多没意义，只会拖慢）。 */
    const val TEXT_SCAN_LIMIT = 64 * 1024

    fun detect(fileName: String?, head: ByteArray): StickerSourceKind {
        if (startsWith(head, PDF_MAGIC)) return StickerSourceKind.PDF
        if (startsWith(head, ZIP_MAGIC)) return StickerSourceKind.ZIP
        if (isImageMagic(head)) return StickerSourceKind.IMAGE
        // 兜底看扩展名：有些文本文件头是纯 ASCII，只有名字能说明它是啥
        return when (fileName?.trim()?.lowercase()?.substringAfterLast('.', "")) {
            "pdf" -> StickerSourceKind.PDF
            "zip", "rar", "7z" -> StickerSourceKind.ZIP
            "png", "jpg", "jpeg", "gif", "webp", "bmp" -> StickerSourceKind.IMAGE
            else -> StickerSourceKind.UNKNOWN
        }
    }

    /**
     * ZIP 容器打开后再细分：docx / pptx / 普通素材包。
     *
     * 只认**必需部件**（`[Content_Types].xml` 与对应的 document/slide 目录），
     * 因为把 docx 当普通 zip 解会解出一堆 XML 和缩略图，用户看到的是"导进来全是乱码"。
     */
    fun refineOfficeContainer(entryNames: List<String>): StickerSourceKind {
        val lower = entryNames.map { it.lowercase() }
        return when {
            lower.any { it == "word/document.xml" } -> StickerSourceKind.DOCX
            lower.any { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") } -> StickerSourceKind.PPTX
            else -> StickerSourceKind.ZIP
        }
    }

    /** 文本里是否含图片 URL（用于把"链接清单"识别成一种可导入形态）。 */
    fun looksLikeUrlList(text: String): Boolean = StickerUrlList.harvest(text).isNotEmpty()

    /** 按魔数给出图片扩展名；不是图片返回 null。 */
    fun imageExtension(head: ByteArray): String? = when {
        startsWith(head, PNG_MAGIC) -> "png"
        startsWith(head, JPEG_MAGIC) -> "jpg"
        startsWith(head, GIF_MAGIC) -> "gif"
        startsWith(head, BMP_MAGIC) -> "bmp"
        isWebp(head) -> "webp"
        else -> null
    }

    private fun isImageMagic(head: ByteArray): Boolean =
        startsWith(head, PNG_MAGIC) || startsWith(head, JPEG_MAGIC) ||
            startsWith(head, GIF_MAGIC) || startsWith(head, BMP_MAGIC) ||
            isWebp(head)

    private fun startsWith(bytes: ByteArray, magic: ByteArray): Boolean {
        if (bytes.size < magic.size) return false
        return magic.indices.all { bytes[it] == magic[it] }
    }

    private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val GIF_MAGIC = "GIF8".toByteArray(Charsets.US_ASCII)
    private val BMP_MAGIC = byteArrayOf(0x42, 0x4D)
    private val RIFF_MAGIC = "RIFF".toByteArray(Charsets.US_ASCII)

    /** WEBP 是 `RIFF` + 4 字节长度 + `WEBP`：只看前缀会把 wav/avi 误判成图片。 */
    private fun isWebp(head: ByteArray): Boolean =
        head.size >= 12 &&
            startsWith(head, RIFF_MAGIC) &&
            head.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP"
}
