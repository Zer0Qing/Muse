package io.zer0.muse.miniphone.data.moment

import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.data.db.MpMomentSource
import io.zer0.muse.miniphone.data.db.entity.MpMessageEntity
import io.zer0.muse.miniphone.data.db.entity.MpMomentEntity
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 角色自发内容的素材：最近聊了什么、上一条发过什么、现在几点、这一条写什么。
 *
 * 库跑真 Room(内存库):「取最后 8 条」「截到 100 字」「上下在前、指令在后」这类断言
 * 必须落在真的查询结果与真的拼接顺序上,用替身就变成了自证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpCharacterMaterialTest {

    private lateinit var db: MiniPhoneDb

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `朋友圈带最近八条对话并标出说话方`() = runBlocking {
        seedThread()
        seedMessages(count = 12)

        val hint = material(at = EVENING).forMoment(CHARACTER_ID)

        val turns = hint.lineSequence().filter { it.startsWith("用户：") || it.startsWith("我：") }.toList()
        assertEquals("只取最后 8 条", 8, turns.size)
        assertEquals("最早那条被挤掉", "用户：在吗 5", turns.first())
        assertEquals("最新那条在最后", "我：在吗 12", turns.last())
        assertTrue("用户的话标「用户」", turns[0].startsWith("用户："))
        assertTrue("角色的话标「我」", turns[1].startsWith("我："))
    }

    @Test
    fun `没有聊过时明确说明`() = runBlocking {
        seedThread()

        val hint = material(at = EVENING).forMoment(CHARACTER_ID)

        assertTrue(hint, hint.contains("还没有和用户聊过"))
        assertFalse("没有会话就不该编造对话", hint.contains("用户："))
    }

    @Test
    fun `每条对话截到一百字`() = runBlocking {
        seedThread()
        val longText = (1..40).joinToString("") { index -> "第${index}段。" }
        insertMessage(index = 1, sender = MpActorType.USER, content = longText)

        val hint = material(at = EVENING).forMoment(CHARACTER_ID)

        assertTrue("保留前 100 字", hint.contains(longText.take(100)))
        assertFalse("第 101 字起要丢掉", hint.contains(longText.take(101)))
    }

    @Test
    fun `朋友圈把上一条动态作为别重复的参照`() = runBlocking {
        seedThread()
        insertMoment(id = "old", content = "很久以前的一条", at = 10L)
        val latest = (1..40).joinToString("") { index -> "第${index}段。" }
        insertMoment(id = "new", content = latest, at = 20L)

        val hint = material(at = EVENING).forMoment(CHARACTER_ID)

        assertTrue("带最新那条的前 60 字", hint.contains(latest.take(60)))
        assertFalse("超出 60 字的部分不要", hint.contains(latest.take(61)))
        assertTrue(hint.contains("别和上一条重复同样的话题"))
        assertFalse("只参照最新那条", hint.contains("很久以前的一条"))
    }

    @Test
    fun `私信带对话与时间但不带动参照`() = runBlocking {
        seedThread()
        seedMessages(count = 2)
        insertMoment(id = "m1", content = "今天风很大", at = 10L)

        val hint = material(at = EVENING).forProactiveChat(CHARACTER_ID)

        assertTrue(hint, hint.contains("用户：在吗 1"))
        assertTrue(hint, hint.contains("现在是周二晚上九点"))
        assertFalse("私信与朋友圈无关", hint.contains("上一条我发的动态"))
        assertFalse("私信不看动态", hint.contains("今天风很大"))
    }

    @Test
    fun `朋友圈顺序是上下文 时间 收尾指令`() = runBlocking {
        seedThread()
        seedMessages(count = 2)
        insertMoment(id = "m1", content = "今天风很大", at = 10L)

        val hint = material(at = EVENING).forMoment(CHARACTER_ID, focus = "晚上收工，随便聊聊今天")

        assertTrue(hint, hint.startsWith("最近和用户的对话（旧→新）："))
        assertTrue("上一条动态在对话之后", hint.indexOf("上一条我发的动态") > hint.indexOf("用户：在吗 1"))
        assertTrue("时间在动态之后", hint.indexOf("现在是周二") > hint.indexOf("上一条我发的动态"))
        assertTrue("收尾指令在最后一句", hint.endsWith("这次可以写：晚上收工，随便聊聊今天"))
    }

    @Test
    fun `私信顺序是上下文 时间 收尾指令`() = runBlocking {
        seedThread()
        seedMessages(count = 2)

        val hint = material(at = EVENING).forProactiveChat(CHARACTER_ID, focus = "晚上，随口聊两句")

        assertTrue(hint, hint.startsWith("最近和用户的对话（旧→新）："))
        assertTrue("时间在对话之后", hint.indexOf("现在是周二") > hint.indexOf("用户：在吗 1"))
        assertTrue("收尾指令在最后一句", hint.endsWith("此刻的由头：晚上，随口聊两句"))
    }

    @Test
    fun `带上当前时间与时段`() = runBlocking {
        val evening = material(at = EVENING).forMoment(CHARACTER_ID)
        val dawn = material(at = DAWN).forMoment(CHARACTER_ID)

        assertTrue(evening, evening.contains("周二"))
        assertTrue(evening, evening.contains("晚上九点"))
        assertTrue("晚上属于「夜」", evening.contains("（夜）"))
        assertTrue("早上属于「早」", dawn.contains("（早）"))
    }

    private fun material(at: Long): MpCharacterMaterial = MpCharacterMaterial(
        threadDao = db.threadDao(),
        messageDao = db.messageDao(),
        momentDao = db.momentDao(),
        now = { at },
        zone = ZONE,
    )

    private suspend fun seedThread() {
        db.threadDao().upsert(MpThreadEntity(id = THREAD_ID, characterId = CHARACTER_ID, lastMessageAt = 12L))
    }

    private suspend fun seedMessages(count: Int) {
        (1..count).forEach { index ->
            insertMessage(
                index = index,
                sender = if (index % 2 == 1) MpActorType.USER else MpActorType.CHARACTER,
                content = "在吗 $index",
            )
        }
    }

    private suspend fun insertMessage(index: Int, sender: MpActorType, content: String) {
        db.messageDao().insert(
            MpMessageEntity(
                id = "msg-$index",
                threadId = THREAD_ID,
                characterId = CHARACTER_ID,
                sender = sender,
                type = MpMessageType.TEXT,
                content = content,
                createdAt = index.toLong(),
            ),
        )
    }

    private suspend fun insertMoment(id: String, content: String, at: Long) {
        db.momentDao().insert(
            MpMomentEntity(
                id = id,
                authorId = CHARACTER_ID,
                authorName = CHARACTER_NAME,
                content = content,
                createdAt = at,
                source = MpMomentSource.AI,
            ),
        )
    }

    private companion object {
        const val CHARACTER_ID: String = "c1"
        const val CHARACTER_NAME: String = "阿离"
        const val THREAD_ID: String = "t1"

        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        /** 周二 21:00。 */
        val EVENING: Long = LocalDateTime.of(2026, 5, 12, 21, 0).atZone(ZONE).toInstant().toEpochMilli()

        /** 周二 06:00。 */
        val DAWN: Long = LocalDateTime.of(2026, 5, 12, 6, 0).atZone(ZONE).toInstant().toEpochMilli()
    }
}
