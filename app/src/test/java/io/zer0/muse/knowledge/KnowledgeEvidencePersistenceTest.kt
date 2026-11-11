package io.zer0.muse.knowledge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MessageEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.knowledge.integration.EvidenceSnapshotCodec
import io.zer0.muse.knowledge.retrieval.EvidenceAssembler
import io.zer0.muse.knowledge.retrieval.EvidenceCandidate
import io.zer0.muse.knowledge.retrieval.RetrievalOutcome
import io.zer0.muse.knowledge.retrieval.RetrievalTrace
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R7 步骤 4(计划 R7「主聊天每轮独立保存 EvidenceBundle；持久引用区分提供给模型与
 * 正文实际引用，不给未使用资料套『已使用』标签」)。
 *
 * 断言:证据快照可写入 messages.knowledgeEvidenceJson 并在重启后读回,
 * 且「提供」与「已引用」两栏独立。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KnowledgeEvidencePersistenceTest {

    private lateinit var db: MuseDb
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).allowMainThreadQueries().build()
        // messages 对 sessions 有 FK:先建会话行。
        db.sessionDao().insert(
            io.zer0.muse.data.session.SessionEntity(
                id = "s-1",
                title = "t",
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun bundle(): io.zer0.muse.knowledge.retrieval.EvidenceBundle {
        val outcome = RetrievalOutcome.Found(
            listOf(EvidenceCandidate("c1", "doc-1", "gen-1", "证据正文", "p1s1")),
            RetrievalTrace("r", "CHAT", "docs=1", emptyList(), 1, 0, 1, 1, emptyList(), 1L),
        )
        return EvidenceAssembler().assemble(
            outcome = outcome,
            budgetTokens = 300,
            docTitles = mapOf("doc-1" to "资料一"),
            traceId = "trace-1",
        )
    }

    @Test
    fun `evidence snapshot persists in message row and roundtrips`() = runBlocking {
        val snapshot = EvidenceSnapshotCodec.toSnapshot(bundle())
        val json = EvidenceSnapshotCodec.encode(snapshot)
        val message = MessageEntity(
            id = "msg-1",
            sessionId = "s-1",
            role = "assistant",
            content = "回答",
            createdAt = 1L,
            knowledgeEvidenceJson = json,
        )
        db.messageDao().upsert(message)

        val stored = db.messageDao().getById("s-1", "msg-1")!!
        assertTrue("快照必须落库", stored.knowledgeEvidenceJson.isNotBlank())
        val decoded = EvidenceSnapshotCodec.decode(stored.knowledgeEvidenceJson)
        assertEquals("trace-1", decoded.traceId)
        assertEquals(1, decoded.units.size)
        assertEquals("证据正文", decoded.units.single().sentSnippet)
        assertEquals("资料一", decoded.units.single().docTitle)
    }

    @Test
    fun `provided and actually cited are independent fields`() = runBlocking {
        val snapshot = EvidenceSnapshotCodec.toSnapshot(bundle())
        assertTrue("提供的资料有记录", snapshot.providedToModel.isNotEmpty())
        assertTrue("未解析正文引用前不标已使用", snapshot.actuallyCited.isEmpty())

        // 模拟正文引用了 [1] 之后回填。
        val withCited = snapshot.copy(actuallyCited = listOf("c1"))
        val json = EvidenceSnapshotCodec.encode(withCited)
        val decoded = EvidenceSnapshotCodec.decode(json)
        assertEquals(listOf("c1"), decoded.actuallyCited)
        assertEquals(snapshot.providedToModel, decoded.providedToModel)
    }

    @Test
    fun `old message without evidence column reads as empty`() = runBlocking {
        val message = MessageEntity(
            id = "msg-2",
            sessionId = "s-1",
            role = "assistant",
            content = "旧回答",
            createdAt = 2L,
        )
        db.messageDao().upsert(message)
        assertEquals("", db.messageDao().getById("s-1", "msg-2")!!.knowledgeEvidenceJson)
    }
}
