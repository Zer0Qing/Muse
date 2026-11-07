package io.zer0.muse.miniphone.ui.chat

import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageStatus
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.data.db.entity.MpMessageEntity
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.io.File

/**
 * 消息窗口：进页、向上翻页、未读清零（小手机 P2b 第二批）。
 *
 * 挑这个类测是因为它的错**全都看得见**：
 *
 *  - 游标挪位不对 → 上一页与这一页重一条或漏一条（用户往上翻看到重复消息）；
 *  - `hasMore`/`isLoadingMore` 判错 → 翻不到底，或者一滑到底反复发同一条查询；
 *  - 首屏不翻正顺序 → 整屏消息倒着长。
 *
 * 用真内存库跑，是因为"重一条/漏一条"这类问题只有在真的分页查询上才暴露得出来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatPagingControllerTest {

    private companion object {
        const val THREAD_ID = "thread-1"
        const val CHARACTER_ID = "char-1"
        const val TOTAL = 45
    }

    private lateinit var db: MiniPhoneDb
    private lateinit var directory: File
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

    @Before
    fun seed() = runBlocking {
        db = newInMemoryMiniPhoneDb()
        directory = File(System.getProperty("java.io.tmpdir"), "mp-paging-" + System.nanoTime()).apply { mkdirs() }
        db.threadDao().upsert(
            MpThreadEntity(
                id = THREAD_ID,
                characterId = CHARACTER_ID,
                unreadCount = 7,
                lastMessageAt = 0L,
                title = "旧标题",
            ),
        )
        // createdAt 递增写入，id 带序号：断言时直接看内容序列
        (1..TOTAL).forEach { index ->
            db.messageDao().insert(
                MpMessageEntity(
                    id = "m%02d".format(index),
                    threadId = THREAD_ID,
                    characterId = CHARACTER_ID,
                    sender = MpActorType.USER,
                    type = MpMessageType.TEXT,
                    content = "第 %02d 句".format(index),
                    createdAt = index.toLong(),
                    status = MpMessageStatus.SENT,
                ),
            )
        }
    }

    @After
    fun tearDown() {
        scope.coroutineContext[Job]?.cancel()
        db.close()
        directory.deleteRecursively()
    }

    private fun state() = MutableStateFlow(ChatUiState(threadId = THREAD_ID))

    private fun controller(
        state: MutableStateFlow<ChatUiState> = state(),
        onThreadOpened: () -> Unit = {},
    ): Pair<ChatPagingController, MutableStateFlow<ChatUiState>> {
        val writer = ChatMessageWriter(
            threadId = THREAD_ID,
            messageDao = db.messageDao(),
            threadDao = db.threadDao(),
            now = { System.currentTimeMillis() },
            newId = { "unused" },
            onStored = {},
        )
        val paging = ChatPagingController(
            threadId = THREAD_ID,
            messageDao = db.messageDao(),
            threadDao = db.threadDao(),
            writer = writer,
            state = state,
            scope = scope,
            onThreadOpened = onThreadOpened,
        )
        return paging to state
    }

    private fun settleUntil(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000L
        while (!predicate() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
    }

    /** 等库里状态：查库是挂起调用，所以要另开一个 runBlocking。 */
    private fun settleDb(predicate: suspend () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + 3_000L
        while (!predicate() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
    }

    @Test
    fun `首屏取最新一页并按时间正序摆好`() {
        var opened = 0
        val (paging, state) = controller(onThreadOpened = { opened++ })

        paging.open()
        settleUntil { state.value.messages.isNotEmpty() }

        val shown = state.value.messages.map { it.content }
        assertEquals("首屏就是一页", CHAT_PAGE_SIZE, shown.size)
        assertEquals("最新那句在最末", "第 45 句", shown.last())
        assertEquals("最早那句是 45-30+16", "第 16 句", shown.first())
        assertTrue("满一页说明还有更早的", state.value.hasMore)
        assertFalse("加载标记要收掉", state.value.isLoadingMore)
        assertEquals("会话行读到了要回调一次", 1, opened)
    }

    @Test
    fun `进页会带上群标记与群名并清掉未读`() {
        val (paging, state) = controller()

        paging.open()
        settleUntil { state.value.characterId.isNotEmpty() }

        assertEquals(CHARACTER_ID, state.value.characterId)
        assertFalse("这条不是群", state.value.isGroup)
        assertEquals("旧标题", state.value.groupTitle)
        settleDb { db.threadDao().getThread(THREAD_ID)?.unreadCount == 0 }
        runBlocking {
            assertEquals("进页要清未读", 0, db.threadDao().getThread(THREAD_ID)?.unreadCount)
        }
    }

    @Test
    fun `往上翻页既不重复也不漏`() {
        val (paging, state) = controller()
        paging.open()
        settleUntil { state.value.messages.size == CHAT_PAGE_SIZE }

        paging.loadOlder()
        settleUntil { state.value.messages.size == TOTAL }

        val contents = state.value.messages.map { it.content }
        assertEquals("全部都在且各一次", (1..TOTAL).map { "第 %02d 句".format(it) }, contents)
        assertFalse("到底了", state.value.hasMore)
        assertFalse(state.value.isLoadingMore)
    }

    @Test
    fun `到底之后再滑不查第二次`() {
        val (paging, state) = controller()
        paging.open()
        settleUntil { state.value.messages.size == CHAT_PAGE_SIZE }
        paging.loadOlder()
        settleUntil { state.value.messages.size == TOTAL }

        paging.loadOlder()
        Thread.sleep(150)

        assertEquals("不该重复追加", TOTAL, state.value.messages.size)
    }

    @Test
    fun `上一页还在路上时不并发再拉一页`() {
        // 手工摆成"上一页还没回"的样子：这条守卫防的是滑动时连环发查询
        val state = MutableStateFlow(ChatUiState(threadId = THREAD_ID, hasMore = true, isLoadingMore = true))
        val (paging, _) = controller(state = state)

        paging.loadOlder()
        Thread.sleep(150)

        assertEquals("被挡住时不该动窗口", emptyList<String>(), state.value.messages.map { it.content })
        assertTrue("也不该把别人的加载标记改掉", state.value.isLoadingMore)
    }

    @Test
    fun `单独调 markRead 也能清未读`() {
        val (paging, _) = controller()

        paging.markRead()
        settleDb { db.threadDao().getThread(THREAD_ID)?.unreadCount == 0 }

        runBlocking {
            assertEquals(0, db.threadDao().getThread(THREAD_ID)?.unreadCount)
        }
    }
}
