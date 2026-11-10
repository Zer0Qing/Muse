package io.zer0.muse.rag

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.zer0.muse.data.knowledge.KnowledgeChunkDao
import io.zer0.muse.data.knowledge.KnowledgeChunkEntity
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsDao
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * v2.x: 流式索引([RagService.indexDocumentStreamed])单测 — 大文件导入链路回归防线。
 *
 * 背景:知识库导入从「全文读入内存 + 全量分块」改为「滑窗流式摄取」,支持任意大小文件。
 * 这里钉住关键语义:内容完整(不再有 500K 字符截断)、分批嵌入写入、
 * 重导清理旧块、空流 / 维度不匹配时不破坏既有数据。
 */
class RagStreamedIndexTest {

    private class FakeEmbeddingProvider(private val dim: Int = 8) : EmbeddingProvider {
        override val id = "fake"
        override val displayName = "Fake Provider"
        override val dimension = dim
        override val modelName = "fake-model"
        val batches = mutableListOf<List<String>>()

        override suspend fun embed(texts: List<String>): List<FloatArray> {
            batches.add(texts.toList())
            return texts.map { t -> FloatArray(dim) { i -> (t.length + i).toFloat() } }
        }
    }

    private fun buildService(
        chunkDao: KnowledgeChunkDao,
        docDao: KnowledgeDocDao,
        ftsDao: KnowledgeChunkFtsDao,
        provider: EmbeddingProvider,
        indexFile: java.io.File? = null,
    ): RagService {
        val embeddingService = mockk<EmbeddingService>(relaxed = true)
        coEvery { embeddingService.getProvider(any()) } returns provider
        return RagService(
            chunkDao = chunkDao,
            docDao = docDao,
            ftsDao = ftsDao,
            docTitleProvider = { emptyMap() },
            embeddingService = embeddingService,
            indexFile = indexFile,
        )
    }

    private class FingerprintedEmbeddingProvider(
        override val dimension: Int = 2,
        override val modelName: String = "current-model",
    ) : EmbeddingProvider {
        override val id = "fake"
        override val displayName = "Fake Provider"

        override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { text ->
            when {
                text == "query" -> FloatArray(dimension) { if (it == 0) 1f else 0f }
                text.startsWith("new") -> FloatArray(dimension) { if (it == 0) 0.8f else if (it == 1) 0.6f else 0f }
                else -> FloatArray(dimension) { if (it == 0) 1f else 0f }
            }
        }
    }

    private class VectorPageReadCounter(var count: Int = 0)

    private data class FingerprintReindexFixture(
        val tempDir: java.io.File,
        val service: RagService,
        val config: RagConfig,
        val pageReads: VectorPageReadCounter,
    )

    private fun createFingerprintReindexFixture(
        provider: FingerprintedEmbeddingProvider = FingerprintedEmbeddingProvider(),
    ): FingerprintReindexFixture {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "rag_fingerprint_${System.nanoTime()}")
        check(tempDir.mkdirs())
        val documents = listOf(
            KnowledgeDocEntity("old-doc", "Old", content = "old content", kbId = "default"),
            KnowledgeDocEntity("new-doc", "New", content = "new content", kbId = "default"),
            KnowledgeDocEntity("empty-doc", "Empty", content = "", kbId = "default"),
        )
        val chunks = mutableListOf(
            KnowledgeChunkEntity(
                id = "old-chunk",
                docId = documents.first().id,
                content = "old chunk",
                embeddingBlob = VectorSearchService.floatArrayToBlob(floatArrayOf(1f, 0f)),
            ),
            KnowledgeChunkEntity(
                id = "empty-old-chunk",
                docId = documents.last().id,
                content = "stale content",
                embeddingBlob = VectorSearchService.floatArrayToBlob(floatArrayOf(1f, 0f)),
            ),
        )
        val chunkDao = mockk<KnowledgeChunkDao>(relaxed = true)
        val docDao = mockk<KnowledgeDocDao>(relaxed = true)
        val ftsDao = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        val pageReads = VectorPageReadCounter()
        coEvery { chunkDao.getFirstIndexedEmbeddingDim() } returns 2
        coEvery { chunkDao.getAllWithEmbedding() } coAnswers { chunks.toList() }
        coEvery { chunkDao.getByDoc(any()) } coAnswers { chunks.filter { it.docId == firstArg() } }
        coEvery { chunkDao.deleteByDoc(any()) } coAnswers {
            val remaining = chunks.filterNot { it.docId == firstArg<String>() }
            chunks.clear()
            chunks.addAll(remaining)
        }
        coEvery { chunkDao.insertAll(any()) } coAnswers { chunks.addAll(firstArg<List<KnowledgeChunkEntity>>()) }
        coEvery { chunkDao.countIndexed() } coAnswers { chunks.size }
        coEvery { chunkDao.getPageWithEmbedding(any(), any()) } coAnswers {
            pageReads.count++
            chunks.toList()
        }
        coEvery { docDao.getByKbIds(any()) } returns documents
        coEvery { docDao.getAll() } returns documents
        coEvery { docDao.getByIds(any()) } coAnswers {
            val ids = firstArg<List<String>>().toSet()
            documents.filter { it.id in ids }
        }

