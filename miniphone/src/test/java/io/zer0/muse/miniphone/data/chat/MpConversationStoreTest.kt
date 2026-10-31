package io.zer0.muse.miniphone.data.chat

import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.data.db.MpMomentSource
import io.zer0.muse.miniphone.data.db.entity.MpMessageEntity
import io.zer0.muse.miniphone.data.db.entity.MpMomentEntity
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.data.db.entity.MpThreadMemberEntity
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 会话列表上的写操作:置顶、标记已读、删会话、清空。
 *
 * 库里跑真 Room(内存库):这几件事都不是纯函数,写得对不对只有真跑一遍 SQL 才看得出来 ——
 * 排序靠 ORDER BY、删除靠 DELETE 与事务,替身数据库证明不了任何一项。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpConversationStoreTest {

    private lateinit var db: MiniPhoneDb
    private lateinit var store: MpConversationStore

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
        store = MpConversationStore(db = db, threadDao = db.threadDao(), messageDao = db.messageDao(), memberDao = db.threadMemberDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun pinnedThreadSortsAboveTheNewerOne() = runBlocking {
        // 「旧」这条更早,但置顶之后要压过更近的「新」
        db.threadDao().upsert(thread(id = "old", characterId = "c1", lastMessageAt = 1_000L))
        db.threadDao().upsert(thread(id = "new", characterId = "c2", lastMessageAt = 5_000L))
        assertEquals(listOf("new", "old"), db.threadDao().observeThreads().first().map { it.id })

        store.setPinned("old", true)

        val ordered = db.threadDao().observeThreads().first()
        assertEquals("置顶的排最前,置顶区内再按最后消息倒序", listOf("old", "new"), ordered.map { it.id })
        assertEquals(true, ordered.first().pinned)
        assertEquals(false, ordered.last().pinned)

        store.setPinned("old", false)

        assertEquals("取消置顶后回到时间顺序", listOf("new", "old"), db.threadDao().observeThreads().first().map { it.id })
    }

    @Test
    fun deletingAThreadTakesItsMessagesAndLeavesTheOthers() = runBlocking {
        db.threadDao().upsert(thread(id = "t1", characterId = "c1", lastMessageAt = 2_000L))
        db.threadDao().upsert(thread(id = "t2", characterId = "c2", lastMessageAt = 1_000L))
        db.messageDao().insert(message(id = "m1", threadId = "t1", createdAt = 1_000L))
        db.messageDao().insert(message(id = "m2", threadId = "t1", createdAt = 2_000L))
        db.messageDao().insert(message(id = "m3", threadId = "t2", createdAt = 1_000L))
        db.threadMemberDao().upsertAll(
            listOf(
                MpThreadMemberEntity(threadId = "t1", characterId = "c1", joinedAt = 1L),
                MpThreadMemberEntity(threadId = "t1", characterId = "c2", joinedAt = 2L),
            ),
        )

        store.deleteThread("t1")

        assertNull("会话本身被删掉", db.threadDao().getThread("t1"))
        assertEquals("它名下的消息一条不剩", 0, db.messageDao().countInThread("t1"))
        assertEquals("它名下的成员行也不留", 0, db.threadMemberDao().countInThread("t1"))
        assertEquals("别的会话还在", "t2", db.threadDao().observeThreads().first().single().id)
        assertEquals("别的会话的消息还在", 1, db.messageDao().countInThread("t2"))
    }

    @Test
    fun markingThreadReadZeroesItsUnreadCount() = runBlocking {
        db.threadDao().upsert(thread(id = "t1", characterId = "c1", lastMessageAt = 3_000L, unread = 3))
        assertEquals(3, db.threadDao().unreadCount("t1"))

        store.markRead("t1")

        assertEquals(0, db.threadDao().unreadCount("t1"))
    }

    @Test
    fun clearingChatsEmptiesBothTablesButKeepsMoments() = runBlocking {
        db.threadDao().upsert(thread(id = "t1", characterId = "c1", lastMessageAt = 2_000L))
        db.threadDao().upsert(thread(id = "t2", characterId = "c2", lastMessageAt = 1_000L))
        db.messageDao().insert(message(id = "m1", threadId = "t1", createdAt = 1_000L))
        db.messageDao().insert(message(id = "m2", threadId = "t2", createdAt = 2_000L))
        db.threadMemberDao().upsertAll(
            listOf(MpThreadMemberEntity(threadId = "t1", characterId = "c1", joinedAt = 1L)),
        )
        db.momentDao().insert(
            MpMomentEntity(
                id = "moment-1",
                authorId = "c1",
                authorName = "阿离",
                content = "留着的动态",
                createdAt = 500L,
                source = MpMomentSource.AI,
            ),
        )

        store.clearAll()

        assertEquals("会话表清空", emptyList<MpThreadEntity>(), db.threadDao().observeThreads().first())
        assertEquals(0, db.messageDao().countInThread("t1"))
        assertEquals(0, db.messageDao().countInThread("t2"))
        assertEquals("成员表也跟着清空", 0, db.threadMemberDao().countInThread("t1"))
        assertEquals("朋友圈不动", 1, db.momentDao().count())
        assertEquals("动态内容也还在", "留着的动态", db.momentDao().getMoment("moment-1")?.content)
    }

    private fun thread(id: String, characterId: String, lastMessageAt: Long, unread: Int = 0): MpThreadEntity = MpThreadEntity(
        id = id,
        characterId = characterId,
        unreadCount = unread,
        lastMessageAt = lastMessageAt,
    )

    private fun message(id: String, threadId: String, createdAt: Long): MpMessageEntity = MpMessageEntity(
        id = id,
        threadId = threadId,
        characterId = "c1",
        sender = MpActorType.CHARACTER,
        type = MpMessageType.TEXT,
        content = id,
        createdAt = createdAt,
    )
}
