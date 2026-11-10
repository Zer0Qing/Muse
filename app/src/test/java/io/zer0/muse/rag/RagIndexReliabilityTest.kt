package io.zer0.muse.rag

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.data.knowledge.KnowledgeChunkDao
import io.zer0.muse.data.knowledge.KnowledgeChunkEntity
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsDao
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RagIndexReliabilityTest {
    private class FixedEmbeddingProvider : EmbeddingProvider {
        override val id = "fixed"
        override val displayName = "Fixed test provider"
        override val dimension = 2
        override val modelName = "fixed-model"

        override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { floatArrayOf(1f, 0f) }
    }

    private class RecordingEmbeddingProvider : EmbeddingProvider {
        override val id = "recording"
        override val displayName = "Recording test provider"
        override val dimension = 2
        override val modelName = "recording-model"
        val receivedTexts = mutableListOf<String>()

        override suspend fun embed(texts: List<String>): List<FloatArray> {
            receivedTexts += texts
            return texts.map { floatArrayOf(1f, 0f) }
        }
    }

    private fun config() = RagConfig(
        hybridEnabled = false,
        rerankEnabled = false,
        threshold = 0f,
        topK = 8,
    )

    private fun service(
        chunkDao: KnowledgeChunkDao,
        docDao: KnowledgeDocDao,
        ftsDao: KnowledgeChunkFtsDao,
        indexFile: java.io.File? = null,
        reindexStagingDirectory: java.io.File? = null,
        provider: EmbeddingProvider = FixedEmbeddingProvider(),
    ): RagService {
        val embedding = mockk<EmbeddingService>()
        coEvery { embedding.getProvider(any()) } returns provider
        // RagService 会读后端状态做“当前实际在用哪个 embedding”的诊断，替身要给一个值
        every { embedding.activeBackend } returns MutableStateFlow(null)
        return RagService(
            chunkDao = chunkDao,
            docDao = docDao,
            ftsDao = ftsDao,
            docTitleProvider = { mapOf("old-doc" to "Old", "new-doc" to "New") },
            embeddingService = embedding,
            indexFile = indexFile,
            reindexStagingDirectory = reindexStagingDirectory,
        )
    }

    @Test
    fun `non-stream replacement failure cleans partial chunks and resets doc status`() = runBlocking {
        val oldChunk = KnowledgeChunkEntity(
            id = "old-chunk",
            docId = "new-doc",
            content = "previous indexed content",
            embeddingBlob = VectorSearchService.floatArrayToBlob(floatArrayOf(1f, 0f)),
        )
        val rows = mutableListOf(oldChunk)
        val chunkDao = mockk<KnowledgeChunkDao>(relaxed = true)
        val docDao = mockk<KnowledgeDocDao>(relaxed = true)
        val ftsDao = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        coEvery { chunkDao.getFirstIndexedEmbeddingDim() } returns 2
        coEvery { chunkDao.countIndexed() } coAnswers { rows.size }
        coEvery { chunkDao.getAllWithEmbedding() } coAnswers { rows.toList() }
        coEvery { chunkDao.getByDoc(any()) } coAnswers { rows.filter { it.docId == firstArg<String>() } }
        coEvery { chunkDao.deleteByDoc(any()) } coAnswers {
            val docId = firstArg<String>()
            rows.removeAll { it.docId == docId }
        }
        coEvery { chunkDao.insertAll(any()) } coAnswers { throw IllegalStateException("injected chunk insert failure") }
        val rag = service(chunkDao, docDao, ftsDao)

        val failure = runCatching { rag.indexDocument("new-doc", "replacement content", config()) }.exceptionOrNull()

        assertTrue("indexing must surface the inserted-chunk failure", failure is IllegalStateException)
        assertTrue("failed replacement must not leave stale or partial chunks", rows.isEmpty())
        coVerify(exactly = 2) { chunkDao.deleteByDoc("new-doc") }
        // P0-3（审计报告）: 中途失败不再静默 clearIndexStatus，而是标记为「索引已丢」。
        // 旧行为让 UI 看起来只是「未索引」，用户不知道该文档原本建过索引、现在数据没了。
        coVerify(exactly = 1) { docDao.markIndexBroken("new-doc") }
    }

    @Test
    fun `missing hnsw index with existing embeddings keeps db vectors authoritative after incremental import`() = runBlocking {
        val oldChunk = KnowledgeChunkEntity(
            id = "old-chunk",
            docId = "old-doc",
            content = "old indexed passage",
            embeddingBlob = VectorSearchService.floatArrayToBlob(floatArrayOf(1f, 0f)),
        )
        val rows = mutableListOf(oldChunk)
        val docs = listOf(
            KnowledgeDocEntity(id = "old-doc", title = "Old"),
            KnowledgeDocEntity(id = "new-doc", title = "New"),
        )
        val chunkDao = mockk<KnowledgeChunkDao>(relaxed = true)
        val docDao = mockk<KnowledgeDocDao>(relaxed = true)
        val ftsDao = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        coEvery { chunkDao.getFirstIndexedEmbeddingDim() } returns 2
        coEvery { chunkDao.countIndexed() } coAnswers { rows.size }
        coEvery { chunkDao.getAllWithEmbedding() } coAnswers { rows.toList() }
        coEvery { chunkDao.getByDoc(any()) } coAnswers { rows.filter { it.docId == firstArg<String>() } }
        coEvery { chunkDao.deleteByDoc(any()) } coAnswers {
            val docId = firstArg<String>()
            rows.removeAll { it.docId == docId }
        }
        coEvery { chunkDao.insertAll(any()) } coAnswers {
            rows.addAll(firstArg<List<KnowledgeChunkEntity>>())
        }
        coEvery { chunkDao.getPageWithEmbedding(any(), any()) } coAnswers { rows.toList() }
        coEvery { docDao.observeAll() } returns flowOf(docs)
        coEvery { docDao.getByIds(any()) } coAnswers { docs.filter { it.id in firstArg<List<String>>() } }
        val rag = service(chunkDao, docDao, ftsDao)

        rag.indexDocument("new-doc", "new indexed passage", config())
        val results = rag.retrieve("query", topK = 8, threshold = 0f, ragConfig = config())

        assertEquals(setOf("old-doc", "new-doc"), results.map { it.docId }.toSet())
    }

    @Test
    fun `single reindex uses original source when stored content is only a preview`() = runBlocking {
        val fullContent = "source-head " + "x".repeat(3_000) + " source-tail-marker"
        val doc = KnowledgeDocEntity(
            id = "preview-doc",
            title = "Preview document",
            content = fullContent.take(40),
            contentHash = RagService.computeContentHash(fullContent),
            chunkCount = 1,
            embeddingModel = "old-model",
        )
        val chunkDao = mockk<KnowledgeChunkDao>(relaxed = true)
        val docDao = mockk<KnowledgeDocDao>(relaxed = true)
        val ftsDao = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        val provider = RecordingEmbeddingProvider()
        val stagingDir = Files.createTempDirectory("rag-reindex-").toFile()
        try {
            coEvery { docDao.getById("preview-doc") } returns doc
            coEvery { chunkDao.getFirstIndexedEmbeddingDim() } returns null
            coEvery { chunkDao.getByDoc("preview-doc") } returns emptyList()
            val rag = service(
                chunkDao = chunkDao,
                docDao = docDao,
                ftsDao = ftsDao,
                reindexStagingDirectory = stagingDir,
                provider = provider,
            )

            rag.reindexDocument(
                docId = doc.id,
                ragConfig = config(),
                sourceContentProvider = { flowOf(fullContent) },
            )

            assertTrue(provider.receivedTexts.any { it.contains("source-tail-marker") })
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    /**
     * E（保尾 green guard）—— 完整正文走 inline 分支重建时,尾标记不得在预览线处丢失。
     *
     * 与上一个测试互补:上一个钉「正文只是预览 → 回源原件流式重建」;这一个钉
     * 「正文本身就是完整内容(contentHash 与正文一致) → 直接 inline 重建」。
     * 完整正文超过 500K 预览线,尾标记落在截断点之后;inline 分支必须把全文喂给
     * indexDocumentInternal,尾标记同时到达 embedding 与落库 chunk。
     *
     * 这是 green guard(现状正确):若后续批次让 inline 重建在 REINDEX_PREVIEW_CHARS 处
     * 截断正文,尾标记丢失 → 本测试转红。不提供 sourceContentProvider,确保走的是 inline
     * 分支而非回源分支(回源分支在缺 provider 时会 error,从而暴露分支误走)。
     */
    @Test
    fun `inline full-content reindex preserves tail beyond preview cap`() = runBlocking {
        val tailMarker = "INLINE-TAIL-BEYOND-PREVIEW-CAP"
        val fullContent = "inline-head " + "x".repeat(500_000) + " " + tailMarker
        val doc = KnowledgeDocEntity(
            id = "inline-doc",
            title = "Inline complete document",
            content = fullContent,
            contentHash = RagService.computeContentHash(fullContent),
            chunkCount = 1,
            embeddingModel = "old-model",
        )
        val storedChunks = mutableListOf<KnowledgeChunkEntity>()
        val chunkDao = mockk<KnowledgeChunkDao>(relaxed = true)
        val docDao = mockk<KnowledgeDocDao>(relaxed = true)
        val ftsDao = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        val provider = RecordingEmbeddingProvider()
        coEvery { docDao.getById("inline-doc") } returns doc
        coEvery { chunkDao.getFirstIndexedEmbeddingDim() } returns null
        coEvery { chunkDao.getByDoc("inline-doc") } returns emptyList()
        coEvery { chunkDao.insertAll(any()) } coAnswers { storedChunks.addAll(firstArg<List<KnowledgeChunkEntity>>()) }
        val rag = service(chunkDao, docDao, ftsDao, provider = provider)

        rag.reindexDocument(
            docId = doc.id,
            ragConfig = RagConfig(
                chunkSize = 1000,
                chunkOverlap = 100,
                markdownAware = false,
                hybridEnabled = false,
                rerankEnabled = false,
                threshold = 0f,
                topK = 8,
            ),
        )

        assertTrue(
            "尾标记必须到达 embedding(inline 完整正文不得在预览线截断)",
            provider.receivedTexts.any { it.contains(tailMarker) },
        )
        assertTrue(
            "尾标记必须落库到 chunk(端到端保尾,而非只喂给 embedding)",
            storedChunks.any { it.content.contains(tailMarker) },
        )
    }
}
