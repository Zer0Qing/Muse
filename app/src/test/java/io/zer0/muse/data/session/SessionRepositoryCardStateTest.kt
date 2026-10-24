package io.zer0.muse.data.session

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.ui.markdown.appendCardStateToMessage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * v2.6.8: 数据出口的 card_state 清洗 —— 会话预览与 FTS 索引。
 *
 * 这两处原来是「读者里最靠后、影响最持久」的两个：
 *  - 会话预览（stripForDisplay）不认 card_state，列表里直接显示 `cardId=…`；
 *  - FTS 索引直接吃 content 原文，于是 `card_state`、`cardId=`、JSON 键值都能被搜到，
 *    搜出来的片段还是一段 JSON。
 *
 * 现在两者分别走 displayContent / searchableContent。这条测试从真实数据库往返验证，
 * 不依赖调用方是否记得手动清洗。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionRepositoryCardStateTest {

    private lateinit var context: Context
    private lateinit var database: MuseDb
    private lateinit var repository: SessionRepository
    private var previousFtsMode: Boolean = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        previousFtsMode = MessageFtsRuntime.useFts5
        // FTS4 分支才由 SessionRepository 手动写 ngram；FTS5 走 external content 触发器，
        // 索引文本仍是 messages.content 原文（见报告里的取舍说明）。
        MessageFtsRuntime.useFts5 = false
        database = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(MessageFtsDdl.createSql(useFts5 = false))
                    }

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.setForeignKeyConstraintsEnabled(true)
                    }
                },
            )
            .build()
        repository = SessionRepository(
            sessionDao = database.sessionDao(),
            messageDao = database.messageDao(),
            database = database,
            context = context,
            messageImageStore = MessageImageStore(File(context.cacheDir, "card-state-${UUID.randomUUID()}")),
        )
    }

    @After
    fun tearDown() {
        database.close()
        MessageFtsRuntime.useFts5 = previousFtsMode
    }

    @Test
    fun `session preview never shows the card state tail`() = runTest {
        val sessionId = newSession("preview")
        repository.appendMessage(
            sessionId,
            UIMessage(
                role = MessageRole.USER,
                content = withCardState("把睡眠调到 6 小时"),
                createdAt = System.currentTimeMillis(),
            ),
        )

        val preview = database.sessionDao().getById(sessionId)!!.lastMessagePreview

        assertEquals("把睡眠调到 6 小时", preview)
        assertFalse(preview.contains("card_state"))
        assertFalse(preview.contains("cardId="))
        assertFalse(preview.contains("hours"))
    }

    @Test
    fun `fts index does not contain the card state tail`() = runTest {
        val sessionId = newSession("fts")
        val messageId = repository.appendMessage(
            sessionId,
            UIMessage(
                role = MessageRole.USER,
                content = withCardState("把睡眠调到 6 小时"),
                createdAt = System.currentTimeMillis(),
            ),
        )

        val indexed = ftsContentFor(messageId)

        assertTrue("正文摘要仍应可检索", indexed.contains("睡眠"))
        assertFalse("卡片状态标记不能进索引", indexed.contains("card"))
        assertFalse("卡片身份不能进索引", indexed.contains("cardid"))
        assertFalse("JSON 键值不能进索引", indexed.contains("hours"))
    }

    @Test
    fun `plain message keeps indexing and previewing verbatim`() = runTest {
        val sessionId = newSession("plain")
        val messageId = repository.appendMessage(
            sessionId,
            UIMessage(
                role = MessageRole.USER,
                content = "普通一句话",
                createdAt = System.currentTimeMillis(),
            ),
        )

        assertEquals("普通一句话", database.sessionDao().getById(sessionId)!!.lastMessagePreview)
        assertEquals("普通 通一 一句 句话 话", ftsContentFor(messageId).trim())
    }

    private suspend fun newSession(tag: String): String {
        val now = System.currentTimeMillis()
        val id = "session-$tag-$now"
        database.sessionDao().insert(SessionEntity(id = id, title = tag, createdAt = now, updatedAt = now))
        return id
    }

    private fun ftsContentFor(messageId: String): String = database.openHelper.readableDatabase
        .query("SELECT content_ngram FROM messages_fts WHERE message_id = ?", arrayOf(messageId))
        .use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
        }

    private fun withCardState(text: String): String = appendCardStateToMessage(text = text, cardId = "msg-123", data = """{"hours":6}""")
}
