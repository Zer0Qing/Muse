package io.zer0.muse.knowledge.ingest

/**
 * R3 定位 chunk — 内容 + 可解释定位(page/section)+ 结构 kind。
 * 「所有成功 chunk 有可解释定位」(计划 R3 验收)由 [locator] 非空保证。
 */
data class LocatedChunk(
    val text: String,
    /** 可解释定位:如 "p1s2"(首个贡献 block 的定位)。 */
    val locator: String,
    /** 结构 kind(heading/paragraph/code/table;继承自来源 block)。 */
    val kind: String,
)

/**
 * R3 步骤 5:结构化分块器 — 同一正文无论以何种 block 粒度输入,切分结果一致。
 *
 * 单一确定性算法(不变式由 StructuredChunkerInvariantTest 钉住):
 *  1. **归并**:按 kind 把连续同 kind block 归并为 (文本流, 定位序列) 段 —— 归并时
 *     记录每个字素所属 block 的 locator,与 block 物理切分粒度无关;
 *  2. **切割**:对每段按字素(BreakIterator 字素边界:代理对/ZWJ/国旗不拆)切
 *     [chunkSize] 大小的 chunk,相邻 chunk 间回退 [chunkOverlap] 字素(连续性);
 *  3. **定位**:chunk 的 locator = 该 chunk 首个字素所属 block 的定位。
 *
 * 窗口无关:输入是完整 block 序列;输入窗口化属于解析层资源上限(防 OOM 保护保留),
 * 本类不感知 —— 因此 1/37/4096 字符 piece 的产出等价。
 * 无损:除 overlap 造成的重复外无字符丢失;同一 kind 段内 chunk 拼接去重 overlap = 原文。
 */
class StructuredChunker(
    val chunkSize: Int,
    val chunkOverlap: Int,
) {
    init {
        require(chunkSize > 0) { "chunkSize 必须为正" }
        require(chunkOverlap >= 0 && chunkOverlap < chunkSize) { "overlap 必须在 [0, chunkSize)" }
    }

    fun chunkFromBlocks(blocks: List<ParsedBlock>): List<LocatedChunk> {        if (blocks.isEmpty()) return emptyList()

        // 1. 归并:连续同 kind block → (全文字素, 每字素的 locator) 段
        val segments = mutableListOf<Segment>()
        for (block in blocks) {
            val last = segments.lastOrNull()
            if (last != null && last.kind == block.kind) {
                last.append(block)
            } else {
                segments.add(Segment(kind = block.kind).also { it.append(block) })
            }
        }

        // 2. 切割:每段按字素切 chunkSize,相邻回退 chunkOverlap
        val chunks = mutableListOf<LocatedChunk>()
        for (seg in segments) {
            val gs = seg.graphemes
            var start = 0
            while (start < gs.size) {
                val end = minOf(start + chunkSize, gs.size)
                chunks.add(
                    LocatedChunk(
                        text = gs.subList(start, end).joinToString("") { it.first },
                        locator = gs[start].second,
                        kind = seg.kind,
                    ),
                )
                if (end >= gs.size) break
                // 下一 chunk 回退 overlap;overlap < chunkSize 由 init require 保证,
                // 回退必然严格前进(无需额外防御)。
                start = end - chunkOverlap
            }
        }
        return chunks
    }

    /**
     * 按固定 overlap 从 chunk 序列重建原文 —— 用于内容 hash 校验。
     *
     * 前提:chunks 来自本分块器对**同一连续段落**的产出(顺序一致、相邻块恰回退
     * [chunkOverlap] 个字素)。校验长文多块时直接拼接会重复计入 overlap,导致误报。
     */
    fun reconstruct(chunks: List<String>): String {
        if (chunks.isEmpty()) return ""
        val sb = StringBuilder(chunks.first())
        for (index in 1 until chunks.size) {
            val graphemes = chunks[index].graphemes()
            val drop = minOf(chunkOverlap, graphemes.size)
            sb.append(graphemes.drop(drop).joinToString(""))
        }
        return sb.toString()
    }

    private class Segment(val kind: String) {
        val graphemes = mutableListOf<Pair<String, String>>() // (字素, locator)

        fun append(block: ParsedBlock) {
            val loc = if (block.page > 0) "p${block.page}s${block.section}" else "s${block.section}"
            for (g in block.text.graphemes()) {
                graphemes.add(g to loc)
            }
        }
    }

    companion object {
        /** 字素迭代(BreakIterator 字素边界:代理对/ZWJ/国旗序列不拆)。 */
        fun String.graphemes(): List<String> {
            if (isEmpty()) return emptyList()
            val it = java.text.BreakIterator.getCharacterInstance()
            it.setText(this)
            val out = mutableListOf<String>()
            var start = it.first()
            var end = it.next()
            while (end != java.text.BreakIterator.DONE) {
                out.add(substring(start, end))
                start = end
                end = it.next()
            }
            return out
        }
    }
}
