package io.zer0.muse.data.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 桌面小组件「按所选助手取最近回复」的查询测试。
 *
 * 修复前小组件取全局最近一条 assistant 消息,用户选的助手对显示内容零影响;
 * 这里钉住三件事:只看所选助手、排除小手机私信、忽略已删除会话/消息。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MessageDaoWidgetAssistantQueryTest {

    private lateinit var database: MuseDb

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun session(id: String, assistantId: String, deletedAt: Long? = null) {
        val now = 1_000L
        database.sessionDao().insert(
            SessionEntity(
                id = id,
                title = id,
                createdAt = now,
                updatedAt = now,
                assistantId = assistantId,
                deletedAt = deletedAt,
            ),
        )
    }

    private suspend fun assistantMessage(id: String, sessionId: String, content: String, createdAt: Long, deletedAt: Long? = null) {
        database.messageDao().upsert(
            MessageEntity(
                id = id,
                sessionId = sessionId,
                role = "ASSISTANT",
                content = content,
                createdAt = createdAt,
                deletedAt = deletedAt,
            ),
        )
    }

    @Test
    fun returnsLatestReplyOfTheSelectedAssistantOnly() = runTest {
        session("s-a", "assistant-a")
        session("s-b", "assistant-b")
        assistantMessage("m-a1", "s-a", "a-old", createdAt = 1_000L)
        assistantMessage("m-a2", "s-a", "a-new", createdAt = 2_000L)
        // b 的回复更晚,但用户选的是 a
        assistantMessage("m-b1", "s-b", "b-newest", createdAt = 9_000L)

        val msg = database.messageDao().getLastAssistantMessageForAssistant("assistant-a")

        assertEquals("a-new", msg?.content)
    }

    @Test
    fun returnsNullWhenSelectedAssistantHasNoReply() = runTest {
        session("s-a", "assistant-a")
        assistantMessage("m-a1", "s-a", "a-only", createdAt = 1_000L)

        assertNull(database.messageDao().getLastAssistantMessageForAssistant("assistant-b"))
    }

    @Test
    fun excludesDeletedSessions() = runTest {
        session("s-gone", "assistant-a", deletedAt = 500L)
        assistantMessage("m-gone", "s-gone", "from-deleted-session", createdAt = 9_000L)

        assertNull(database.messageDao().getLastAssistantMessageForAssistant("assistant-a"))
    }

    @Test
    fun excludesDeletedMessages() = runTest {
        session("s-a", "assistant-a")
        assistantMessage("m-ok", "s-a", "kept", createdAt = 1_000L)
        assistantMessage("m-del", "s-a", "removed", createdAt = 9_000L, deletedAt = 9_500L)

        val msg = database.messageDao().getLastAssistantMessageForAssistant("assistant-a")

        assertEquals("kept", msg?.content)
    }

    @Test
    fun ignoresUserMessages() = runTest {
        session("s-a", "assistant-a")
        assistantMessage("m-a", "s-a", "reply", createdAt = 1_000L)
        database.messageDao().upsert(
            MessageEntity(
                id = "m-user",
                sessionId = "s-a",
                role = "USER",
                content = "question",
                createdAt = 9_000L,
            ),
        )

        val msg = database.messageDao().getLastAssistantMessageForAssistant("assistant-a")

        assertEquals("reply", msg?.content)
    }
}
