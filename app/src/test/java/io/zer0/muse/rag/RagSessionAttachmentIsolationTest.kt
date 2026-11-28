package io.zer0.muse.rag

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.data.knowledge.KnowledgeChunkDao
import io.zer0.muse.data.knowledge.KnowledgeChunkEntity
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsDao
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话附件隔离:附件是会话私有资料,只有调用方显式给出「当前会话附件」时才允许召回。
 */
class RagSessionAttachmentIsolationTest {

    private class FixedEmbeddingProvider : EmbeddingProvider {
        override val id = "fixed"
        override val displayName = "Fixed test provider"
        override val dimension = 2
        override val modelName = "fixed-model"

        override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { floatArrayOf(1f, 0f) }
    }

    private fun config() = RagConfig(
        hybridEnabled = false,
        rerankEnabled = false,
        threshold = 0f,
        topK = 5,
    )

    private fun chunk(id: String, docId: String, content: String) = KnowledgeChunkEntity(
        id = id,
        docId = docId,
        content = content,
        embeddingBlob = VectorSearchService.floatArrayToBlob(floatArrayOf(1f, 0f)),
    )

    private fun service(rows: List<KnowledgeChunkEntity>, docs: List<KnowledgeDocEntity>): RagService {
        val chunkDao = mockk<KnowledgeChunkDao>(relaxed = true)
        val docDao = mockk<KnowledgeDocDao>(relaxed = true)
        val ftsDao = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        coEvery { chunkDao.countIndexed() } returns rows.size
        coEvery { chunkDao.getPageWithEmbedding(any(), any()) } coAnswers {
            if (secondArg<Int>() == 0) rows else emptyList()
        }
        coEvery { docDao.getByIds(any()) } coAnswers {
            val ids = firstArg<List<String>>()
            docs.filter { it.id in ids }
        }
        val embedding = mockk<EmbeddingService>()
        coEvery { embedding.getProvider(any()) } returns FixedEmbeddingProvider()
        coEvery { embedding.cachedVector(any(), any()) } returns floatArrayOf(1f, 0f)
        every { embedding.activeBackend } returns MutableStateFlow(null)
        return RagService(
            chunkDao = chunkDao,
            docDao = docDao,
            ftsDao = ftsDao,
            docTitleProvider = { docs.associate { it.id to it.title } },
            embeddingService = embedding,
        )
    }

    @Test
    fun `session attachments are excluded from retrieval by default`() = runBlocking {
        val attachmentDocId = SessionAttachment.buildDocId("session-1", "attach-1")
        val rows = listOf(
            chunk("chunk-plain-0", "doc-plain", "用户知识库内容"),
            chunk("chunk-$attachmentDocId-0", attachmentDocId, "别的会话上传的附件内容"),
        )
        val docs = listOf(
            KnowledgeDocEntity(id = "doc-plain", title = "普通文档", fileType = "txt"),
            KnowledgeDocEntity(
                id = attachmentDocId,
                title = "会话附件",
                fileType = SessionAttachment.FILE_TYPE,
            ),
        )
        val rag = service(rows, docs)

        val results = rag.retrieve("查询", topK = 5, threshold = 0f, ragConfig = config())

        assertEquals(
            "默认检索不得召回会话附件",
            listOf("doc-plain"),
            results.map { it.docId },
        )
    }

    @Test
    fun `explicitly allowed session attachment doc ids are retrievable`() = runBlocking {
        val attachmentDocId = SessionAttachment.buildDocId("session-1", "attach-1")
        val rows = listOf(
            chunk("chunk-plain-0", "doc-plain", "用户知识库内容"),
            chunk("chunk-$attachmentDocId-0", attachmentDocId, "本会话上传的附件内容"),
        )
        val docs = listOf(
            KnowledgeDocEntity(id = "doc-plain", title = "普通文档", fileType = "txt"),
            KnowledgeDocEntity(
                id = attachmentDocId,
                title = "会话附件",
                fileType = SessionAttachment.FILE_TYPE,
            ),
        )
        val rag = service(rows, docs)

        val results = rag.retrieve(
            query = "查询",
            topK = 5,
            threshold = 0f,
            ragConfig = config(),
            allowedSessionAttachmentDocIds = setOf(attachmentDocId),
        )

        assertEquals(
            "显式允许后本会话附件必须可召回",
            setOf("doc-plain", attachmentDocId),
            results.map { it.docId }.toSet(),
        )
    }

    @Test
    fun `attachment doc id prefix alone is enough to keep it out of automatic injection`() = runBlocking {
        // 历史数据可能只写了 docId 前缀、没写 fileType;隔离判定必须两条都认。
        val legacyAttachmentDocId = SessionAttachment.buildDocId("session-9", "attach-9")
        val rows = listOf(
            chunk("chunk-$legacyAttachmentDocId-0", legacyAttachmentDocId, "历史附件内容"),
        )
        val docs = listOf(
            KnowledgeDocEntity(id = legacyAttachmentDocId, title = "历史附件", fileType = "txt"),
        )
        val rag = service(rows, docs)

        val results = rag.retrieve("查询", topK = 5, threshold = 0f, ragConfig = config())

        assertTrue("缺 fileType 的附件也必须按 docId 前缀隔离,实际=${results.map { it.docId }}", results.isEmpty())
    }

    @Test
    fun `keyword fallback also drops session attachments that were not allowed`() = runBlocking {
        val attachmentDocId = SessionAttachment.buildDocId("session-2", "attach-2")
        val docs = listOf(
            KnowledgeDocEntity(id = "doc-plain", title = "普通文档", fileType = "txt"),
            KnowledgeDocEntity(id = attachmentDocId, title = "会话附件", fileType = SessionAttachment.FILE_TYPE),
        )
        val chunkDao = mockk<KnowledgeChunkDao>(relaxed = true)
        val docDao = mockk<KnowledgeDocDao>(relaxed = true)
        val ftsDao = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        coEvery { chunkDao.countIndexed() } returns 0
        coEvery { docDao.getByIds(any()) } coAnswers {
            val ids = firstArg<List<String>>()
            docs.filter { it.id in ids }
        }
        val embedding = mockk<EmbeddingService>()
        coEvery { embedding.getProvider(any()) } returns FixedEmbeddingProvider()
        coEvery { embedding.cachedVector(any(), any()) } returns floatArrayOf(1f, 0f)
        every { embedding.activeBackend } returns MutableStateFlow(null)
        val rag = RagService(
            chunkDao = chunkDao,
            docDao = docDao,
            ftsDao = ftsDao,
            docTitleProvider = { emptyMap() },
            embeddingService = embedding,
            keywordSearchFallback = { _, _ ->
                listOf(
                    KeywordFallbackResult(
                        docId = "doc-plain",
                        docTitle = "普通文档",
                        snippet = "普通内容",
                        chunkId = "chunk-plain-0",
                    ),
                    KeywordFallbackResult(
                        docId = attachmentDocId,
                        docTitle = "会话附件",
                        snippet = "附件内容",
                        chunkId = "chunk-$attachmentDocId-0",
                    ),
                )
            },
        )

        val injection = rag.buildInjectionContextWithCitations("查询", config())

        assertTrue("兜底路径必须保留普通文档", injection.citations.any { it.docId == "doc-plain" })
        assertTrue(
            "兜底路径不得召回未允许的会话附件,实际=${injection.citations.map { it.docId }}",
            injection.citations.none { it.docId == attachmentDocId },
        )
    }
}
