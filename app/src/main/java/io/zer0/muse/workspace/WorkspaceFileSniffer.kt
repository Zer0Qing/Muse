package io.zer0.muse.workspace

/**
 * 工作区文件的**内容类型**（工作区媒体「只能看见文件名」修复的第一层）。
 *
 * 为什么按内容判，而不是按扩展名判：
 *  - 扩展名是用户随手起的，`report.pdf` 改名成 `report.txt` 就会进文本分支，
 *    UTF-8 解码二进制把整页变成 U+FFFD，模型还会收到「后面还有内容」的续读提示，
 *    一轮一轮翻页烧 token，拿到的信息量是零；
 *  - 反过来，工具产物里大量没有后缀的 PNG（`shot_1699999999` 这种）也会被当成文本读坏。
 *
 * 所以：**内容嗅探是唯一事实来源**，扩展名只用于界面分流与 MIME 补充。
 * [WorkspaceManager] 的读、编辑与 grep 都走这里，不再各留一份判定
 * （原先 grep 有 NUL 检测、读没有，就是同一条规则写了两遍、其中一遍漏了）。
 */
enum class WorkspaceFileKind {
    /** 可以按 UTF-8 读进上下文。 */
    TEXT,

    /** 位图：要交给视觉通路或图片预览，不能当文本读。 */
    IMAGE,

    /** 音频：要转写或播放器。 */
    AUDIO,

    /** 视频。 */
    VIDEO,

    /** 压缩包与包格式（zip/docx/apk/tar 等）。 */
    ARCHIVE,

    /** 版式文档（PDF）。界面有专门的 pdf.js 预览。 */
    DOCUMENT,

    /** 可执行体。 */
    EXECUTABLE,

    /** 认不出的二进制。 */
    BINARY,
}

/**
 * 一次嗅探的结果。
 *
 * @param kind 内容类型
 * @param mimeType 猜出的 MIME；猜不出时空串
 * @param note 给用户/模型看的补充，例如 UTF-16 文本按 UTF-8 读会乱码
 */
data class Sniffed(
    val kind: WorkspaceFileKind,
    val mimeType: String = "",
    val note: String? = null,
)

/**
 * 只读文件头若干字节做判定。
 *
 * 设计约束：
 *  - **纯函数**，不碰 Android API，可在 JVM 单测里逐条钉住规则；
 *  - 只看头部至多 [HEAD_BYTES] 字节，判定不依赖整份文件；
 *  - 判不准时宁可归到 [WorkspaceFileKind.BINARY]（读会明确拒绝，用户立刻发现），
 *    也不要归到 [WorkspaceFileKind.TEXT]（静默喂乱码给模型，没人会发现）。
 *
 * 函数数量偏多是这张判定表的必然结果：规则本身有三十来条，
 * 按魔数家族拆（图像 / 容器 / 文档与可执行体）比塞进一个 `when` 更好读，
 * 也比给每个分支再抽一个函数更容易核对覆盖范围。
 */
@Suppress("TooManyFunctions") // 内容判定表：按家族分组的小函数，拆开是为了能逐条核对
object WorkspaceFileSniffer {

    /** 判定读取的头部字节数：够覆盖所有魔数、tar 的 ustar 偏移与 UTF-16 抽样。 */
    const val HEAD_BYTES: Int = 8 * 1024

    /** 头部里不可打印字符占比超过这个值，判二进制。 */
    private const val CONTROL_RATIO_BINARY = 0.30

    /** UTF-8 解码替换字符占比超过这个值，判二进制。 */
    private const val REPLACEMENT_RATIO_BINARY = 0.05

    /** 文本里允许出现的控制字符（制表、换行、回车、垂直制表、换页）。 */
    private val ALLOWED_CONTROL = intArrayOf('\t'.code, '\n'.code, '\r'.code, 0x0B, 0x0C)

    /** MP4/M4A 家族里属于音轨的 brand。 */
    private val AUDIO_BRANDS = setOf("M4A ", "M4B ", "m4a ", "mp4a", "M4P ")

    /** 走 ISO-BMFF 容器的图片 brand。 */
    private val IMAGE_BRANDS = setOf("mif1", "msf1", "heic", "heix", "hevc", "hevx", "avif", "avis")

    private val TEXT_PLAIN = Sniffed(WorkspaceFileKind.TEXT, "text/plain")

