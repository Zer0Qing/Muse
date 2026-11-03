package io.zer0.muse.data.sticker

/**
 * 从 Office 文档（docx / pptx）里按**阅读顺序**取图与配文（表情包重做 P1）。
 *
 * 样本结构很规整：`图片 → 图床链接 + 中文描述 → 图片 → …`，第一段是包名。
 * 这个顺序必须保住 —— 用户关心的不是"导进来了 22 张图"，而是
 * **"这张图配的说明有没有跟对"**：一旦错位，名字全乱，比不导更糟。
 *
 * 所以做法是：读正文 XML，按字节位置同时收"图片引用"和"文字段"，
 * 图片开启一个候选，其后的文字成为它的配文，直到下一张图出现。
 *
 * 不依赖 Android：输入是已经读进内存的条目表（调用方负责用限量方式从 zip 里读），
 * 因此这一段能在 JVM 上直接测。
 */
object StickerDocumentScanner {

    /** 一张候选图：包内媒体路径、配文、图床直链、文档中的序号。 */
    data class Candidate(
        val mediaPath: String,
        val caption: String?,
        val sourceUrl: String?,
        val order: Int,
    )

    /** 扫描结果：包名（第一段文字）+ 按顺序的候选图。 */
    data class Scanned(val title: String?, val candidates: List<Candidate>)

    private val REL_ID_PATTERN = Regex("Id=\"([^\"]+)\"[^>]*Target=\"([^\"]+)\"")
    private val SLIDE_NUMBER = Regex("""(\d+)\.xml${'$'}""")
    private val IMAGE_REF = Regex("""r:(?:embed|id)="([^"]+)"""")
    private val TEXT_RUN = Regex("""<(?:w|a):t(?:\s[^>]*)?>([^<]*)</(?:w|a):t>""")

    /** 候选数量上限：素材包不会超过这个量级，超出来一般是解析走偏了。 */
    const val MAX_CANDIDATES = 300

    /** 单条媒体文件上限（8MB）：表情图不可能更大，大了就是解析错了。 */
    const val MAX_MEDIA_BYTES = 8 * 1024 * 1024

    fun scan(kind: StickerSourceKind, entries: Map<String, ByteArray>): Scanned {
        val parts = partsFor(kind, entries)
        val candidates = mutableListOf<Candidate>()
        var title: String? = null
        parts.forEach { (partPath, relsPath) ->
            val xml = entries.decode(partPath) ?: return@forEach
            val rels = entries.decode(relsPath).orEmpty()
            val targets = parseRels(rels, partPath)
            val flow = walk(xml, targets)
            if (title == null) title = flow.title
            flow.candidates.forEach { candidates += it.copy(order = candidates.size) }
        }
        return Scanned(title = title, candidates = candidates.take(MAX_CANDIDATES))
    }

    /** 正文部件与它的 rels 部件（pptx 有多张 slide，按名字里的编号排）。 */
    private fun partsFor(kind: StickerSourceKind, entries: Map<String, ByteArray>): List<Pair<String, String>> =
        when (kind) {
            StickerSourceKind.DOCX -> listOf("word/document.xml" to "word/_rels/document.xml.rels")
            StickerSourceKind.PPTX -> entries.keys
                .filter { it.lowercase().startsWith("ppt/slides/slide") && it.endsWith(".xml") }
                .sortedBy { SLIDE_NUMBER.find(it)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE }
                .map { it to "ppt/slides/_rels/${it.substringAfterLast('/')}.rels" }
            else -> emptyList()
        }

    /** rId → 包内媒体路径。Target 可能是相对路径，按正文部件所在目录解析。 */
    private fun parseRels(rels: String, partPath: String): Map<String, String> {
        val base = partPath.substringBeforeLast('/', "")
        val result = mutableMapOf<String, String>()
        REL_ID_PATTERN.findAll(rels).forEach { match ->
            val (id, rawTarget) = match.destructured
            if (rawTarget.startsWith("http")) return@forEach
            val cleaned = rawTarget.removePrefix("/")
            val path = if (rawTarget.startsWith("/")) cleaned else normalizeZipPath("$base/$cleaned")
            result[id] = path
        }
        return result
    }

    /** 一次线性扫描：把图片引用与文字段按位置串起来。 */
    private fun walk(xml: String, targets: Map<String, String>): FlowResult {
        val tokens = tokensOf(xml, targets)
        val candidates = mutableListOf<Candidate>()
        val leading = StringBuilder()
        var open: Candidate? = null
        tokens.forEach { token ->
            when (token) {
                is Token.Image -> {
                    open?.let { candidates += it.finish() }
                    open = Candidate(mediaPath = token.path, caption = null, sourceUrl = null, order = 0)
                }
                is Token.Text -> if (token.value.isNotBlank()) {
                    val current = open
                    if (current == null) leading.append(token.value).append(' ')
                    else open = current.copy(caption = mergeCaption(current.caption, token.value))
                }
            }
        }
        open?.let { candidates += it.finish() }
        return FlowResult(title = leading.toString().trim().takeIf { it.isNotEmpty() }, candidates = candidates)
    }

    /** 配文里通常夹着图床链接：链接单独存，剩下的做配文。 */
    private fun mergeCaption(existing: String?, text: String): String {
        val joined = if (existing.isNullOrBlank()) text else "$existing $text"
        return joined.trim()
    }

    private fun Candidate.finish(): Candidate {
        val urls = StickerUrlList.harvest(caption.orEmpty())
        val url = urls.firstOrNull()
        val caption = caption
            // 复用同一条 ASCII 正则：这里再写非空白字符集会把中文配文一起删掉
            ?.replace(StickerUrlList.URL_PATTERN, " ")
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return copy(caption = caption, sourceUrl = url)
    }

    private sealed interface Token {
        data class Image(val path: String) : Token
        data class Text(val value: String) : Token
    }

    private class FlowResult(val title: String?, val candidates: List<Candidate>)

    /** 收集"图片引用 / 文字段"两类标记，按它们在 XML 里出现的位置排序。 */
    private fun tokensOf(xml: String, targets: Map<String, String>): List<Token> {
        val found = mutableListOf<Pair<Int, Token>>()
        IMAGE_REF.findAll(xml).forEach { match ->
            targets[match.groupValues[1]]?.let { found += match.range.first to Token.Image(it) }
        }
        TEXT_RUN.findAll(xml).forEach { match ->
            val text = unescape(match.groupValues[1])
            if (text.isNotBlank()) found += match.range.first to Token.Text(text)
        }
        return found.sortedBy { it.first }.map { it.second }
    }

    private fun unescape(text: String): String = text
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    private fun Map<String, ByteArray>.decode(path: String): String? =
        this[path]?.toString(Charsets.UTF_8) ?: entriesIgnoreCase(this, path)?.toString(Charsets.UTF_8)

    private fun entriesIgnoreCase(entries: Map<String, ByteArray>, path: String): ByteArray? {
        val lowered = path.lowercase()
        return entries.entries.firstOrNull { it.key.lowercase() == lowered }?.value
    }

    /** 消掉 `..` 与 `./`，避免 rels 里写出包外路径。 */
    internal fun normalizeZipPath(path: String): String {
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        val stack = mutableListOf<String>()
        parts.forEach { part ->
            when {
                part == ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                else -> stack += part
            }
        }
        return stack.joinToString("/")
    }
}
