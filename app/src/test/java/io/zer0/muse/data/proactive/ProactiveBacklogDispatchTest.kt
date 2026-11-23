package io.zer0.muse.data.proactive

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * backlog 派发账的 DAO 语义。
 *
 * 这里钉的是「并发下的唯一性」而不是「表建出来了」：领取代、租约过期、
 * 迟到的发送回执、同一条牵挂被重复记账 —— 四种情况全部落在 SQL 的条件上，
 * 换成假仓库回放结果等于什么都没验。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProactiveBacklogDispatchTest {

    private companion object {
        const val ASSISTANT = "a1"
        const val SESSION = "s1"
        const val OTHER_SESSION = "s2"
        const val OTHER_ASSISTANT = "a2"
        const val TOKEN = "token-a"
        const val STALE_TOKEN = "token-b"
    }

    private lateinit var database: MuseDb
    private lateinit var dao: ProactiveBacklogDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            // 同步执行器：默认的 arch_disk_io 线程会在 @After 关库之后继续跑 InvalidationTracker
            // 的刷新协程，Robolectric 的 SQLite shadow 随后抛 "Illegal connection pointer"，
            // 而且是未捕获异常 —— 它会挂到下一个测试头上，把一次随机的时序问题
            // 变成另一条用例的假失败。
            .setQueryExecutor(java.util.concurrent.Executor { it.run() })
            .setTransactionExecutor(java.util.concurrent.Executor { it.run() })
            .allowMainThreadQueries()
            .build()
        dao = database.proactiveBacklogDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun dispatch(
        dispatchId: String,
        thoughtId: String = "thought-$dispatchId",
        assistantId: String = ASSISTANT,
        sessionId: String = SESSION,
        state: String = BacklogDispatchState.READY,
        claimToken: String? = null,
        claimUntil: Long? = null,
        attemptCount: Int = 0,
        nextAttemptAt: Long = 0L,
        createdAt: Long = 1_000L,
    ) = ProactiveBacklogDispatchEntity(
        dispatchId = dispatchId,
        thoughtId = thoughtId,
        assistantId = assistantId,
        sessionId = sessionId,
        state = state,
        claimToken = claimToken,
        claimUntil = claimUntil,
        attemptCount = attemptCount,
        nextAttemptAt = nextAttemptAt,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    // ── 状态闭集 ──

    @Test
    fun stateCatalogIsExactlyFiveValues() {
        assertEquals(
            setOf("READY", "CLAIMED", "SENT", "FAILED", "EXPIRED"),
            BacklogDispatchState.all,
        )
        assertEquals("多一个少一个都会让调度与 UI 的穷尽分支对不上", 5, BacklogDispatchState.all.size)
        assertTrue(BacklogDispatchState.isTerminal(BacklogDispatchState.SENT))
        assertTrue(BacklogDispatchState.isTerminal(BacklogDispatchState.FAILED))
        assertTrue(BacklogDispatchState.isTerminal(BacklogDispatchState.EXPIRED))
        assertFalse(BacklogDispatchState.isTerminal(BacklogDispatchState.READY))
        assertFalse(BacklogDispatchState.isTerminal(BacklogDispatchState.CLAIMED))
        assertFalse("未知状态不算终态，巡检要能重新归类它", BacklogDispatchState.isTerminal("ZOMBIE"))
    }

    @Test
    fun defaultRowIsReadyWithZeroAttempts() {
        // thoughtId 故意不设构造默认值：它是唯一业务键，漏传必须编译期暴露。
        val fresh = ProactiveBacklogDispatchEntity(dispatchId = "d-default", thoughtId = "thought-d-default")
        assertEquals(BacklogDispatchState.READY, fresh.state)
        assertEquals(0, fresh.attemptCount)
        assertEquals(0L, fresh.nextAttemptAt)
        assertNull(fresh.claimToken)
        assertNull(fresh.claimUntil)
        assertNull(fresh.lastError)
        assertNull(fresh.outcomeId)
    }

    @Test
    fun unknownStateIsNeverTreatedAsReady() = runTest {
        assertEquals(1L, dao.insertIfAbsent(dispatch("d-zombie")))
        database.openHelper.writableDatabase.execSQL(
            "UPDATE proactive_backlog_dispatch SET state = 'ZOMBIE' WHERE dispatchId = 'd-zombie'",
        )

        assertTrue(
            "库里的未知状态不能被当成 READY 派发出去（否则会发出语义不明的消息）",
            dao.findReady(now = Long.MAX_VALUE, limit = 10).isEmpty(),
        )
        assertEquals("读回原始值而不是抛异常或改写", "ZOMBIE", dao.getById("d-zombie")?.state)
        assertEquals(
            "未知状态也算不上终态，只能由巡检重新归类",
            false,
            BacklogDispatchState.isTerminal(dao.getById("d-zombie")?.state ?: ""),
        )
    }

    // ── 唯一性 ──

    @Test
    fun eachThoughtGetsAtMostOneDispatch() = runTest {
        assertEquals(1L, dao.insertIfAbsent(dispatch("d1", thoughtId = "thought-1")))
        assertEquals(
            "同一条牵挂第二次记账必须落空，不能生成第二条派发",
            -1L,
            dao.insertIfAbsent(dispatch("d2", thoughtId = "thought-1")),
        )

        assertEquals("d1", dao.findByThoughtId("thought-1")?.dispatchId)
        assertNull("另一条牵挂不受影响", dao.findByThoughtId("thought-2"))
        assertEquals("冲突被忽略时不能多写一行", 1, dao.getAll().size)
    }

    // ── 范围订阅 ──

    @Test
    fun observeForScopeSeesOnlyItsOwnAssistantAndSession() = runTest {
        dao.insertIfAbsent(dispatch("mine", assistantId = ASSISTANT, sessionId = SESSION, createdAt = 1_000L))
        dao.insertIfAbsent(dispatch("other-session", assistantId = ASSISTANT, sessionId = OTHER_SESSION))
        dao.insertIfAbsent(dispatch("other-assistant", assistantId = OTHER_ASSISTANT, sessionId = SESSION))

        assertEquals(
            listOf("mine"),
            dao.observeForScope(ASSISTANT, SESSION).first().map { it.dispatchId },
        )
        assertTrue(dao.observeForScope("nobody", SESSION).first().isEmpty())
    }

    @Test
    fun observeForScopeKeepsCreationOrder() = runTest {
        dao.insertIfAbsent(dispatch("late", createdAt = 3_000L))
        dao.insertIfAbsent(dispatch("early", createdAt = 1_000L))
        dao.insertIfAbsent(dispatch("middle", createdAt = 2_000L))

        assertEquals(
            listOf("early", "middle", "late"),
            dao.observeForScope(ASSISTANT, SESSION).first().map { it.dispatchId },
        )
    }

    // ── 待发查询 ──

    @Test
    fun findReadyHonoursStateDueTimeAndLimit() = runTest {
        dao.insertIfAbsent(dispatch("due-early", nextAttemptAt = 100L, createdAt = 3_000L))
        dao.insertIfAbsent(dispatch("due-late", nextAttemptAt = 200L, createdAt = 1_000L))
        dao.insertIfAbsent(dispatch("not-due", nextAttemptAt = 900L))
        dao.insertIfAbsent(dispatch("claimed", state = BacklogDispatchState.CLAIMED, claimToken = "t", claimUntil = 10L))
        dao.insertIfAbsent(dispatch("sent", state = BacklogDispatchState.SENT))

        assertEquals(
            "只取到期的 READY，按到期时间升序",
            listOf("due-early", "due-late"),
            dao.findReady(now = 500L, limit = 10).map { it.dispatchId },
        )
        assertEquals(
            "limit 必须在 SQL 里生效，而不是取回内存再切",
            listOf("due-early"),
            dao.findReady(now = 500L, limit = 1).map { it.dispatchId },
        )
        assertEquals(
            "到期时间正好等于 now 也算到期",
            listOf("due-early", "due-late"),
            dao.findReady(now = 200L, limit = 10).map { it.dispatchId },
        )
        assertTrue("CLAIMED / SENT 不得混进待发列表", dao.findReady(now = 1_000L, limit = 10).none { it.state != BacklogDispatchState.READY })
    }

    @Test
    fun findReadyBreaksTiesByDispatchId() = runTest {
        // 同一次巡检写进来的行，到期时间与创建时间往往一模一样；
        // 少了 dispatchId 这层 tiebreak，SQLite 给出的相对顺序没有承诺。
        dao.insertIfAbsent(dispatch("b-tie", nextAttemptAt = 100L, createdAt = 1_000L))
        dao.insertIfAbsent(dispatch("a-tie", nextAttemptAt = 100L, createdAt = 1_000L))

        assertEquals(
            "同到期时间、同创建时间时按 dispatchId 定序，避免每轮顺序抖动",
            listOf("a-tie", "b-tie"),
            dao.findReady(now = 100L, limit = 10).map { it.dispatchId },
        )
        assertEquals(
            "顺序必须稳定：重复查询结果一致（否则 limit 截断会随机漏行）",
            listOf("a-tie", "b-tie"),
            dao.findReady(now = 100L, limit = 10).map { it.dispatchId },
        )
        assertEquals(
            "limit 截断按同一顺序取前 N 条",
            listOf("a-tie"),
            dao.findReady(now = 100L, limit = 1).map { it.dispatchId },
        )
    }

    // ── 领取 ──

    @Test
    fun claimTakesTheRowAndBumpsAttemptCount() = runTest {
        dao.insertIfAbsent(dispatch("d1"))

        assertEquals(1, dao.claim("d1", TOKEN, claimUntil = 5_000L, now = 1_000L))
        dao.getById("d1")?.let { row ->
            assertEquals(BacklogDispatchState.CLAIMED, row.state)
            assertEquals(TOKEN, row.claimToken)
            assertEquals(5_000L, row.claimUntil)
            assertEquals("领取即是一次尝试", 1, row.attemptCount)
            assertEquals(1_000L, row.updatedAt)
        } ?: error("d1 应在库里")
    }

    @Test
    fun claimIsLostByTheSecondWorker() = runTest {
        dao.insertIfAbsent(dispatch("d1"))
        assertEquals(1, dao.claim("d1", TOKEN, claimUntil = 5_000L, now = 1_000L))

        assertEquals(
            "已被别人领走时 affected rows = 0，调用方据此退让",
            0,
            dao.claim("d1", STALE_TOKEN, claimUntil = 9_000L, now = 2_000L),
        )
        dao.getById("d1")?.let { row ->
            assertEquals("先到的 token 不能被覆盖", TOKEN, row.claimToken)
            assertEquals(5_000L, row.claimUntil)
            assertEquals("失败的抢占不许把尝试次数刷高", 1, row.attemptCount)
        } ?: error("d1 应在库里")

        assertEquals("不存在的行同样返回 0", 0, dao.claim("missing", TOKEN, 5_000L, 1_000L))
    }

    @Test
    fun claimRefusesRowsThatAreNotDueYet() = runTest {
        dao.insertIfAbsent(dispatch("d1", nextAttemptAt = 10_000L))

        assertEquals(0, dao.claim("d1", TOKEN, claimUntil = 20_000L, now = 5_000L))
        val row = dao.getById("d1") ?: error("d1 应在库里")
        assertEquals(BacklogDispatchState.READY, row.state)
        assertNull(row.claimToken)
        assertEquals(0, row.attemptCount)

        assertEquals("到期后可以领取", 1, dao.claim("d1", TOKEN, claimUntil = 20_000L, now = 10_000L))
    }

    @Test
    fun claimDoesNotResurrectTerminalRows() = runTest {
        listOf(BacklogDispatchState.SENT, BacklogDispatchState.FAILED, BacklogDispatchState.EXPIRED).forEach { state ->
            val id = "d-$state"
            dao.insertIfAbsent(dispatch(id, state = state))
            assertEquals("$state 是终态，不能再被领取", 0, dao.claim(id, TOKEN, 9_999L, 1_000L))
            assertEquals(state, dao.getById(id)?.state)
        }
    }

    // ── 租约回收 ──

    @Test
    fun releaseExpiredClaimsReturnsOnlyDeadLeases() = runTest {
        dao.insertIfAbsent(
            dispatch("expired", state = BacklogDispatchState.CLAIMED, claimToken = TOKEN, claimUntil = 1_000L, attemptCount = 1),
        )
        dao.insertIfAbsent(
            dispatch("alive", state = BacklogDispatchState.CLAIMED, claimToken = TOKEN, claimUntil = 9_000L, attemptCount = 1),
        )
        dao.insertIfAbsent(
            dispatch("broken", state = BacklogDispatchState.CLAIMED, claimToken = TOKEN, claimUntil = null, attemptCount = 1),
        )
        dao.insertIfAbsent(dispatch("sent", state = BacklogDispatchState.SENT))

        assertEquals(
            "租约到期的和没有租约的坏行都要收回来，未到期与终态不能动",
            2,
            dao.releaseExpiredClaims(now = 2_000L),
        )
        dao.getById("expired")?.let { row ->
            assertEquals(BacklogDispatchState.READY, row.state)
            assertNull(row.claimToken)
            assertNull(row.claimUntil)
            assertEquals("回收不抹掉尝试次数（下一次重试要接着算退避）", 1, row.attemptCount)
        } ?: error("expired 应在库里")
        assertEquals(BacklogDispatchState.CLAIMED, dao.getById("alive")?.state)
        assertEquals(BacklogDispatchState.SENT, dao.getById("sent")?.state)

        assertEquals("收回后可以重新领取", 1, dao.claim("expired", STALE_TOKEN, claimUntil = 5_000L, now = 2_100L))
        assertEquals(2, dao.getById("expired")?.attemptCount)
    }

    // ── 发送结果 ──

    @Test
    fun markSentNeedsTheLiveToken() = runTest {
        dao.insertIfAbsent(dispatch("d1"))
        dao.claim("d1", TOKEN, claimUntil = 5_000L, now = 1_000L)

        assertEquals(
            "不是持有者就不能把这条标成已发",
            0,
            dao.markSent("d1", STALE_TOKEN, outcomeId = "o1", updatedAt = 2_000L),
        )
        assertNull(dao.getById("d1")?.outcomeId)

        assertEquals(1, dao.markSent("d1", TOKEN, outcomeId = "o1", updatedAt = 2_000L))
        dao.getById("d1")?.let { row ->
            assertEquals(BacklogDispatchState.SENT, row.state)
            assertEquals("o1", row.outcomeId)
            assertNull("发出后租约必须清掉", row.claimToken)
            assertNull(row.claimUntil)
            assertEquals(2_000L, row.updatedAt)
        } ?: error("d1 应在库里")

        assertEquals(
            "同一条不能发两次（重复回执必须落空，否则用户会收到两遍）",
            0,
            dao.markSent("d1", TOKEN, outcomeId = "o2", updatedAt = 3_000L),
        )
        assertEquals("o1", dao.getById("d1")?.outcomeId)
    }

    @Test
    fun markRetryReturnsRowToReadyWithBackoff() = runTest {
        dao.insertIfAbsent(dispatch("d1"))
        dao.claim("d1", TOKEN, claimUntil = 5_000L, now = 1_000L)

        assertEquals(
            "过期 token 的迟到重试不能改写这条",
            0,
            dao.markRetry("d1", STALE_TOKEN, nextAttemptAt = 10_000L, error = "迟到的失败", updatedAt = 2_000L),
        )
        assertEquals(1, dao.markRetry("d1", TOKEN, nextAttemptAt = 10_000L, error = "网络失败", updatedAt = 2_000L))

        dao.getById("d1")?.let { row ->
            assertEquals("可重试失败回到 READY，而不是终态", BacklogDispatchState.READY, row.state)
            assertEquals(10_000L, row.nextAttemptAt)
            assertEquals("网络失败", row.lastError)
            assertNull(row.claimToken)
            assertNull(row.claimUntil)
            assertEquals("重试不额外加次数，领取时才加", 1, row.attemptCount)
        } ?: error("d1 应在库里")

        assertTrue("退避期内不得重新进入待发队列", dao.findReady(now = 9_999L, limit = 10).isEmpty())
        assertEquals(listOf("d1"), dao.findReady(now = 10_000L, limit = 10).map { it.dispatchId })
    }

    @Test
    fun markFailedIsTerminalAndTokenGuarded() = runTest {
        dao.insertIfAbsent(dispatch("d1"))
        dao.claim("d1", TOKEN, claimUntil = 5_000L, now = 1_000L)

        assertEquals(0, dao.markFailed("d1", STALE_TOKEN, error = "迟到的失败", updatedAt = 2_000L))
        assertEquals(BacklogDispatchState.CLAIMED, dao.getById("d1")?.state)

        assertEquals(1, dao.markFailed("d1", TOKEN, error = "模板缺失", updatedAt = 2_000L))
        dao.getById("d1")?.let { row ->
            assertEquals(BacklogDispatchState.FAILED, row.state)
            assertEquals("模板缺失", row.lastError)
            assertNull(row.claimToken)
        } ?: error("d1 应在库里")
        assertTrue("终态不再出现在待发列表", dao.findReady(now = Long.MAX_VALUE, limit = 10).isEmpty())
        assertEquals(0, dao.claim("d1", TOKEN, 9_999L, 3_000L))
    }

    @Test
    fun markExpiredOnlyTouchesUnfinishedRows() = runTest {
        dao.insertIfAbsent(dispatch("ready"))
        dao.insertIfAbsent(dispatch("claimed-live", state = BacklogDispatchState.CLAIMED, claimToken = TOKEN, claimUntil = 9_000L))
        dao.insertIfAbsent(dispatch("claimed-dead", state = BacklogDispatchState.CLAIMED, claimToken = TOKEN, claimUntil = 1_000L))
        dao.insertIfAbsent(dispatch("claimed-broken", state = BacklogDispatchState.CLAIMED, claimToken = TOKEN, claimUntil = null))
        dao.insertIfAbsent(dispatch("sent", state = BacklogDispatchState.SENT))

        assertEquals(1, dao.markExpired("ready", now = 2_000L, updatedAt = 2_000L))
        assertEquals(
            "租约还没到期就不能作废：worker 可能正握着这一条在发",
            0,
            dao.markExpired("claimed-live", now = 2_000L, updatedAt = 2_000L),
        )
        assertEquals("租约已到期可以作废", 1, dao.markExpired("claimed-dead", now = 2_000L, updatedAt = 2_000L))
        assertEquals("没有租约的坏行永远等不到到期判定，一并作废", 1, dao.markExpired("claimed-broken", now = 2_000L, updatedAt = 2_000L))
        assertEquals("已发出的消息不能因为牵挂过期被改写", 0, dao.markExpired("sent", now = 2_000L, updatedAt = 2_000L))

        assertEquals(BacklogDispatchState.EXPIRED, dao.getById("ready")?.state)
        assertEquals(BacklogDispatchState.CLAIMED, dao.getById("claimed-live")?.state)
        assertEquals("活租约的 token 不能被清掉", TOKEN, dao.getById("claimed-live")?.claimToken)
        assertEquals(BacklogDispatchState.EXPIRED, dao.getById("claimed-dead")?.state)
        assertNull("过期时也要释放租约", dao.getById("claimed-dead")?.claimToken)
        assertEquals(BacklogDispatchState.EXPIRED, dao.getById("claimed-broken")?.state)
        assertEquals(BacklogDispatchState.SENT, dao.getById("sent")?.state)
        assertEquals("重复过期是空操作", 0, dao.markExpired("ready", now = 3_000L, updatedAt = 3_000L))

        assertEquals(
            "租约到期之后再作废是可以的（那时的持有者早已失去回执资格）",
            1,
            dao.markExpired("claimed-live", now = 9_000L, updatedAt = 9_000L),
        )
        assertEquals(BacklogDispatchState.EXPIRED, dao.getById("claimed-live")?.state)
    }
}
