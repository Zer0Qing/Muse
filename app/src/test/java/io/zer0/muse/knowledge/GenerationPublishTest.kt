package io.zer0.muse.knowledge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.knowledge.IndexGenerationState
import io.zer0.muse.data.knowledge.KnowledgeJobDao
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
 * R4 步骤 1/3(计划 R4:「先写阶段故障注入…断言旧活动代可查且新代不半可见」;
 * 「建 staging generation…验证数量、locator、hash 与真实词法查询后事务切换活动指针」)。
 *
 * 全部用真实 in-memory Room 驱动 GenerationPublisher + KnowledgeJobDao,
 * 断言的是真实活动指针/真实代行,不是 mock 调用次数或源码字符串。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GenerationPublishTest {

    private lateinit var db: MuseDb
    private lateinit var dao: KnowledgeJobDao
    private lateinit var publisher: GenerationPublisher

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.knowledgeJobDao()
        publisher = GenerationPublisher(dao)
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

    @Test
    fun `stage does not expose generation as active before publish`() = runBlocking {
        seedDoc("doc-a", "rev-a-1")
        val cs = chunks("alpha beta", "gamma delta")

        val genId = publisher.stageGeneration("doc-a", "rev-a-1", cs)

        // 暂存代已建,但活动代指针为空 -> 查询侧看不到(新代不半可见)。
        assertEquals(IndexGenerationState.STAGING.name, dao.getGeneration(genId)?.state)
        assertNull("staging 代未被切换为活动代", dao.getActiveGeneration("doc-a"))
        // 代内 chunk/lexical 行确实写入了(只是未被活动指针引用)。
        assertEquals(2, dao.countGenChunks(genId))
        assertEquals(2, dao.countGenLexical(genId))
    }

    @Test
    fun `publish switches active pointer after validation`() = runBlocking {
        seedDoc("doc-a", "rev-a-1")
        val cs = chunks("alpha beta", "gamma delta")
        val expectedHash = hashOf(cs.map { it.text })

        val genId = publisher.stageGeneration("doc-a", "rev-a-1", cs)
        val validationErr = publisher.validate(genId, "rev-a-1", expectedHash) { texts -> hashOf(texts) }
        assertNull("校验应通过", validationErr)

        val result = publisher.publish(genId, "rev-a-1")

        assertTrue("发布成功", result is PublishResult.Published)
        assertEquals(genId, dao.getActiveGeneration("doc-a"))
        assertEquals(IndexGenerationState.ACTIVE.name, dao.getGeneration(genId)?.state)
    }

    @Test
    fun `validation fails on hash mismatch and active pointer stays with old generation`() = runBlocking {
        seedDoc("doc-a", "rev-a-1")
        // 先发布一个旧代作为活动代。
        val oldCs = chunks("old content")
        val oldGen = publisher.stageGeneration("doc-a", "rev-a-1", oldCs)
        publisher.validate(oldGen, "rev-a-1", hashOf(oldCs.map { it.text })) { t -> hashOf(t) }
        publisher.publish(oldGen, "rev-a-1")
        assertEquals(oldGen, dao.getActiveGeneration("doc-a"))

        // 新代内容与期望 hash 不符 -> 校验失败。
        val newCs = chunks("new content")
        val newGen = publisher.stageGeneration("doc-a", "rev-a-2", newCs)
        val err = publisher.validate(newGen, "rev-a-2", "deadbeef-not-matching") { t -> hashOf(t) }
        assertNotNull("hash 不符必须校验失败", err)

        // 不发布新代,活动指针仍指向旧代(旧代可查)。
        assertEquals("活动代仍是旧代", oldGen, dao.getActiveGeneration("doc-a"))
    }

    @Test
    fun `publish conflict when revision changed keeps old pointer`() = runBlocking {
        seedDoc("doc-a", "rev-a-1")
        val cs = chunks("alpha")
        val genId = publisher.stageGeneration("doc-a", "rev-a-1", cs)

        // 用错误的 expectedRevision 发布 -> Conflict,活动指针不变。
        val result = publisher.publish(genId, "rev-DIFFERENT")

        assertTrue("revision 冲突", result is PublishResult.Conflict)
        assertNull("活动指针未被设置", dao.getActiveGeneration("doc-a"))
        assertEquals(IndexGenerationState.STAGING.name, dao.getGeneration(genId)?.state)
    }

    @Test
    fun `abandon cleans staging generation but refuses active generation`() = runBlocking {
        seedDoc("doc-a", "rev-a-1")
        val cs = chunks("alpha")
        val genId = publisher.stageGeneration("doc-a", "rev-a-1", cs)

        publisher.abandon(genId)
        assertEquals(IndexGenerationState.FAILED.name, dao.getGeneration(genId)?.state)
        assertEquals("staging 行已清", 0, dao.countGenChunks(genId))

        // 发布后 abandon 活动代必须被拒。
        val cs2 = chunks("beta")
        val gen2 = publisher.stageGeneration("doc-a", "rev-a-2", cs2)
        publisher.validate(gen2, "rev-a-2", hashOf(cs2.map { it.text })) { t -> hashOf(t) }
        publisher.publish(gen2, "rev-a-2")
        publisher.abandon(gen2)
        assertEquals("活动代状态不被 abandon 改成 FAILED", IndexGenerationState.ACTIVE.name, dao.getGeneration(gen2)?.state)
        assertEquals("活动指针仍在", gen2, dao.getActiveGeneration("doc-a"))
    }
}
