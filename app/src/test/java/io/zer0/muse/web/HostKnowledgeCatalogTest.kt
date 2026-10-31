package io.zer0.muse.web

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.data.knowledge.KnowledgeBaseEntity
import io.zer0.muse.data.knowledge.KnowledgeChunkEntity
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.rag.EmbeddingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 知识只读命令的投影与分页：真 Room + 真 DAO，验的是「数字是不是库里的真值」「页边界对不对」。
 *
 * 命令分派与焦点行为由 HostCatalogFocusTest 覆盖，这里只看 `knowledge.list` /
 * `knowledge.documents` 的回执内容。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HostKnowledgeCatalogTest {

    private lateinit var database: MuseDb
    private lateinit var embeddingService: EmbeddingService
    private lateinit var source: DaoHostKnowledgeSource

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).build()
        embeddingService = mockk(relaxed = true)
        every { embeddingService.activeBackend } returns MutableStateFlow(
            EmbeddingService.BackendState(
                backendId = "local-keyword",
                displayName = "Local Keyword (Offline)",
                modelName = "local-keyword-v1",
                degraded = true,
                reason = "云端 Provider 不可用",
            ),
        )
        source = DaoHostKnowledgeSource(
            baseDao = database.knowledgeBaseDao(),
            docDao = database.knowledgeDocDao(),
            chunkDao = database.knowledgeChunkDao(),
            embeddingService = embeddingService,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seedBase(id: String, name: String, createdAt: Long, updatedAt: Long) {
        database.knowledgeBaseDao().upsert(
            KnowledgeBaseEntity(id = id, name = name, createdAt = createdAt, updatedAt = updatedAt),
        )
    }

    private suspend fun seedDoc(
        id: String,
        kbId: String,
        updatedAt: Long,
        chunkCount: Int = 0,
        content: String = "正文",
        internal: Boolean = false,
        embeddingModel: String = "",
    ) {
        database.knowledgeDocDao().upsert(
            KnowledgeDocEntity(
                id = id,
                title = "title-$id",
                content = content,
                kbId = kbId,
                isInternal = internal,
                updatedAt = updatedAt,
                createdAt = updatedAt - 1,
                // 故意不跟随实际块数：文档行的 chunk_count 是写入列，知识库汇总要用真实块数。
                chunkCount = chunkCount,
                embeddingModel = embeddingModel,
            ),
        )
    }

    private suspend fun seedChunks(docId: String, count: Int) {
        database.knowledgeChunkDao().insertAll(
            (0 until count).map { index ->
                KnowledgeChunkEntity(
                    id = "chunk-$docId-$index",
                    docId = docId,
                    content = "chunk-$index",
                    chunkIndex = index,
                )
            },
        )
    }

    @Test
    fun `knowledge list reports library fields with real document and chunk counts`() = runTest {
        seedBase(id = "kb-a", name = "法律", createdAt = 1_000L, updatedAt = 2_000L)
        seedBase(id = "kb-b", name = "笔记", createdAt = 3_000L, updatedAt = 4_000L)
        // kb-a：两个文档，共 5 个真实分块；文档行的 chunk_count 分别停在 2 与 0（后者是旧值）。
        seedDoc(id = "doc-1", kbId = "kb-a", updatedAt = 10L, chunkCount = 2)
        seedChunks(docId = "doc-1", count = 2)
        seedDoc(id = "doc-2", kbId = "kb-a", updatedAt = 20L, chunkCount = 0)
        seedChunks(docId = "doc-2", count = 3)
        // 内部开发文档不进用户可见计数，块数也不算进来。
        seedDoc(id = "devdoc-1", kbId = "kb-a", updatedAt = 30L, internal = true)
        seedChunks(docId = "devdoc-1", count = 4)
        // 另一个库的文档与分块不能串到 kb-a 上。
        seedDoc(id = "doc-b", kbId = "kb-b", updatedAt = 40L)
        seedChunks(docId = "doc-b", count = 1)

        val event = HostKnowledgeReads.listEvent(source.bases(), requestId = "req-list")

        assertEquals(HostKnowledgeReads.TYPE_LIST, event.type)
        assertEquals("req-list", event.requestId)
        assertNull(event.error)
        assertEquals(2, event.knowledgeBases.size)

        val law = event.knowledgeBases.first { it.id == "kb-a" }
        assertEquals("法律", law.name)
        assertEquals(2, law.documentCount)
        assertEquals(5, law.chunkCount)
        assertEquals(1_000L, law.createdAt)
        assertEquals(2_000L, law.updatedAt)
        assertEquals("local-keyword-v1", law.embeddingBackend)

        val notes = event.knowledgeBases.first { it.id == "kb-b" }
        assertEquals(1, notes.documentCount)
        assertEquals(1, notes.chunkCount)
    }

    @Test
    fun `knowledge list is an empty array when no library exists`() = runTest {
        val event = HostKnowledgeReads.listEvent(source.bases(), requestId = "req-empty")

        assertEquals(HostKnowledgeReads.TYPE_LIST, event.type)
        assertNull(event.error)
        assertTrue(event.knowledgeBases.isEmpty())
    }

    @Test
    fun `knowledge documents pages with a cursor and stops on the last page`() = runTest {
        seedBase(id = "kb", name = "库", createdAt = 0L, updatedAt = 0L)
        // 25 条：updated_at 递增，所以排序（更新的在前）是 doc-24 … doc-0。
        repeat(25) { index ->
            seedDoc(id = "doc-$index", kbId = "kb", updatedAt = 100L + index, chunkCount = 1 + index)
        }

        val first = source.documents("kb", cursor = null, limit = HostKnowledgeReads.pageLimit(null))!!
        assertEquals(20, first.documents.size)
        assertTrue(first.hasMore)
        assertEquals("doc-24", first.documents.first().id)
        assertEquals("doc-5", first.documents.last().id)

        val second = source.documents("kb", cursor = first.documents.last().id, limit = HostKnowledgeReads.pageLimit(null))!!
        assertEquals(5, second.documents.size)
        assertFalse(second.hasMore)
        assertEquals("doc-4", second.documents.first().id)
        assertEquals("doc-0", second.documents.last().id)
        // 两页之间不重不漏。
        assertEquals(25, (first.documents + second.documents).map { it.id }.distinct().size)
    }

    @Test
    fun `knowledge documents exposes per-document fields and index status`() = runTest {
        seedBase(id = "kb", name = "库", createdAt = 0L, updatedAt = 0L)
        seedDoc(id = "indexed", kbId = "kb", updatedAt = 10L, chunkCount = 3, content = "abcdef", embeddingModel = "m")
        seedDoc(id = "broken", kbId = "kb", updatedAt = 20L, chunkCount = 0, embeddingModel = KnowledgeDocDao.INDEX_BROKEN_SENTINEL)
        seedDoc(id = "fresh", kbId = "kb", updatedAt = 30L, chunkCount = 0)

        val page = source.documents("kb", cursor = null, limit = 20)!!

        assertFalse(page.hasMore)
        val byId = page.documents.associateBy { it.id }
        assertEquals("title-indexed", byId.getValue("indexed").title)
        assertEquals(3, byId.getValue("indexed").chunkCount)
        assertEquals(6L, byId.getValue("indexed").sizeBytes)
        assertEquals(9L, byId.getValue("indexed").createdAt)
        assertEquals(HostDocumentStatus.INDEXED, byId.getValue("indexed").status)
        assertEquals(HostDocumentStatus.INDEX_BROKEN, byId.getValue("broken").status)
        assertEquals(HostDocumentStatus.PENDING, byId.getValue("fresh").status)
    }

    @Test
    fun `knowledge documents clamps the page size to the upper bound`() = runTest {
        seedBase(id = "kb", name = "库", createdAt = 0L, updatedAt = 0L)
        repeat(60) { index -> seedDoc(id = "doc-${index.toString().padStart(2, '0')}", kbId = "kb", updatedAt = 100L + index) }

        val limit = HostKnowledgeReads.pageLimit(999)
        assertEquals(HostKnowledgeReads.MAX_LIMIT, limit)
        assertEquals(50, limit)

        val page = source.documents("kb", cursor = null, limit = limit)!!
        assertEquals(50, page.documents.size)
        assertTrue(page.hasMore)

        val rest = source.documents("kb", cursor = page.documents.last().id, limit = limit)!!
        assertEquals(10, rest.documents.size)
        assertFalse(rest.hasMore)
    }

    @Test
    fun `knowledge documents answers knowledge_base_not_found for an unknown library`() = runTest {
        val page = source.documents("kb-missing", cursor = null, limit = 20)

        assertNull(page)
        val event = HostKnowledgeReads.documentsEvent(kbId = "kb-missing", page = page, requestId = "req-docs")
        assertEquals(HostKnowledgeReads.TYPE_DOCUMENTS, event.type)
        assertEquals(HostKnowledgeReads.ERROR_KB_NOT_FOUND, event.error)
        assertEquals("kb-missing", event.kbId)
        assertTrue(event.documents.isEmpty())
    }

    @Test
    fun `knowledge documents on an existing empty library is an empty page`() = runTest {
        seedBase(id = "kb", name = "库", createdAt = 0L, updatedAt = 0L)

        val event = HostKnowledgeReads.documentsEvent(
            kbId = "kb",
            page = source.documents("kb", cursor = null, limit = 20),
            requestId = "req-docs",
        )

        assertNull(event.error)
        assertTrue(event.documents.isEmpty())
        assertEquals(false, event.hasMore)
    }
}
