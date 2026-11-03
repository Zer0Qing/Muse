package io.zer0.muse.miniphone.ui.chat

import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageStatus
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.launch
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
 * 消息落库的唯一入口（小手机 P2b）。
 *
 * 这个类只有 100 行，但三条规则错了都会**静默**出事：
 *
 *  - 库里写了、界面没追加 → 用户发完话气泡不出现，以为没发出去；
 *  - 界面追加了、库里没写 → 退出重进那句话不见了；
 *  - 状态只改一边 → 界面上永远转圈，而库里早就「已发出」。
 *
 * 所以每条断言都**两边都看**：库里读一遍、回调记一遍。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatMessageWriterTest {

    private lateinit var db: MiniPhoneDb

    private companion object {
        const val OWN = "thread-own"
        const val OTHER = "thread-other"
        const val CHAR = "char-1"
    }

    @Before
    fun seedThreads() = runBlocking {
        db = newInMemoryMiniPhoneDb()
        db.threadDao().upsert(MpThreadEntity(id = OWN, characterId = CHAR, unreadCount = 0, lastMessageAt = 1L))
        db.threadDao().upsert(MpThreadEntity(id = OTHER, characterId = CHAR, unreadCount = 0, lastMessageAt = 1L))
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun writer(
        stored: MutableList<io.zer0.muse.miniphone.ui.chat.ChatMessageUi> = mutableListOf(),
        changed: MutableList<Pair<String, MpMessageStatus>> = mutableListOf(),
        deleted: MutableList<String> = mutableListOf(),
    ): ChatMessageWriter = ChatMessageWriter(
        threadId = OWN,
        messageDao = db.messageDao(),
        threadDao = db.threadDao(),
        now = { 2_000L },
        newId = { "m-1" },
        onStored = { stored += it },
        onStatusChanged = { id, status -> changed += id to status },
        onDeleted = { deleted += it },
    )

    private fun outgoing(threadId: String = OWN, content: String = "在吗") = ChatOutgoingMessage(
        threadId = threadId,
        characterId = CHAR,
        sender = MpActorType.USER,
        type = MpMessageType.TEXT,
        content = content,
        status = MpMessageStatus.SENDING,
    )

    @Test
    fun `落一条消息要同时改库里 会话预览 和界面列表`() = runBlocking {
        val stored = mutableListOf<ChatMessageUi>()

        val id = writer(stored = stored).append(outgoing())

        assertEquals("m-1", id)
        val row = db.messageDao().getMessage(id)
        assertNotNull("库里必须有这一行", row)
        assertEquals("在吗", row?.content)
        assertEquals(MpMessageStatus.SENDING, row?.status)
        assertEquals("界面上也得追加同一行", listOf("在吗"), stored.map { it.content })
        assertEquals(1, db.messageDao().countInThread(OWN))
        val thread = db.threadDao().getThread(OWN)
        assertNotNull(thread)
        assertEquals("会话列表那行的预览要跟上", "在吗", thread?.lastMessagePreview)
        assertEquals("预览时间用同一个时间源", 2_000L, thread?.lastMessageAt)
    }

    @Test
    fun `写到别的会话不能冒进本会话的界面`() = runBlocking {
        val stored = mutableListOf<ChatMessageUi>()

        writer(stored = stored).append(outgoing(threadId = OTHER, content = "别会话的话"))

        assertEquals("别的会话不该出现在这里", emptyList<String>(), stored.map { it.content })
        assertEquals("但库里必须真的写了", 1, db.messageDao().countInThread(OTHER))
        assertEquals(0, db.messageDao().countInThread(OWN))
    }

    @Test
    fun `改状态时库里与界面上那一行一起改`() = runBlocking {
        val changed = mutableListOf<Pair<String, MpMessageStatus>>()
        targetWriter(changed).append(outgoing())

        targetWriter(changed).updateStatus("m-1", MpMessageStatus.SENT)

        assertEquals(MpMessageStatus.SENT, db.messageDao().getMessage("m-1")?.status)
        assertEquals(listOf("m-1" to MpMessageStatus.SENT), changed)
    }

    @Test
    fun `改一条不存在的状态不该让库里多出鬼行`() = runBlocking {
        val changed = mutableListOf<Pair<String, MpMessageStatus>>()

        targetWriter(changed).updateStatus("ghost", MpMessageStatus.FAILED)

        assertNull(db.messageDao().getMessage("ghost"))
        assertEquals("回调仍会带上这一条，由界面自己忽略未知 id", listOf("ghost" to MpMessageStatus.FAILED), changed)
    }

    @Test
    fun `删除只动消息表 界面同步收掉那一行`() = runBlocking {
        val deleted = mutableListOf<String>()
        targetWriter(deleted = deleted).append(outgoing())

        targetWriter(deleted = deleted).delete("m-1")

        assertNull(db.messageDao().getMessage("m-1"))
        assertEquals(0, db.messageDao().countInThread(OWN))
        assertEquals(listOf("m-1"), deleted)
        assertNotNull("会话行本身要留着，不然列表里整条会话消失", db.threadDao().getThread(OWN))
    }

    @Test
    fun `同一把闸里的读与写不会插队`() = runBlocking {
        val w = targetWriter()
        w.append(outgoing(content = "第一句"))

        // 游标读与查询之间插进一条新消息，倒序分页就会重一条、漏一条：
        // withWriteLock 存在的意义是让这两件事连续，这里验证它真的排队。
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        kotlinx.coroutines.coroutineScope {
            repeat(4) { index ->
                launch {
                    w.withWriteLock {
                        order += "in-$index"
                        kotlinx.coroutines.delay(25)
                        order += "out-$index"
                    }
                }
            }
        }

        // 每个 in 后面必须紧跟它自己的 out，中间不许出现别的持锁者
        assertTrue(
            "临界区被插队了：$order",
            order.chunked(2).all { it.size == 2 && it[0].startsWith("in-") && it[1].startsWith("out-") },
        )
    }

    private fun targetWriter(
        changed: MutableList<Pair<String, MpMessageStatus>> = mutableListOf(),
        deleted: MutableList<String> = mutableListOf(),
    ): ChatMessageWriter = writer(changed = changed, deleted = deleted)
}
