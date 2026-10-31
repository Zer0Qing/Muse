package io.zer0.muse.web

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MessageEntity
import io.zer0.muse.data.session.MessageImageStore
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.SessionRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * `message.delete` / `message.favorite` 落到仓储之后的实际效果。
 *
 * 命令层的测试用假仓库验规则，这里用真 Room + 真 [MessageImageStore] 验“删干净了没”：
 * 消息行消失之外，消息的图片文件（含 Web 端为内联 base64 生成的 `{id}_inline_*`）也要跟着走。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HostMessageWritePersistenceTest {

    private lateinit var database: MuseDb
    private lateinit var imageDir: File

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).build()
        // messages_fts 不在 Room 的 entities 里（FTS 虚拟表由 MuseDb 的建库回调创建），
        // 内存库不会跑那些回调，这里按同一份 DDL 补上，否则删除时的 FTS 同步会报 no such table。
        database.openHelper.writableDatabase.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` USING FTS4(`message_id` TEXT, `content_ngram` TEXT)",
        )
        imageDir = Files.createTempDirectory("host-message-write").toFile()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun repository(): SessionRepository = SessionRepository(
        sessionDao = database.sessionDao(),
        messageDao = database.messageDao(),
        database = database,
        context = context,
        messageImageStore = MessageImageStore(imageDir),
    )

    /** 先建会话再塞消息（messages 对 sessions 有外键）。 */
    private suspend fun seedSession(sessionId: String = SESSION_ID) {
        val now = 1_000L
        database.sessionDao().insert(
            SessionEntity(
                id = sessionId,
                title = sessionId,
                createdAt = now,
                updatedAt = now,
                assistantId = "default",
            ),
        )
    }

    private suspend fun seedMessage(id: String, sessionId: String = SESSION_ID): MessageEntity {
        val entity = MessageEntity(
            id = id,
            sessionId = sessionId,
            role = "assistant",
            content = "内容",
            createdAt = 2_000L,
        )
        database.messageDao().upsert(entity)
        return entity
    }

    @Test
    fun `delete removes the row and both media file flavors`() = runBlocking {
        seedSession()
        val id = UUID.randomUUID().toString()
        seedMessage(id)
        val plain = File(imageDir, "${id}_0_deadbeef.bin").apply { writeBytes(ByteArray(8) { 1 }) }
        val inline = File(imageDir, "${id}_inline_cafebabe.png").apply { writeBytes(ByteArray(8) { 2 }) }
        assertTrue(plain.isFile)
        assertTrue(inline.isFile)

        repository().deleteMessage(id)

        assertNull("消息行必须消失", database.messageDao().getByMessageId(id))
        assertTrue("普通落盘图必须被清理", !plain.exists())
        assertTrue("内联落盘图（{id}_inline_*）必须被清理", !inline.exists())
    }

    @Test
    fun `deleting an already deleted id is a silent no-op`() = runBlocking {
        seedSession()
        val id = UUID.randomUUID().toString()
        seedMessage(id)

        repository().deleteMessage(id)
        repository().deleteMessage(id)

        assertNull(database.messageDao().getByMessageId(id))
    }

    @Test
    fun `favorite set is idempotent and readable`() = runBlocking {
        seedSession()
        val id = UUID.randomUUID().toString()
        seedMessage(id)
        val repository = repository()

        repository.setMessageFavorite(id, true)
        assertEquals(true, database.messageDao().getByMessageId(id)?.favorite)
        // 同一状态再设一次不报错、不翻转。
        repository.setMessageFavorite(id, true)
        assertEquals(true, database.messageDao().getByMessageId(id)?.favorite)

        repository.setMessageFavorite(id, false)
        assertEquals(false, database.messageDao().getByMessageId(id)?.favorite)
    }

    private companion object {
        const val SESSION_ID = "session-1"
    }
}
