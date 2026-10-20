package io.zer0.muse.rag

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.zer0.muse.data.knowledge.KnowledgeChunkDao
import io.zer0.muse.data.knowledge.KnowledgeChunkEntity
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsDao
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
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
}
