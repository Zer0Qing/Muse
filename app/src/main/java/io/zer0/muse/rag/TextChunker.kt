package io.zer0.muse.rag

/**
 * v1.54: 文本递归分块器。
 *
 * v1.133 扩展:
 *  - [markdownAware]:识别 ``` 代码块 / Markdown 标题层级 / 表格行,整体保留不切断
 *  - [chunkByToken]:按 token 数分块(替代字符数,中英文混合更精确)
 *
 * 分块策略(三级递归):
 * 1. 按段落分(双换行 \n\n 分割)
 * 2. 段落超长时按句子分(中文句号/问号/叹号 + 英文 .!?)
 * 3. 句子仍超长时按字符硬切
 * 4. 合并相邻小块到接近 [targetSize]
 * 5. 相邻块之间保留 [overlap] 字符重叠(语义连续性)
 *
 * @param targetSize 目标块大小([chunkByToken]=true 时为 token 数,否则字符数,默认 500)
 * @param overlap 相邻块重叠(同 targetSize 单位,默认 50)
 * @param markdownAware v1.133: 是否感知 Markdown 结构(代码块/标题/表格整体保留)
 * @param chunkByToken v1.133: 是否按 token 数分块(true 时 targetSize 单位为 token)
 */
class TextChunker(
    private val targetSize: Int = 500,
    private val overlap: Int = 50,
    private val markdownAware: Boolean = false,
    private val chunkByToken: Boolean = false,
) {
    data class Chunk(
        val content: String,
        val index: Int,
        /** v1.133: chunk 元数据(可包含 section/page/tags 等,用于过滤)。 */
        val metadata: Map<String, String> = emptyMap(),
    )

    companion object {
        private val sentenceEnd = Regex("(?<=[。！？!?])|(?<=[.!?])\\s+")
        /** ``` 代码块边界识别(开/闭成对)。 */
        private val codeFence = Regex("^```\\w*\\s*$", RegexOption.MULTILINE)
        /** Markdown 标题行(# ~ ######)。 */
        private val mdHeader = Regex("^(#{1,6})\\s+(.+)$", RegexOption.MULTILINE)
        /** Markdown 表格行(|...|...|)。 */
        private val mdTableRow = Regex("^\\|.*\\|\\s*$", RegexOption.MULTILINE)
    }

    /**
     * 将长文本切分为多个 chunk。
     * 空文本返回空列表;短文本直接返回单块。
     */
    fun split(text: String): List<Chunk> {
        if (text.isBlank()) return emptyList()
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n").trim()

        // v1.133: Markdown-aware 模式 — 先按结构切,再按 size 切
        if (markdownAware) {
            return splitMarkdownAware(normalized)
        }

        if (!chunkByToken) {
            return splitByChars(normalized)
        }

        // v1.133: token-based 模式
        return splitByTokens(normalized)
    }

    // ── 字符数模式(原 v1.54 实现)──
    private fun splitByChars(text: String): List<Chunk> {
        if (text.length <= targetSize) return listOf(Chunk(text, 0))
        val paragraphs = text.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
        val sentences = mutableListOf<String>()
        for (para in paragraphs) {
            if (para.length <= targetSize) {
                sentences.add(para)
            } else {
                sentences.addAll(splitLongParagraph(para))
            }
        }
        return mergeWithOverlap(sentences)
    }

    /** 超长段落按句子分割(中英文标点)。 */
    private fun splitLongParagraph(para: String): List<String> {
        val result = mutableListOf<String>()
        val parts = sentenceEnd.split(para).map { it.trim() }.filter { it.isNotEmpty() }
        for (part in parts) {
            if (part.length <= targetSize) {
                result.add(part)
            } else {
                var i = 0
                while (i < part.length) {
                    val end = minOf(i + targetSize, part.length)
                    result.add(part.substring(i, end))
                    i = end
                }
            }
        }
        return result
    }

    private fun mergeWithOverlap(pieces: List<String>): List<Chunk> {
        if (pieces.isEmpty()) return emptyList()
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (piece in pieces) {
            if (current.isNotEmpty() && current.length + 1 + piece.length > targetSize) {
                chunks.add(current.toString().trim())
                val tail = current.takeLast(overlap)
                current.clear()
                current.append(tail)
                current.append(piece)
            } else {
                if (current.isNotEmpty()) current.append("\n")
                current.append(piece)
            }
        }
        if (current.isNotBlank()) chunks.add(current.toString().trim())
        return chunks.mapIndexed { idx, content -> Chunk(content, idx) }
    }

    // ── v1.133: Markdown-aware 模式 ──
    /**
     * 按结构边界(代码块/标题/表格)优先切分,保留语义完整性。
     * 切完后再按 [targetSize] 在结构边界内进一步切。
     */
    private fun splitMarkdownAware(text: String): List<Chunk> {
        // 1. 把文本按"结构单元"拆成 pieces,每个 piece 是一个不可分割的语义块
        val pieces = splitByMarkdownStructure(text)
        // 2. 按 targetSize 合并相邻 piece,保留结构边界
        return mergeMarkdownPieces(pieces)
    }

    /** 识别 Markdown 结构边界,返回结构单元列表(每个单元 = 代码块/标题段/表格/普通段落)。 */
    private fun splitByMarkdownStructure(text: String): List<MdPiece> {
        val pieces = mutableListOf<MdPiece>()
        val lines = text.split("\n")
        val current = StringBuilder()
        var currentHeader: String? = null
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            // ``` 代码块开始
            if (codeFence.matches(line)) {
                // flush 当前累积的普通文本
                if (current.isNotBlank()) {
                    pieces.add(MdPiece(current.toString().trim(), currentHeader, PieceType.TEXT))
                    current.clear()
                }
                // 收集整个代码块(包括开闭 ```
                val codeBlock = StringBuilder(line).append("\n")
                i++
                while (i < lines.size && !codeFence.matches(lines[i])) {
                    codeBlock.append(lines[i]).append("\n")
                    i++
                }
                if (i < lines.size) { // 闭合 ```
                    codeBlock.append(lines[i]).append("\n")
                    i++
                }
                pieces.add(MdPiece(codeBlock.toString().trimEnd(), currentHeader, PieceType.CODE))
                continue
            }

            // 标题行
            mdHeader.find(line)?.let { m ->
                if (current.isNotBlank()) {
                    pieces.add(MdPiece(current.toString().trim(), currentHeader, PieceType.TEXT))
                    current.clear()
                }
                currentHeader = m.groupValues[2].trim()
                pieces.add(MdPiece(line, currentHeader, PieceType.HEADER))
                i++
                continue
            }

            // 表格行(连续的 |...|...| 行作为一块)
            if (mdTableRow.matches(line)) {
                if (current.isNotBlank()) {
                    pieces.add(MdPiece(current.toString().trim(), currentHeader, PieceType.TEXT))
                    current.clear()
                }
                val table = StringBuilder(line).append("\n")
                i++
                while (i < lines.size && mdTableRow.matches(lines[i])) {
                    table.append(lines[i]).append("\n")
                    i++
                }
                pieces.add(MdPiece(table.toString().trimEnd(), currentHeader, PieceType.TABLE))
                continue
            }

            // 普通行:累积
            current.append(line).append("\n")
            i++
        }
        if (current.isNotBlank()) pieces.add(MdPiece(current.toString().trim(), currentHeader, PieceType.TEXT))

        return pieces
    }

    /** 合并 Markdown pieces 到接近 targetSize,代码块/表格整体不切断。 */
    private fun mergeMarkdownPieces(pieces: List<MdPiece>): List<Chunk> {
        if (pieces.isEmpty()) return emptyList()
        val chunks = mutableListOf<Chunk>()
        val current = StringBuilder()
        var currentHeader: String? = null
        var currentTypes = mutableListOf<PieceType>()

        fun flush() {
            if (current.isNotBlank()) {
                val content = current.toString().trimEnd()
                if (content.isNotBlank()) {
                    val meta = mutableMapOf<String, String>()
                    currentHeader?.let { meta["section"] = it }
                    chunks.add(Chunk(content, chunks.size, meta))
                }
                current.clear()
                currentTypes.clear()
            }
        }

        for (piece in pieces) {
            val pieceLen = measureSize(piece.content)
            // 代码块/表格:即使超过 targetSize 也整体保留(不再切割,保证语义完整)
            if (piece.type == PieceType.CODE || piece.type == PieceType.TABLE) {
                if (current.isNotEmpty()) flush()
                currentHeader = piece.header
                val meta = mutableMapOf<String, String>()
                piece.header?.let { meta["section"] = it }
                meta["type"] = piece.type.name.lowercase()
                chunks.add(Chunk(piece.content, chunks.size, meta))
                continue
            }

            val currentLen = measureSize(current.toString())
            if (current.isNotEmpty() && currentLen + 1 + pieceLen > targetSize) {
                flush()
                // overlap:从当前块尾部取 overlap 单位
                val tail = current.toString().takeLast(overlap.coerceAtLeast(0))
                if (tail.isNotBlank()) current.append(tail).append("\n")
            }
            if (current.isEmpty()) currentHeader = piece.header
            if (current.isNotEmpty()) current.append("\n")
            current.append(piece.content)
            currentTypes.add(piece.type)
        }
        flush()
        return chunks
    }

    private enum class PieceType { HEADER, TEXT, CODE, TABLE }
    private data class MdPiece(val content: String, val header: String?, val type: PieceType)

    // ── v1.133: token-based 模式 ──
    private fun splitByTokens(text: String): List<Chunk> {
        // 简化实现:按 token 估算累加,超过 targetSize 即切。中英文混合更精确。
        val paragraphs = text.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var currentTokens = 0

        for (para in paragraphs) {
            val paraTokens = measureSize(para)
            if (current.isNotEmpty() && currentTokens + paraTokens > targetSize) {
                chunks.add(current.toString().trim())
                // overlap:取当前块尾部约 overlap tokens 的文本
                val tail = current.toString().takeLast(estimateTokensToChars(overlap))
                current.clear()
                current.append(tail)
                currentTokens = measureSize(tail)
            }
            if (current.isNotEmpty()) current.append("\n")
            current.append(para)
            currentTokens += paraTokens
        }
        if (current.isNotBlank()) chunks.add(current.toString().trim())
        return chunks.mapIndexed { idx, content -> Chunk(content, idx) }
    }

    /** 测量文本大小:token 模式返回 token 估算,字符模式返回字符数。 */
    private fun measureSize(text: String): Int =
        if (chunkByToken) estimateTokens(text) else text.length

    /** 把 token 数粗略换算为字符数(中文 1 token≈1.5 字符,英文 1 token≈4 字符,折中取 2.5)。 */
    private fun estimateTokensToChars(tokens: Int): Int = (tokens * 2.5).toInt().coerceAtLeast(0)

    /** v1.55: 精确 token 数(基于 BPE 编码,jtokkit 初始化失败时回退到启发式估算)。 */
    fun estimateTokens(text: String): Int = io.zer0.muse.util.TokenEstimator.estimate(text).coerceAtLeast(1)
}
