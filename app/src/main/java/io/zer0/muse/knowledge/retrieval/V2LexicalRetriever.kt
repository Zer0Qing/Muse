package io.zer0.muse.knowledge.retrieval

import io.zer0.muse.data.knowledge.KnowledgeJobDao

/**
 * R6 V2 词法召回实现 — 在活动代范围内做词法匹配与排名。
 *
 * 当前实现(R6 合同层):
 *  - 通过 `knowledge_v2_gen_lexical`(R4 已建)取候选,词法文本已规范化(小写+空白折叠);
 *  - 查询也按同一规范化版本处理,索引/查询口径一致(计划 R6 步骤 2);
 *  - 按**词项命中次数**打分,同分按 chunkIndex 稳定排序;
 *  - 候选预算 = limit * oversample,达到上限标记 incomplete(不伪装完整 topK)。
 *
 * 真实 FTS4 MATCH/matchinfo 后端在 Android 真机的验证与切换归 R10(计划 R0 已冻结
 * Robolectric 无 FTS4 vtable 的边界);本类不把 FTS5 bm25 套在 FTS4 上。
 */
class V2LexicalRetriever(
    private val dao: KnowledgeJobDao,
    private val oversample: Int = 4,
) : LexicalRetriever {

    override suspend fun search(
        request: KnowledgeSearchRequest,
        activeGenerationIds: List<String>,
        limit: Int,
    ): LexicalRecall {
        if (activeGenerationIds.isEmpty()) {
            return LexicalRecall(hits = emptyList(), degradedReason = null)
        }
        val terms = tokenize(request.query)
        if (terms.isEmpty()) {
            return LexicalRecall(hits = emptyList())
        }
        val candidateBudget = (limit * oversample).coerceAtLeast(limit)
        val rowsById = LinkedHashMap<String, io.zer0.muse.data.knowledge.KnowledgeLexicalRow>()
        var incomplete = false
        try {
            // 逐词项在 SQL 侧过滤后再 LIMIT;合并去重(同一 chunk 命中多个词项)。
            for (term in terms) {
                val rows = dao.searchLexicalRows(activeGenerationIds, escapeLike(term), candidateBudget)
                if (rows.size >= candidateBudget) incomplete = true
                for (row in rows) {
                    rowsById.putIfAbsent("${row.generationId}#${row.chunkIndex}", row)
                }
            }
        } catch (e: Exception) {
            return LexicalRecall(
                hits = emptyList(),
                degradedReason = "词法索引不可用: ${e.message}",
            )
        }

        val scored = rowsById.values.mapNotNull { row ->
            val lexical = row.lexicalText
            if (lexical.isBlank()) return@mapNotNull null
            var score = 0f
            for (term in terms) {
                var index = lexical.indexOf(term)
                while (index >= 0) {
                    score += 1f
                    index = lexical.indexOf(term, index + term.length)
                }
            }
            if (score <= 0f) null
            else LexicalHit(
                chunkId = "${row.generationId}#${row.chunkIndex}",
                docId = row.docId,
                generationId = row.generationId,
                text = row.content,
                locator = row.locator,
                score = score,
                rank = 0,
            )
        }
            .sortedWith(compareByDescending<LexicalHit> { it.score }.thenBy { it.chunkId })
            .take(candidateBudget)
            .mapIndexed { index, hit -> hit.copy(rank = index) }

        return LexicalRecall(hits = scored, incomplete = incomplete)
    }

    companion object {
        /** 词法规范化版本 — 与 GenerationPublisher.normalizeLexical 同一口径(小写+空白折叠)。 */
        const val NORMALIZATION_VERSION = "v1-lowercase-collapse"

        fun tokenize(query: String): List<String> =
            query.lowercase()
                .replace(Regex("\\s+"), " ")
                .trim()
                .split(' ')
                .filter { it.isNotBlank() }

        /** LIKE 通配符转义:用户输入中的 % / _ / \ 按字面匹配(参数化查询不涉及注入)。 */
        fun escapeLike(term: String): String =
            term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}
