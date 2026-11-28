package io.zer0.muse.rag

import io.zer0.common.resultOf
import io.zer0.muse.data.knowledge.KnowledgeChunkDao
import io.zer0.muse.data.knowledge.KnowledgeChunkEntity
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsDao
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsHit
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import kotlinx.coroutines.flow.first

/** A keyword-retrieved chunk with enough identity to produce a real citation. */
data class KeywordFallbackResult(
    val docId: String,
    val docTitle: String,
    val snippet: String,
    val chunkId: String = "",
    val chunkIndex: Int = 0,
    val score: Float = 0f,
    val isInternal: Boolean = false,
    val metadataJson: String = "{}",
    val createdAt: Long = 0L,
)

/** Keeps FTS and LIKE fallback behavior aligned with vector scope and metadata filters. */
object KnowledgeKeywordFallback {
    data class SearchDaos(
        val docDao: KnowledgeDocDao,
        val chunkDao: KnowledgeChunkDao,
        val ftsDao: KnowledgeChunkFtsDao,
    )

    suspend fun search(
        query: String,
        topK: Int,
        scopeDocIds: List<String>?,
        metadataFilter: VectorSearchService.MetadataFilter?,
        daos: SearchDaos,
    ): List<KeywordFallbackResult> {
        val cleanQuery = HybridSearchService.sanitizeQueryForRag(query)
        if (cleanQuery.isBlank() || topK <= 0 || scopeDocIds?.isEmpty() == true) return emptyList()
        val allowedIds = scopeDocIds?.toSet()
        val filter = metadataFilter?.takeUnless { it.isEmpty() }
        val indexedResults = searchIndexedChunks(cleanQuery, topK, allowedIds, filter, daos)
        return if (indexedResults.isNotEmpty()) {
            indexedResults
        } else {
            searchDocuments(cleanQuery, topK, allowedIds, filter, daos)
        }
    }

    private suspend fun searchIndexedChunks(
        query: String,
        topK: Int,
        allowedIds: Set<String>?,
        filter: VectorSearchService.MetadataFilter?,
        daos: SearchDaos,
    ): List<KeywordFallbackResult> {
        val hits = resultOf {
            daos.ftsDao.searchBm25Safe(
                HybridSearchService.buildFtsQuery(query),
                (topK * 3).coerceAtLeast(topK),
            )
        }
            .getOrNull()
            .orEmpty()
        val chunks = if (hits.isEmpty()) {
            emptyList()
        } else {
            resultOf { daos.chunkDao.getByIds(hits.map { it.chunkId }.distinct()) }
                .getOrNull()
                .orEmpty()
        }
        val chunkById = chunks.associateBy { it.id }
        val docs = if (chunks.isEmpty()) {
            emptyList()
        } else {
            resultOf { daos.docDao.getByIds(chunks.map { it.docId }.distinct()) }
                .getOrNull()
                .orEmpty()
        }
        val docById = docs.asSequence()
            .filter { !it.isInternal && (allowedIds == null || it.id in allowedIds) }
            .associateBy { it.id }

        return hits.mapNotNull { hit ->
            val result = resultForFtsHit(hit, chunkById, docById, filter) ?: return@mapNotNull null
            if (hit.score >= 0.0) {
                val relevance = HybridSearchService.computeLexicalRelevance(
                    query = query,
                    content = result.snippet,
                    title = result.docTitle,
                )
                if (relevance < HybridSearchService.MIN_UNSCORED_LEXICAL_RELEVANCE) {
                    return@mapNotNull null
                }
                result.copy(score = relevance)
            } else {
                result
            }
        }.distinctBy { it.chunkId }.take(topK)
    }

    private fun resultForFtsHit(
        hit: KnowledgeChunkFtsHit,
        chunksById: Map<String, KnowledgeChunkEntity>,
        docsById: Map<String, KnowledgeDocEntity>,
        filter: VectorSearchService.MetadataFilter?,
    ): KeywordFallbackResult? = chunksById[hit.chunkId]?.let { chunk ->
        docsById[chunk.docId]?.takeIf { doc ->
            filter == null || filter.matches(doc.id, doc.title, chunk.metadataJson, chunk.createdAt)
        }?.let { doc ->
            KeywordFallbackResult(
                docId = doc.id,
                docTitle = doc.title,
                snippet = chunk.content.take(MAX_SNIPPET_CHARS),
                chunkId = chunk.id,
                chunkIndex = chunk.chunkIndex,
                score = (-hit.score).toFloat(),
                metadataJson = chunk.metadataJson,
                createdAt = chunk.createdAt,
            )
        }
    }

    private suspend fun searchDocuments(
        query: String,
        topK: Int,
        allowedIds: Set<String>?,
        filter: VectorSearchService.MetadataFilter?,
        daos: SearchDaos,
    ): List<KeywordFallbackResult> {
        val fallbackDocs = resultOf {
            if (allowedIds == null) daos.docDao.search(query).first() else daos.docDao.getByIds(allowedIds.toList())
        }.getOrNull().orEmpty()
        val publicDocs = fallbackDocs.asSequence()
            .filter { !it.isInternal && (allowedIds == null || it.id in allowedIds) }
            .filter { doc ->
                allowedIds == null ||
                    doc.title.contains(query, ignoreCase = true) ||
                    doc.content.contains(query, ignoreCase = true) ||
                    HybridSearchService.computeLexicalRelevance(
                        query = query,
                        content = doc.content.take(2000),
                        title = doc.title,
                    ) >= HybridSearchService.MIN_UNSCORED_LEXICAL_RELEVANCE
            }
            .take(topK)
            .toList()
        val results = mutableListOf<KeywordFallbackResult>()
        for (doc in publicDocs) {
            resultForDocument(doc, filter, daos.chunkDao)?.let(results::add)
        }
        return results
    }

    private suspend fun resultForDocument(
        doc: KnowledgeDocEntity,
        filter: VectorSearchService.MetadataFilter?,
        chunkDao: KnowledgeChunkDao,
    ): KeywordFallbackResult? {
        val docChunks = resultOf { chunkDao.getByDoc(doc.id) }.getOrNull().orEmpty()
        val matchingChunk = docChunks.firstOrNull { chunk ->
            filter == null || filter.matches(doc.id, doc.title, chunk.metadataJson, chunk.createdAt)
        }
        return if (filter != null && matchingChunk == null) {
            null
        } else {
            KeywordFallbackResult(
                docId = doc.id,
                docTitle = doc.title,
                snippet = (matchingChunk?.content?.takeIf { it.isNotBlank() } ?: doc.content).take(MAX_SNIPPET_CHARS),
                chunkId = matchingChunk?.id.orEmpty(),
                chunkIndex = matchingChunk?.chunkIndex ?: 0,
                metadataJson = matchingChunk?.metadataJson ?: "{}",
                createdAt = matchingChunk?.createdAt ?: 0L,
            )
        }
    }

    private const val MAX_SNIPPET_CHARS = 500
}
