package io.zer0.muse.knowledge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.knowledge.IndexGenerationState
import io.zer0.muse.data.knowledge.KnowledgeGenChunkEntity
import io.zer0.muse.data.knowledge.KnowledgeGenerationEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.knowledge.domain.KnowledgeScope
import io.zer0.muse.knowledge.domain.RetrievalPolicy
import io.zer0.muse.knowledge.retrieval.KnowledgeEntryType
import io.zer0.muse.knowledge.retrieval.KnowledgeSearchRequest
import io.zer0.muse.knowledge.retrieval.V2DenseRetriever
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * R6 步骤 4(计划 R6「dense 走同空间 ANN 或数据库扫描；受限范围不支持 ANN 原生过滤时
 * 选择范围内扫描」)。
 *
 * 断言:范围内精确扫描、维度不一致降级、未就绪向量不可见、余弦只在本空间比较。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KnowledgeDenseRetrievalTest {

    private lateinit var db: MuseDb
    private lateinit var retriever: V2DenseRetriever
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).allowMainThreadQueries().build()
        retriever = V2DenseRetriever(db.knowledgeJobDao(), oversample = 4)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun FloatArray.toBytes(): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(size * 4).order(java.nio.ByteOrder.BIG_ENDIAN)
        forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    private suspend fun seedGeneration(
        generationId: String,
        docId: String,
        rows: List<Pair<String, FloatArray?>>,
    ) {
        db.knowledgeJobDao().insertGeneration(
            KnowledgeGenerationEntity(
                generationId = generationId,
                docId = docId,
                generationNo = 1,
                state = IndexGenerationState.ACTIVE.name,
                revisionId = "rev-$docId",
                chunkCount = rows.size,
                vectorReady = if (rows.all { it.second != null }) 1 else 0,
            ),
        )
        db.knowledgeJobDao().insertGenChunks(
            rows.mapIndexed { index, pair ->
                KnowledgeGenChunkEntity(
                    id = "$generationId#$index",
                    generationId = generationId,
                    chunkIndex = index,
                    content = pair.first,
                    locator = "p1s${index + 1}",
                    kind = "paragraph",
                    embeddingBlob = pair.second?.toBytes(),
                )
            },
        )
    }

    private fun request(vector: FloatArray?, topK: Int = 3) = KnowledgeSearchRequest(
        requestId = "dense",
        query = "q",
        entry = KnowledgeEntryType.DEBUG,
        scope = KnowledgeScope.AllUserLibraries,
        policy = RetrievalPolicy(topK, 0.35f, 0.5f, true, 1f, 1f, false, "", "", 0),
        datasetEpoch = 1L,
        deadlineMs = 5000L,
        queryVector = vector,
    )

    @Test
    fun `cosine ranks most similar chunk first`() = runBlocking {
        seedGeneration(
            "gen-1",
            "doc-1",
            listOf(
                "完全同向" to floatArrayOf(1f, 0f),
                "近似同向" to floatArrayOf(0.9f, 0.1f),
                "正交" to floatArrayOf(0f, 1f),
            ),
        )
        val recall = retriever.search(request(floatArrayOf(1f, 0f)), listOf("gen-1"), 3)
        assertEquals(3, recall.hits.size)
        assertEquals("完全同向", recall.hits[0].text)
        assertTrue("同向分数应接近 1", abs(recall.hits[0].score - 1f) < 0.001f)
        assertTrue("正交分数应接近 0", abs(recall.hits[2].score) < 0.001f)
    }

    @Test
    fun `chunks without vector are invisible`() = runBlocking {
        seedGeneration(
            "gen-1",
            "doc-1",
            listOf(
                "有向量" to floatArrayOf(1f, 0f),
                "无向量" to null,
            ),
        )
        val recall = retriever.search(request(floatArrayOf(1f, 0f)), listOf("gen-1"), 5)
        assertEquals("未就绪向量不得可见", 1, recall.hits.size)
        assertEquals("有向量", recall.hits[0].text)
    }

    @Test
    fun `dimension mismatch degrades instead of comparing`() = runBlocking {
        seedGeneration("gen-1", "doc-1", listOf("三维" to floatArrayOf(1f, 0f, 0f)))
        val recall = retriever.search(request(floatArrayOf(1f, 0f)), listOf("gen-1"), 5)
        assertTrue("维度不一致不得产生命中", recall.hits.isEmpty())
        assertNotNull("必须给出降级原因", recall.degradedReason)
        assertTrue(recall.degradedReason!!.contains("维度"))
    }

    @Test
    fun `no query vector degrades explicitly`() = runBlocking {
        seedGeneration("gen-1", "doc-1", listOf("有向量" to floatArrayOf(1f, 0f)))
        val recall = retriever.search(request(null), listOf("gen-1"), 5)
        assertTrue(recall.hits.isEmpty())
        assertTrue(recall.degradedReason!!.contains("未提供查询向量"))
    }

    @Test
    fun `out of scope generation is not scanned`() = runBlocking {
        seedGeneration("gen-1", "doc-1", listOf("A" to floatArrayOf(1f, 0f)))
        seedGeneration("gen-2", "doc-2", listOf("B" to floatArrayOf(1f, 0f)))
        val recall = retriever.search(request(floatArrayOf(1f, 0f)), listOf("gen-1"), 5)
        assertEquals(1, recall.hits.size)
        assertEquals("gen-1", recall.hits[0].generationId)
    }

    @Test
    fun `candidate budget truncation is marked incomplete`() = runBlocking {
        val rows = (1..20).map { "row$it" to floatArrayOf(1f, 0f) }
        seedGeneration("gen-1", "doc-1", rows)
        val recall = retriever.search(request(floatArrayOf(1f, 0f), topK = 1), listOf("gen-1"), 1)
        assertTrue("达预算必须标记不完整", recall.incomplete)
    }
}