    /**
     * 嗅探文件头。
     *
     * @param head 文件开头至多 [HEAD_BYTES] 字节；空数组按空文件处理
     * @param sizeBytes 文件总字节数，只用于区分「空文件」与「头部即二进制」
     */
    fun sniff(head: ByteArray, sizeBytes: Long): Sniffed {
        if (sizeBytes == 0L || head.isEmpty()) return TEXT_PLAIN
        // BOM 先于魔数：UTF-16/32 的文本一定以 BOM 开头，让它们走文本分支并带上说明。
        bom(head)?.let { return it }
        return magic(head) ?: byContent(head)
    }

    /** 按扩展名猜 MIME；只在魔数没命中时给界面和提示用。 */
    fun guessMime(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return EXTENSIONS[ext].orEmpty()
    }

    /** 按扩展名猜类型（界面分流用；判定仍以 [sniff] 为准）。 */
    fun guessKind(fileName: String): WorkspaceFileKind = kindOfMime(guessMime(fileName))

    private fun kindOfMime(mime: String): WorkspaceFileKind = when {
        mime.isEmpty() -> WorkspaceFileKind.TEXT
        mime.startsWith("image/") -> WorkspaceFileKind.IMAGE
        mime.startsWith("audio/") -> WorkspaceFileKind.AUDIO
        mime.startsWith("video/") -> WorkspaceFileKind.VIDEO
        mime == "application/pdf" -> WorkspaceFileKind.DOCUMENT
        mime.startsWith("application/zip") || mime.endsWith("tar") ||
            mime.endsWith("gzip") || mime.endsWith("x-7z-compressed") ||
            mime.endsWith("x-rar-compressed") || mime.endsWith("x-archive") -> WorkspaceFileKind.ARCHIVE

        mime.endsWith("x-executable") || mime.endsWith("x-dosexec") ||
            mime.endsWith("package-archive") || mime.endsWith("android.dex") -> WorkspaceFileKind.EXECUTABLE

        else -> WorkspaceFileKind.TEXT
    }

    // ── 魔数：按家族分三张表 ────────────────────────────────────────────

    private fun magic(h: ByteArray): Sniffed? = imageMagic(h) ?: mediaMagic(h) ?: docAndExecMagic(h) ?: ftypMagic(h)

    /** 位图。 */
    private fun imageMagic(h: ByteArray): Sniffed? = when {
        h starts bytes(0xFF, 0xD8, 0xFF) -> Sniffed(WorkspaceFileKind.IMAGE, "image/jpeg")

        h starts bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) ->
            Sniffed(WorkspaceFileKind.IMAGE, "image/png")

        h starts "GIF87a".toByteArray() || h starts "GIF89a".toByteArray() ->
            Sniffed(WorkspaceFileKind.IMAGE, "image/gif")

        h starts "BM".toByteArray() && h.size >= 14 -> Sniffed(WorkspaceFileKind.IMAGE, "image/bmp")
        isRiffWebp(h) -> Sniffed(WorkspaceFileKind.IMAGE, "image/webp")

        // ICO 前 4 字节是 00 00 01 00；再要求长度，避免撞上短文件的 4 字节巧合。
        h starts bytes(0x00, 0x00, 0x01, 0x00) && h.size > 6 ->
            Sniffed(WorkspaceFileKind.IMAGE, "image/x-icon")