        val indexFile = java.io.File(tempDir, "hnsw.bin")
        HnswVectorIndex().apply {
            add("old-chunk", floatArrayOf(1f, 0f))
            add("empty-old-chunk", floatArrayOf(1f, 0f))
            save(indexFile, "fake\u0000old-model\u00002")
        }
        val embeddingService = mockk<EmbeddingService>(relaxed = true)
        coEvery { embeddingService.getProvider(any()) } returns provider
        val service = RagService(
            chunkDao = chunkDao,
            docDao = docDao,
            ftsDao = ftsDao,
            docTitleProvider = { mapOf("old-doc" to "Old", "new-doc" to "New") },
            embeddingService = embeddingService,
            indexFile = indexFile,
        )
        val config = RagConfig(chunkSize = 100, chunkOverlap = 10, markdownAware = false, threshold = 0.1f)
        return FingerprintReindexFixture(tempDir, service, config, pageReads)
    }

    @Test
    fun `streamed index preserves all content across windows and batches`() = runBlocking {
        val chunkDao = mockk<KnowledgeChunkDao>(relaxed = true)
        val docDao = mockk<KnowledgeDocDao>(relaxed = true)
        val ftsDao = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        coEvery { chunkDao.getFirstIndexedEmbeddingDim() } returns null
        val provider = FakeEmbeddingProvider()
        val service = buildService(chunkDao, docDao, ftsDao, provider)

        // 300 段,每段 ~200 字符 → ~64K 字符;windowChars=2048 → 多窗;chunkSize=300 → 多块多批
        val segments = (0 until 300).map { i -> "SEG%03d ".format(i) + "文字内容".repeat(48) }
        val fullText = segments.joinToString("\n\n")
        val pieces = fullText.chunked(4096)
        val progress = mutableListOf<Int>()
        val count = service.indexDocumentStreamed(
            docId = "doc-stream-1",
            textPieces = flow { pieces.forEach { emit(it) } },
            ragConfig = RagConfig(chunkSize = 300, chunkOverlap = 30, markdownAware = false),
            windowChars = 2048,
            onProgress = { progress.add(it) },
        )

        assertTrue("应产出多个块,实际 $count", count > 50)
        assertTrue("应分批嵌入(批数=${provider.batches.size})", provider.batches.size >= 2)
        assertEquals("进度应递增到底", count, progress.last())
        assertEquals("进度应非递减", progress.sorted(), progress)
        val all = provider.batches.flatten().joinToString("\n")
        for (tag in listOf("SEG000", "SEG150", "SEG299")) {
            assertTrue("缺少段落 $tag", all.contains(tag))
        }
    }

    @Test
    fun `streamed index does not truncate beyond legacy 500K char limit`() = runBlocking {
        val storedChunks = mutableListOf<KnowledgeChunkEntity>()
        val chunkDao = mockk<KnowledgeChunkDao>(relaxed = true)
        val docDao = mockk<KnowledgeDocDao>(relaxed = true)
        val ftsDao = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        coEvery { chunkDao.getFirstIndexedEmbeddingDim() } returns null
        coEvery { chunkDao.insertAll(any()) } coAnswers { storedChunks.addAll(firstArg<List<KnowledgeChunkEntity>>()) }
        val provider = FakeEmbeddingProvider()
        val service = buildService(chunkDao, docDao, ftsDao, provider)

        // 600K+ 字符(超过旧 500K 截断线),头部与尾部各有哨兵标记
        val head = "HEAD-MARK "
        val tail = " TAIL-MARK"
        val body = "长文本填充".repeat(120_000)
        val fullText = head + body + tail
        val pieces = fullText.chunked(8192)
        val count = service.indexDocumentStreamed(
            docId = "doc-big",
            textPieces = flow { pieces.forEach { emit(it) } },
            ragConfig = RagConfig(chunkSize = 1000, chunkOverlap = 100, markdownAware = false),
            windowChars = 8192,
        )

        assertTrue("大文件应产出较多块,实际 $count", count > 100)
        val all = provider.batches.flatten().joinToString("\n")
        assertTrue("头部内容缺失", all.contains("HEAD-MARK"))
        assertTrue("尾部内容缺失(旧实现 500K 截断会丢尾部)", all.contains("TAIL-MARK"))
        // E（保尾 green guard）:不仅 embedding 收到头尾标记,落库 chunk 也必须含 ——
        // 钉端到端持久化保尾。若后续批次在 createChunkEntities/落库阶段截断正文,
        // embedding 输入仍完整但落库丢尾 → 下面两条断言转红。
        assertTrue("落库 chunk 必须含头部标记", storedChunks.any { it.content.contains("HEAD-MARK") })
        assertTrue("落库 chunk 必须含尾部标记(端到端保尾)", storedChunks.any { it.content.contains("TAIL-MARK") })
    }

    @Test
    fun `streamed index clears previous chunks before writing new ones`() = runBlocking {
        val chunkDao = mockk<KnowledgeChunkDao>(relaxed = true)
        val docDao = mockk<KnowledgeDocDao>(relaxed = true)
        val ftsDao = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        coEvery { chunkDao.getFirstIndexedEmbeddingDim() } returns null
        val provider = FakeEmbeddingProvider()
        val service = buildService(chunkDao, docDao, ftsDao, provider)

        service.indexDocumentStreamed(
            docId = "doc-same",
            textPieces = flowOf("这是一个需要重新索引的测试文档,内容足够产生至少一个分块。"),
            ragConfig = RagConfig(chunkSize = 100, chunkOverlap = 10),
            windowChars = 1024,
        )

        coVerify { chunkDao.deleteByDoc("doc-same") }
        coVerify { ftsDao.deleteByDoc("doc-same") }
        coVerify { chunkDao.insertAll(any()) }
    }

    @Test
    fun `streamed index with empty flow keeps existing chunks untouched`() = runBlocking {
        val chunkDao = mockk<KnowledgeChunkDao>(relaxed = true)
        val docDao = mockk<KnowledgeDocDao>(relaxed = true)
        val ftsDao = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        coEvery { chunkDao.getFirstIndexedEmbeddingDim() } returns null
        val provider = FakeEmbeddingProvider()
        val service = buildService(chunkDao, docDao, ftsDao, provider)

        val count = service.indexDocumentStreamed(
            docId = "doc-empty",
            textPieces = flowOf<String>(),
            ragConfig = RagConfig(),
            windowChars = 1024,
        )

        assertEquals(0, count)
        coVerify(exactly = 0) { chunkDao.deleteByDoc(any()) }
        coVerify(exactly = 0) { chunkDao.insertAll(any()) }
    }

    @Test
    fun `streamed index aborts on dimension mismatch without clearing old chunks`() = runBlocking {
        val chunkDao = mockk<KnowledgeChunkDao>(relaxed = true)
        val docDao = mockk<KnowledgeDocDao>(relaxed = true)
        val ftsDao = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        // 库中已有 16 维 chunk,新 provider 输出 8 维 → 必须中止且不清理旧块
        coEvery { chunkDao.getFirstIndexedEmbeddingDim() } returns 16
        val provider = FakeEmbeddingProvider(dim = 8)
        val service = buildService(chunkDao, docDao, ftsDao, provider)

        try {
            service.indexDocumentStreamed(
                docId = "doc-dim",
                textPieces = flowOf("维度不匹配时不应写入任何数据。"),
                ragConfig = RagConfig(chunkSize = 100, chunkOverlap = 10),
                windowChars = 1024,
            )
            fail("应抛出维度不匹配异常")
        } catch (e: IllegalStateException) {
            assertTrue("异常信息应说明维度不匹配: ${e.message}", e.message.orEmpty().contains("dimension mismatch"))
        }
        coVerify(exactly = 0) { chunkDao.deleteByDoc("doc-dim") }
        coVerify(exactly = 0) { chunkDao.insertAll(any()) }
    }

    @Test
    fun `partial reindex after embedding model switch uses full vector search until all KBs rebuild`() = runBlocking {
        val fixture = createFingerprintReindexFixture()
        try {
            fixture.service.indexDocument("new-doc", "new document content", fixture.config)
            val beforeFullReindex = fixture.service.retrieve("query", 1, 0.1f, fixture.config)
            assertTrue("指纹失配后不得用旧数据库向量与新模型分数混合", beforeFullReindex.isEmpty())
            assertEquals("指纹失配期间必须禁用所有向量检索", 0, fixture.pageReads.count)
            fixture.service.saveVectorIndex()
            assertThrows(HnswEmbeddingFingerprintMismatchException::class.java) {
                HnswVectorIndex().load(
                    java.io.File(fixture.tempDir, "hnsw.bin"),
                    "fake\u0000current-model\u00002",
                )
            }

            val failures = fixture.service.reindexAllInKbs(listOf("default"), fixture.config)
            assertTrue("全 KB 重建应成功: $failures", failures.isEmpty())
            fixture.pageReads.count = 0
            val afterFullReindex = fixture.service.retrieve("query", 3, 0.1f, fixture.config)
            assertEquals("完整重建后最近邻仍应正确", "chunk-old-doc-0", afterFullReindex.first().chunkId)
            assertEquals(setOf("chunk-old-doc-0", "chunk-new-doc-0"), afterFullReindex.map { it.chunkId }.toSet())
            assertEquals("完整重建后的 HNSW 检索不应回退扫描向量表", 0, fixture.pageReads.count)
        } finally {
            fixture.tempDir.listFiles()?.forEach { it.delete() }
            fixture.tempDir.delete()
        }
    }

    @Test
    fun `full reindex can replace every vector after embedding dimension changes`() = runBlocking {
        val fixture = createFingerprintReindexFixture(
            provider = FingerprintedEmbeddingProvider(dimension = 3, modelName = "new-dimension-model"),
        )
        try {
            val failures = fixture.service.reindexAllInKbs(listOf("default"), fixture.config)

            assertTrue("全库维度切换重建应成功: $failures", failures.isEmpty())
            fixture.pageReads.count = 0
            val results = fixture.service.retrieve("query", 3, 0.1f, fixture.config)
            assertEquals(setOf("chunk-old-doc-0", "chunk-new-doc-0"), results.map { it.chunkId }.toSet())
            assertEquals("完成全库维度切换重建后应重新启用 HNSW", 0, fixture.pageReads.count)
        } finally {
            fixture.tempDir.listFiles()?.forEach { it.delete() }
            fixture.tempDir.delete()
        }
    }

    @Test
    fun `oversized plain chunk is split when markdownAware merges structureless text`() = runBlocking {
        val chunkDao = mockk<KnowledgeChunkDao>(relaxed = true)
        val docDao = mockk<KnowledgeDocDao>(relaxed = true)
        val ftsDao = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        coEvery { chunkDao.getFirstIndexedEmbeddingDim() } returns null
        val provider = FakeEmbeddingProvider()
        val service = buildService(chunkDao, docDao, ftsDao, provider)

        // markdownAware=true(默认)+ 无 Markdown 结构的长文本:TextChunker 会合并成巨块,
        // 流式摄取的保底二次切分应将其切到 targetSize 量级,避免巨块进入 embedding/检索
        val body = "纯段落长文本".repeat(20_000) // 120K 字符,无换行无标点
        val chunkSize = 500
        val count = service.indexDocumentStreamed(
            docId = "doc-plain",
            textPieces = flowOf(body),
            ragConfig = RagConfig(chunkSize = chunkSize, chunkOverlap = 50, markdownAware = true),
            windowChars = 8192,
        )

        assertTrue("应产出多个块,实际 $count", count > 50)
        val maxLen = provider.batches.flatten().maxOf { it.length }
        assertTrue("块长应被保底切分到 ${chunkSize * 2} 以内,实际最大 $maxLen", maxLen <= chunkSize * 2)
    }

    @Test
    fun `oversized code block is preserved under fallback split`() = runBlocking {
        val chunkDao = mockk<KnowledgeChunkDao>(relaxed = true)
        val docDao = mockk<KnowledgeDocDao>(relaxed = true)
        val ftsDao = mockk<KnowledgeChunkFtsDao>(relaxed = true)
        coEvery { chunkDao.getFirstIndexedEmbeddingDim() } returns null
        val provider = FakeEmbeddingProvider()
        val service = buildService(chunkDao, docDao, ftsDao, provider)

        // 大代码块(超过 2×chunkSize)应被整体保留,不受保底切分影响
        val codeBlock = "```\n" + "val line = 1\n".repeat(300) + "```"
        val text = "说明文字。\n\n$codeBlock\n\n结尾。"
        val count = service.indexDocumentStreamed(
            docId = "doc-code",
            textPieces = flowOf(text),
            ragConfig = RagConfig(chunkSize = 200, chunkOverlap = 20, markdownAware = true),
            windowChars = 4096,
        )

        assertTrue("应至少产出一个块", count >= 1)
        val codeChunk = provider.batches.flatten().firstOrNull { it.contains("```") }
        assertTrue("应存在代码块", codeChunk != null)
        assertTrue("代码块应整体保留(不超长硬切),实际长度 ${codeChunk!!.length}", codeChunk.length > 200 * 2)
    }
}
