package io.zer0.muse.knowledge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.knowledge.IndexGenerationState
import io.zer0.muse.data.knowledge.KnowledgeV2DocEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.knowledge.domain.PublishResult
import io.zer0.muse.knowledge.index.GenerationPublisher
import io.zer0.muse.knowledge.ingest.LocatedChunk
import io.zer0.muse.knowledge.storage.KnowledgeContentStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R5 步骤 4(计划 R5:「基于 R4 词法 generation 补齐向量批次和 HybridReady 原子能力发布…
 * 补跑 Embed 中断、晚返回、取消保旧及失败不报 HybridReady 的故障矩阵」)。
 *
 * 断言:向量批任一校验失败 -> vectorReady 不置位、不发布 HybridReady、旧活动代不受影响。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KnowledgeVectorPublishTest {

    private lateinit var db: MuseDb
    private lateinit var publisher: GenerationPublisher

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build()
        publisher = GenerationPublisher(db.knowledgeJobDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun chunks(vararg texts: String): List<LocatedChunk> =
        texts.mapIndexed { i, t -> LocatedChunk(text = t, locator = "p1s${i + 1}", kind = "paragraph") }

    private fun hashOf(texts: List<String>): String =
        KnowledgeContentStore.hashOf(texts.joinToString("").toByteArray())

    private suspend fun seedDoc(docId: String, revisionId: String) {
        db.knowledgeV2Dao().insertDoc(
            KnowledgeV2DocEntity(
                docId = docId,
                ownerType = "UserLibrary",
                ownerId = "kb-1",
                currentRevisionId = revisionId,
            ),
        )
    }

    private suspend fun stagedLexicalGeneration(docId: String = "doc-1", revisionId: String = "rev-1"): Pair<String, List<String>> {
        seedDoc(docId, revisionId)
        val cs = chunks("alpha beta", "gamma delta", "epsilon")
        val gen = publisher.stageGeneration(docId, revisionId, cs)
        val err = publisher.validate(gen, revisionId, hashOf(cs.map { it.text })) { t -> hashOf(t) }
        assertNull(err)
        return gen to cs.map { it.text }
    }

    @Test
    fun `valid vector batch marks ready and hybrid publish succeeds`() = runBlocking {
        val (gen, _) = stagedLexicalGeneration()

        val vectors = listOf(
            floatArrayOf(0.1f, 0.2f, 0.3f),
            floatArrayOf(0.4f, 0.5f, 0.6f),
            floatArrayOf(0.7f, 0.8f, 0.9f),
        )
        val err = publisher.stageVectors(gen, vectors, expectedDimension = 3, embeddingSpaceId = "space-1")
        assertNull("向量批应通过", err)
        assertEquals(1, db.knowledgeJobDao().isVectorReady(gen))

        val published = publisher.publishHybrid(gen, "rev-1", "space-1")
        assertTrue("Hybrid 应发布成功", published is PublishResult.Published)
        assertEquals(gen, db.knowledgeJobDao().getActiveGeneration("doc-1"))
        assertEquals(IndexGenerationState.ACTIVE.name, db.knowledgeJobDao().getGeneration(gen)?.state)
    }

    @Test
    fun `vector dimension mismatch fails and never marks hybrid ready`() = runBlocking {
        val (gen, _) = stagedLexicalGeneration()
        val bad = listOf(
            floatArrayOf(0.1f, 0.2f),
            floatArrayOf(0.3f, 0.4f, 0.5f),
            floatArrayOf(0.6f, 0.7f),
        )
        val err = publisher.stageVectors(gen, bad, expectedDimension = 3, embeddingSpaceId = "space-1")
        assertNotNull("维度不一致必须失败", err)
        assertEquals("不得标记 vectorReady", 0, db.knowledgeJobDao().isVectorReady(gen))

        val published = publisher.publishHybrid(gen, "rev-1", "space-1")
        assertTrue("未就绪不得发布 Hybrid", published is PublishResult.ValidationFailed)
    }

    @Test
    fun `nan vector fails and keeps old generation active`() = runBlocking {
        // 先发布一个旧活动代。
        seedDoc("doc-old", "rev-old")
        val oldCs = chunks("old")
        val oldGen = publisher.stageGeneration("doc-old", "rev-old", oldCs)
        publisher.validate(oldGen, "rev-old", hashOf(oldCs.map { it.text })) { t -> hashOf(t) }
        publisher.publish(oldGen, "rev-old")

        val (gen, _) = stagedLexicalGeneration(docId = "doc-new", revisionId = "rev-new")
        val nanVectors = listOf(
            floatArrayOf(Float.NaN, 0.2f, 0.3f),
            floatArrayOf(0.4f, 0.5f, 0.6f),
            floatArrayOf(0.7f, 0.8f, 0.9f),
        )
        val err = publisher.stageVectors(gen, nanVectors, 3, "space-1")
        assertNotNull("NaN 必须失败", err)
        assertEquals(0, db.knowledgeJobDao().isVectorReady(gen))
        // 旧活动代完全不受影响。
        assertEquals(oldGen, db.knowledgeJobDao().getActiveGeneration("doc-old"))
        assertEquals(IndexGenerationState.ACTIVE.name, db.knowledgeJobDao().getGeneration(oldGen)?.state)
    }

    @Test
    fun `publish hybrid without vectors is rejected`() = runBlocking {
        val (gen, _) = stagedLexicalGeneration()
        val result = publisher.publishHybrid(gen, "rev-1", "space-1")
        assertTrue(result is PublishResult.ValidationFailed)
        assertNull("活动指针未设置", db.knowledgeJobDao().getActiveGeneration("doc-1"))
    }

    @Test
    fun `vector count mismatch fails`() = runBlocking {
        val (gen, _) = stagedLexicalGeneration()
        val tooFew = listOf(floatArrayOf(0.1f, 0.2f, 0.3f))
        val err = publisher.stageVectors(gen, tooFew, 3, "space-1")
        assertNotNull("数量不一致必须失败", err)
        assertEquals(0, db.knowledgeJobDao().isVectorReady(gen))
    }
}
