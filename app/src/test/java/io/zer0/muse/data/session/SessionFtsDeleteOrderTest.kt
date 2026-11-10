package io.zer0.muse.data.session

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * 删会话时「FTS 索引必须排在 messages 级联删之前」这条顺序的守卫与实验。
 *
 * 结论（本轮做实验得出，之前只有一句注释）：
 *
 *  - **fts5 模式下顺序无所谓**：`MessageFtsRuntime.useFts5 = true` 时所有 `syncFts*` 都是空操作，
 *    索引由 DDL 里的 `messages_fts_ad`（AFTER DELETE ON messages）触发器维护；
 *  - **fts4 模式下顺序是必须的**，而 `MessageFtsRuntime.useFts5` 的**默认值就是 false** ——
 *    也就是说这是生产默认路径。`deleteFtsBySession` 的 SQL 是
 *    `DELETE FROM messages_fts WHERE message_id IN (SELECT id FROM messages WHERE sessionId = :s)`：
 *    等 messages 被级联删完再跑，子查询为空，**索引一行都删不掉**。
 *
 * 表现不是崩溃，是「会话已经删了，搜索还能搜到它的消息」——
 * 点进去要么空白、要么打开一个不存在的会话。所以这条值得钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionFtsDeleteOrderTest {

    private lateinit var context: Context
    private lateinit var database: MuseDb
    private lateinit var repository: SessionRepository
    private var previousFtsMode = false
    private lateinit var imageDirectory: File
    private val sessionId = "fts-order-session"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        previousFtsMode = MessageFtsRuntime.useFts5
        // 生产默认就是 fts4（useFts5=false），这里显式写死，避免以后默认值变了这条测试悄悄失效
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
        imageDirectory = File(context.cacheDir, "fts-order-${UUID.randomUUID()}")
        repository = SessionRepository(
            sessionDao = database.sessionDao(),
            messageDao = database.messageDao(),
            database = database,
            context = context,
            messageImageStore = MessageImageStore(imageDirectory),
        )
    }

    @After
    fun tearDown() {
        database.close()
        runCatching { imageDirectory.deleteRecursively() }
        MessageFtsRuntime.useFts5 = previousFtsMode
    }

    private fun countRows(table: String): Int = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { c ->
        if (c.moveToFirst()) c.getInt(0) else -1
    }

    private fun seed(): String {
        val now = System.currentTimeMillis()
        runBlocking {
            database.sessionDao().insert(
                SessionEntity(id = sessionId, title = "fts order", createdAt = now, updatedAt = now),
            )
            repository.appendMessage(
                sessionId,
                UIMessage(role = MessageRole.USER, content = "蓝鲸计划的第一条备注", createdAt = now),
            )
            repository.appendMessage(
                sessionId,
                UIMessage(role = MessageRole.ASSISTANT, content = "已记录蓝鲸计划", createdAt = now + 1),
            )
        }
        return sessionId
    }

    @Test
    fun `fts4 模式下写入消息会同步进索引（否则后面的断言都是空的）`() {
        seed()

        // 这条是"实验装置自检"：如果索引压根没被写过，那"删完应为 0"就是恒真断言
        assertTrue(
            "前置条件不成立：fts4 索引没被写入，这条测试将什么都测不到",
            countRows("messages_fts") >= 1,
        )
        assertEquals(2, countRows("messages"))
    }

    @Test
    fun `删会话不留孤儿索引 —— FTS 必须排在级联删之前`() {
        seed()
        val indexedBefore = countRows("messages_fts")
        assertTrue(indexedBefore >= 1)

        runBlocking { repository.deleteSession(sessionId) }

        assertEquals("messages 应已被级联删掉", 0, countRows("messages"))
        assertEquals(
            "messages_fts 留了孤儿行：会话删了但搜索还能搜到它的消息，" +
                "点进去是一个不存在的会话。原因是 deleteFtsBySession 依赖 messages 子查询，" +
                "必须排在 messages 被级联删之前",
            0,
            countRows("messages_fts"),
        )
    }
}