        else -> null
    }

    /** 音视频容器。 */
    private fun mediaMagic(h: ByteArray): Sniffed? = when {
        // RIFF 容器要按第 9..12 字节分流派：WEBP 是图，WAVE/AIFF 是音。
        // 只写 WEBP 的话，wav 会落到后面的 NUL 判定里变成「未知二进制」，
        // 而 wav 头部带 NUL 是常态。
        isRiffWave(h) -> Sniffed(WorkspaceFileKind.AUDIO, "audio/wav")
        isRiffAiff(h) -> Sniffed(WorkspaceFileKind.AUDIO, "audio/aiff")
        h starts "OggS".toByteArray() -> Sniffed(WorkspaceFileKind.AUDIO, "audio/ogg")
        h starts "fLaC".toByteArray() -> Sniffed(WorkspaceFileKind.AUDIO, "audio/flac")
        h starts "#!AMR".toByteArray() -> Sniffed(WorkspaceFileKind.AUDIO, "audio/amr")
        h starts "ID3".toByteArray() -> Sniffed(WorkspaceFileKind.AUDIO, "audio/mpeg")
        isAdts(h) -> Sniffed(WorkspaceFileKind.AUDIO, "audio/aac")
        isMp3FrameSync(h) -> Sniffed(WorkspaceFileKind.AUDIO, "audio/mpeg")

        else -> null
    }

    /**
     * ISO-BMFF（ftyp）家族单独一张表：同一个容器能装视频、音频与图片，
     * 派系全写在 brand 里。归到一处判，不要在图像与音频两张表里各拼一次下标。
     */
    private fun ftypMagic(h: ByteArray): Sniffed? {
        if (!isFtyp(h)) return null
        val brand = String(h, 8, 4, Charsets.US_ASCII)
        return when {
            brand in IMAGE_BRANDS -> Sniffed(WorkspaceFileKind.IMAGE, imageBrandMime(brand))
            brand in AUDIO_BRANDS -> Sniffed(WorkspaceFileKind.AUDIO, "audio/mp4")
            else -> Sniffed(WorkspaceFileKind.VIDEO, "video/mp4")
        }
    }

    /** 版式文档、压缩包与可执行体。 */
    private fun docAndExecMagic(h: ByteArray): Sniffed? = when {
        h starts "%PDF-".toByteArray() -> Sniffed(WorkspaceFileKind.DOCUMENT, "application/pdf")

        h starts bytes(0x50, 0x4B, 0x03, 0x04) || h starts bytes(0x50, 0x4B, 0x05, 0x06) ||
            h starts bytes(0x50, 0x4B, 0x07, 0x08) -> Sniffed(WorkspaceFileKind.ARCHIVE, "application/zip")

        h starts bytes(0x1F, 0x8B) -> Sniffed(WorkspaceFileKind.ARCHIVE, "application/gzip")

        h starts bytes(0x37, 0x7A, 0xBC, 0xAF, 0x1C, 0xA8) ->
            Sniffed(WorkspaceFileKind.ARCHIVE, "application/x-7z-compressed")

        h starts "Rar!\u001A\u0007".toByteArray() ->
            Sniffed(WorkspaceFileKind.ARCHIVE, "application/x-rar-compressed")

        h.size >= 262 && String(h, 257, 5, Charsets.US_ASCII) == "ustar" ->
            Sniffed(WorkspaceFileKind.ARCHIVE, "application/x-tar")

        h.size >= 8 && String(h, 0, 8, Charsets.US_ASCII) == "!<arch>\n" ->
            Sniffed(WorkspaceFileKind.ARCHIVE, "application/x-archive")

        // 可执行体单独归类：预览必然没意义，直接拒比「未知二进制」更明确。
        h starts bytes(0x7F, 0x45, 0x4C, 0x46) -> Sniffed(WorkspaceFileKind.EXECUTABLE, "application/x-executable")
        h starts "MZ".toByteArray() && h.size > 64 -> Sniffed(WorkspaceFileKind.EXECUTABLE, "application/x-dosexec")
        h starts "dex\n".toByteArray() -> Sniffed(WorkspaceFileKind.EXECUTABLE, "application/vnd.android.dex")

        else -> null
    }

    /** 把十六进制字面量收成 Byte，省掉逐个 `0x89.toByte()` 的写法。 */
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    /** RIFF 家族的通用判据：前 4 字节 RIFF，第 9..12 字节是派系名。 */
    private fun riffTailIs(h: ByteArray, form: String): Boolean = h.size >= 12 && String(h, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(h, 8, 4, Charsets.US_ASCII) == form

    private fun isRiffWebp(h: ByteArray): Boolean = riffTailIs(h, "WEBP")

    private fun isRiffWave(h: ByteArray): Boolean = riffTailIs(h, "WAVE") || riffTailIs(h, "wave")

    private fun isRiffAiff(h: ByteArray): Boolean = riffTailIs(h, "AIFF") || riffTailIs(h, "AIFC") || riffTailIs(h, "16AF")

    /** ISO-BMFF 容器（MP4/M4A/HEIF/AVIF）：第 5..8 字节是 "ftyp"。 */
    private fun isFtyp(h: ByteArray): Boolean = h.size >= 12 && String(h, 4, 4, Charsets.US_ASCII) == "ftyp"

    /** HEIF/AVIF 归到说得出口的 MIME，不把 brand 字面量直接拼进子类型。 */
    private fun imageBrandMime(brand: String): String = if (brand == "avif" || brand == "avis") "image/avif" else "image/heif"

    /** ADTS AAC：0xFF 开头，第二字节高 4 位全 1 且帧头位置位。 */
    private fun isAdts(h: ByteArray): Boolean = h.size >= 2 && h[0] == 0xFF.toByte() && (h[1].toInt() and 0xF6) == 0xF0

    /** 无 ID3 头的裸 MP3：11 位帧同步（FF Ex）。 */
    private fun isMp3FrameSync(h: ByteArray): Boolean = h.size >= 4 && h[0] == 0xFF.toByte() && (h[1].toInt() and 0xE0) == 0xE0

    // ── 文本性判定 ──────────────────────────────────────────────────────

    /** 没有任何魔数命中时的兜底：按字节内容判文本还是二进制。 */
    private fun byContent(h: ByteArray): Sniffed {
        val binary = h.indexOf(0.toByte()) >= 0 ||
            controlRatio(h) > CONTROL_RATIO_BINARY ||
            replacementRatio(h) > REPLACEMENT_RATIO_BINARY
        return if (binary) Sniffed(WorkspaceFileKind.BINARY) else TEXT_PLAIN
    }

    private fun bom(h: ByteArray): Sniffed? = when {
        // UTF-16LE 的 BOM 是 UTF-32LE BOM 的前缀，必须先判长的。
        h starts bytes(0xFF, 0xFE, 0x00, 0x00) -> charsetSniff("utf-32")
        h starts bytes(0x00, 0x00, 0xFE, 0xFF) -> charsetSniff("utf-32be")
        h starts bytes(0xFF, 0xFE) || h starts bytes(0xFE, 0xFF) -> charsetSniff("utf-16")
        h starts bytes(0xEF, 0xBB, 0xBF) -> Sniffed(WorkspaceFileKind.TEXT, "text/plain; charset=utf-8-bom")
        else -> null
    }

    private fun charsetSniff(charset: String): Sniffed = Sniffed(
        WorkspaceFileKind.TEXT,
        "text/plain; charset=$charset",
        note = "该文件是 ${charset.uppercase()} 编码，按 UTF-8 读取会出现乱码；建议先转成 UTF-8",
    )

    private fun controlRatio(h: ByteArray): Double {
        if (h.isEmpty()) return 0.0
        val bad = h.count { b ->
            val i = b.toInt() and 0xFF
            i < 0x20 && i !in ALLOWED_CONTROL
        }
        return bad.toDouble() / h.size
    }

    /**
     * UTF-8 解码替换字符占比。
     *
     * 只丢掉**解码结果末尾那一个** U+FFFD：头部是按字节切的，唯一能合法产生残缺
     * 序列的位置就是末尾。以前这里固定砍掉输入的最后 3 字节，恰好遇到「多字节汉字 +
     * \n」结尾的文件时，砍完反而把一个汉字腰斩了——正常中文正文被误判成二进制
     * （WorkspaceEditToolTest 当场拓出来了）。
     */
    private fun replacementRatio(h: ByteArray): Double {
        val text = String(h, Charsets.UTF_8)
        if (text.isEmpty()) return 0.0
        val body = if (text.endsWith('\uFFFD')) text.substring(0, text.length - 1) else text
        if (body.isEmpty()) return 0.0
        return body.count { it == '\uFFFD' }.toDouble() / body.length
    }

    /** 常用扩展名 MIME 表；只在魔数没命中时作为补充，界面分流也用它。 */
    private val EXTENSIONS: Map<String, String> = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
        "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp",
        "ico" to "image/x-icon", "heic" to "image/heic", "heif" to "image/heif",
        "avif" to "image/avif", "svg" to "image/svg+xml",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac",
        "ogg" to "audio/ogg", "opus" to "audio/opus", "wav" to "audio/wav",
        "flac" to "audio/flac", "amr" to "audio/amr",
        "mp4" to "video/mp4", "mov" to "video/quicktime", "mkv" to "video/x-matroska",
        "webm" to "video/webm", "avi" to "video/x-msvideo",
        "pdf" to "application/pdf",
        "zip" to "application/zip", "apk" to "application/vnd.android.package-archive",
        "tar" to "application/x-tar", "gz" to "application/gzip", "7z" to "application/x-7z-compressed",
        "rar" to "application/x-rar-compressed", "docx" to "application/zip",
        "xlsx" to "application/zip", "pptx" to "application/zip",
        "so" to "application/x-executable", "exe" to "application/x-dosexec",
        "dex" to "application/vnd.android.dex",
        "txt" to "text/plain", "md" to "text/markdown", "json" to "application/json",
        "html" to "text/html", "htm" to "text/html", "xml" to "text/xml", "csv" to "text/csv",
    )

    /** Kotlin 的 ByteArray 没有 startsWith，这里给一个 `a starts b` 的读法。 */
    private infix fun ByteArray.starts(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }
}
