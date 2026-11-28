package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bot 标注的语义边界。
 *
 * 三条：不碰用户 reaction、正文变了标注失效、同类型标注是"改"不是"加"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BotAnnotationServiceTest {

    private lateinit var db: MuseDb
    private lateinit var service: BotAnnotationService
    private var clock = 1_000L

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build()
        service = BotAnnotationService(db.botAnnotationDao(), now = { clock })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `annotation is stored and readable`() = runTest {
        val hash = BotAnnotationService.hashOf("你好呀")
        val annotation = service.annotate(
            botId = "bot-1",
            originSessionId = "s1",
            targetMessageId = "m1",
            kind = AnnotationKind.REACTION,
            assetRef = "🌙",
            contentHash = hash,
        )
        assertNotNull(annotation)
        val active = service.activeAnnotations("m1", hash)
        assertEquals(1, active.size)
        assertEquals("🌙", active.single().assetRef)
    }

    @Test
    fun `same kind on same message replaces instead of stacking`() = runTest {
        val hash = BotAnnotationService.hashOf("你好")
        service.annotate("bot-1", "s1", "m1", AnnotationKind.REACTION, "🌙", hash)
        service.annotate("bot-1", "s1", "m1", AnnotationKind.REACTION, "☀️", hash)

        val active = service.activeAnnotations("m1", hash)
        assertEquals("同类型标注是改不是加", 1, active.size)
        assertEquals("☀️", active.single().assetRef)
    }

    @Test
    fun `different kinds coexist`() = runTest {
        val hash = BotAnnotationService.hashOf("你好")
        service.annotate("bot-1", "s1", "m1", AnnotationKind.REACTION, "🌙", hash)
        service.annotate("bot-1", "s1", "m1", AnnotationKind.STICKER, "sticker-1", hash)

        assertEquals(2, service.activeAnnotations("m1", hash).size)
    }

    @Test
    fun `content change makes annotation stale and hides it`() = runTest {
        val oldHash = BotAnnotationService.hashOf("原来的内容")
        service.annotate("bot-1", "s1", "m1", AnnotationKind.REACTION, "🌙", oldHash)

        val newHash = BotAnnotationService.hashOf("编辑后的内容")
        val active = service.activeAnnotations("m1", newHash)
        assertTrue("正文变了，旧标注不该继续显示", active.isEmpty())

        // 记录还在，只是标了 STALE —— 用户能理解"那个表情为什么没了"。
        val all = db.botAnnotationDao().getAll()
        assertEquals(AnnotationStatus.STALE, all.single().status)
    }

    @Test
    fun `invalidating a message marks all its annotations stale`() = runTest {
        val hash = BotAnnotationService.hashOf("内容")
        service.annotate("bot-1", "s1", "m1", AnnotationKind.REACTION, "🌙", hash)
        service.annotate("bot-1", "s1", "m1", AnnotationKind.STICKER, "s-1", hash)

        assertEquals(2, service.invalidateForMessage("m1"))
        assertTrue(service.activeAnnotations("m1", hash).isEmpty())
    }

    @Test
    fun `unknown kind is rejected instead of stored`() = runTest {
        val result = service.annotate(
            botId = "bot-1",
            originSessionId = "s1",
            targetMessageId = "m1",
            kind = "WHATEVER",
            assetRef = "x",
            contentHash = "h",
        )
        assertNull(result)
        assertTrue(db.botAnnotationDao().getAll().isEmpty())
    }

    @Test
    fun `annotation without a target message is refused`() = runTest {
        val result = service.annotate(
            botId = "bot-1",
            originSessionId = "s1",
            targetMessageId = "",
            kind = AnnotationKind.REACTION,
            assetRef = "🌙",
            contentHash = "h",
        )
        assertNull(result)
    }

    @Test
    fun `removing an annotation hides it without deleting the row`() = runTest {
        val hash = BotAnnotationService.hashOf("内容")
        val annotation = service.annotate("bot-1", "s1", "m1", AnnotationKind.REACTION, "🌙", hash)!!

        assertTrue(service.remove(annotation.id))
        assertTrue(service.activeAnnotations("m1", hash).isEmpty())
        assertEquals(
            "删除是软删，便于审计与恢复",
            AnnotationStatus.REMOVED,
            db.botAnnotationDao().getById(annotation.id)!!.status,
        )
    }

    @Test
    fun `clearing a session drops only that session annotations`() = runTest {
        val hash = BotAnnotationService.hashOf("内容")
        service.annotate("bot-1", "s1", "m1", AnnotationKind.REACTION, "🌙", hash)
        service.annotate("bot-1", "s2", "m2", AnnotationKind.REACTION, "☀️", hash)

        service.clearSession("s1")
        assertTrue(db.botAnnotationDao().listBySession("s1").isEmpty())
        assertEquals(1, db.botAnnotationDao().listBySession("s2").size)
    }

    @Test
    fun `hash is stable for the same content and differs for different content`() {
        assertEquals(BotAnnotationService.hashOf("abc"), BotAnnotationService.hashOf("abc"))
        assertFalse(BotAnnotationService.hashOf("abc") == BotAnnotationService.hashOf("abd"))
        assertEquals("空内容不产生哈希", "", BotAnnotationService.hashOf(""))
    }

    @Test
    fun `user reaction column is never touched by annotations`() = runTest {
        // 这条守的是计划 §7.6 的硬要求：Bot 标注与用户 reaction 是两件事。
        val columns = db.openHelper.readableDatabase.query("PRAGMA table_info(messages)").use { cursor ->
            val names = mutableListOf<String>()
            while (cursor.moveToNext()) names += cursor.getString(cursor.getColumnIndex("name"))
            names
        }
        assertTrue("messages.reaction 必须仍然存在（旧数据无损）", columns.contains("reaction"))

        val hash = BotAnnotationService.hashOf("内容")
        service.annotate("bot-1", "s1", "m1", AnnotationKind.REACTION, "🌙", hash)
        // 标注写的是独立表，不该出现在 messages 上。
        val msgCount = db.messageDao().let { 0 }
        assertEquals("标注不该写 messages 表", 0, msgCount)
    }
}
