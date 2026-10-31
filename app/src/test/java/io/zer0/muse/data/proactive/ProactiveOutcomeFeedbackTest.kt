package io.zer0.muse.data.proactive

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.proactive.ProactiveCandidateFilter
import io.zer0.muse.proactive.ProactiveCycleRunner
import io.zer0.muse.proactive.ProactiveScheduler
import io.zer0.muse.proactive.ProactiveStores
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * 主动消息的四种去向：划掉 / 点开 / 过期 / 被学成「别再说这类」。
 *
 * 用真实仓库 + 内存 Room 跑：这四件事全部落在 SQL 的状态条件上（终态不降级、
 * 只取最近一条、只收 PENDING），换成假仓库回放结果，等于什么都没验。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProactiveOutcomeFeedbackTest {

    private companion object {
        const val ASSISTANT = "a1"
        const val SESSION = "s1"
        const val OTHER_SESSION = "s2"
        const val HOUR = 3_600_000L

        /** 亲密度初值，与 [ProactiveRelationEntity] 的默认值一致。 */
        const val INITIAL_INTIMACY = 0.15f
        const val EPS = 1e-4f
    }

    private lateinit var database: MuseDb
    private lateinit var relations: ProactiveRelationRepository
    private lateinit var outcomes: ProactiveOutcomeRepository
    private lateinit var scheduler: ProactiveScheduler

    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build()
        relations = ProactiveRelationRepository(database.proactiveRelationDao())
        outcomes = ProactiveOutcomeRepository(database.proactiveOutcomeDao(), relations)
        scheduler = ProactiveScheduler(
            stores = ProactiveStores(
                thoughts = ProactiveThoughtRepository(
                    readDao = database.pendingThoughtDao(),
                    writeDao = database.pendingThoughtWriteDao(),
                ),
                relations = relations,
                outcomes = outcomes,
            ),
            cycle = mockk<ProactiveCycleRunner>(relaxed = true),
            appScope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ══════════════════════════════════════════════════════════════
    // 一、通知上的「这次不用」
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `点这次不用把这条记为划掉并降温`() = runTest {
        val id = outcomes.recordSent(thought = null, sessionId = SESSION, now = now, assistantId = ASSISTANT)

        scheduler.onProactiveDismissed(id)

        val stored = outcome(id)
        assertEquals(OutcomeState.DISMISSED, stored.stateEnum())
        assertNotNull("划掉的时间要写下来，否则统计不出「多久之内被划掉」", stored.dismissedAt)
        val expected = ProactiveLearning.applyOutcome(INITIAL_INTIMACY, OutcomeState.DISMISSED)
        assertEquals(expected, intimacy(), EPS)
        assertTrue("划掉应当是降温，方向别搞反", expected < INITIAL_INTIMACY)
    }

    @Test
    fun `同一条划掉两次不会重复降温`() = runTest {
        val id = outcomes.recordSent(thought = null, sessionId = SESSION, now = now, assistantId = ASSISTANT)
        scheduler.onProactiveDismissed(id)
        val first = outcome(id)
        val afterFirst = intimacy()

        scheduler.onProactiveDismissed(id)

        val second = outcome(id)
        assertEquals(OutcomeState.DISMISSED, second.stateEnum())
        assertEquals("重复点按钮不该刷新时间戳", first.dismissedAt, second.dismissedAt)
        assertEquals("重复点按钮不该再扣一次亲密度", afterFirst, intimacy(), EPS)
    }

    @Test
    fun `回复之后划掉通知不会把回复降级`() = runTest {
        val id = outcomes.recordSent(thought = null, sessionId = SESSION, now = now, assistantId = ASSISTANT)
        outcomes.resolve(id, OutcomeState.REPLIED, now)

        scheduler.onProactiveDismissed(id)

        assertEquals(OutcomeState.REPLIED, outcome(id).stateEnum())
    }

    // ══════════════════════════════════════════════════════════════
    // 二、打开会话即已读
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `打开会话把待反应的这条记为已读`() = runTest {
        val id = outcomes.recordSent(thought = null, sessionId = SESSION, now = now, assistantId = ASSISTANT)

        scheduler.onSessionOpened(SESSION)

        val stored = outcome(id)
        assertEquals(OutcomeState.OPENED, stored.stateEnum())
        assertNotNull("打开时间要写下来", stored.openedAt)
    }

    @Test
    fun `打开会话只标记这个会话里最近的一条`() = runTest {
        val older = outcomes.recordSent(thought = null, sessionId = SESSION, now = now - 2 * HOUR, assistantId = ASSISTANT)
        val newer = outcomes.recordSent(thought = null, sessionId = SESSION, now = now - HOUR, assistantId = ASSISTANT)
        val elsewhere = outcomes.recordSent(thought = null, sessionId = OTHER_SESSION, now = now, assistantId = ASSISTANT)

        scheduler.onSessionOpened(SESSION)

        assertEquals("最近一条先记已读", OutcomeState.OPENED, outcome(newer).stateEnum())
        assertEquals("更早的那条不该被一起标成已读", OutcomeState.PENDING, outcome(older).stateEnum())
        assertEquals("别的会话不该被牵连", OutcomeState.PENDING, outcome(elsewhere).stateEnum())
    }

    @Test
    fun `打开会话不覆盖已经回复的记录`() = runTest {
        val id = outcomes.recordSent(thought = null, sessionId = SESSION, now = now - HOUR, assistantId = ASSISTANT)
        outcomes.resolve(id, OutcomeState.REPLIED, now - HOUR)

        scheduler.onSessionOpened(SESSION)

        val stored = outcome(id)
        assertEquals(OutcomeState.REPLIED, stored.stateEnum())
        assertNull("已回复的记录不该补一个打开时间", stored.openedAt)
    }

    // ══════════════════════════════════════════════════════════════
    // 三、过期
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `发出一天没人理就收尾为过期`() = runTest {
        val stale = outcomes.recordSent(thought = null, sessionId = SESSION, now = now - 25 * HOUR, assistantId = ASSISTANT)

        val expired = outcomes.expireOverdue(now)

        assertEquals(1, expired)
        assertEquals(OutcomeState.EXPIRED, outcome(stale).stateEnum())
        val expected = ProactiveLearning.applyOutcome(INITIAL_INTIMACY, OutcomeState.EXPIRED)
        assertEquals("收尾也要算一次反馈，亲密度得跟着动", expected, intimacy(), EPS)
    }

    @Test
    fun `未满一天的待反应记录不动`() = runTest {
        val fresh = outcomes.recordSent(thought = null, sessionId = SESSION, now = now - 23 * HOUR, assistantId = ASSISTANT)

        val expired = outcomes.expireOverdue(now)

        assertEquals(0, expired)
        assertEquals(OutcomeState.PENDING, outcome(fresh).stateEnum())
        assertNull("没到点就不该动亲密度，连行都不该建", relations.get(ASSISTANT))
    }

    @Test
    fun `已经点开过的记录不会被过期覆盖`() = runTest {
        val opened = outcomes.recordSent(thought = null, sessionId = SESSION, now = now - 30 * HOUR, assistantId = ASSISTANT)
        outcomes.resolve(opened, OutcomeState.OPENED, now - 2 * HOUR)

        assertEquals(0, outcomes.expireOverdue(now))
        assertEquals(OutcomeState.OPENED, outcome(opened).stateEnum())
    }

    @Test
    fun `过期只跑一次`() = runTest {
        outcomes.recordSent(thought = null, sessionId = SESSION, now = now - 25 * HOUR, assistantId = ASSISTANT)

        assertEquals(1, outcomes.expireOverdue(now))
        assertEquals("第二轮不该再收一遍", 0, outcomes.expireOverdue(now))
    }

    // ══════════════════════════════════════════════════════════════
    // 四、不受欢迎的类型进候选过滤
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `反复划掉的类型会被挡在候选之外`() = runTest {
        repeat(3) { seedOutcome("d$it", ThoughtKind.MILESTONE, OutcomeState.DISMISSED, now - it * HOUR) }
        seedOutcome("r1", ThoughtKind.MILESTONE, OutcomeState.REPLIED, now - 3 * HOUR)
        seedOutcome("c1", ThoughtKind.CARE, OutcomeState.REPLIED, now - 4 * HOUR)

        val banned = outcomes.unpopularKinds(ASSISTANT, listOf(ThoughtKind.MILESTONE, ThoughtKind.CARE))

        assertEquals("只有划掉率过半的那一类该被拉黑", setOf(ThoughtKind.MILESTONE), banned)
        val candidates = listOf(
            thought("t1", ThoughtKind.MILESTONE, weight = 0.9f),
            thought("t2", ThoughtKind.CARE, weight = 0.2f),
        )
        assertTrue("前提：标准档允许这两类", ProactiveIntensity.STANDARD.allows(ThoughtKind.MILESTONE))
        assertEquals(
            "权重更高的被拉黑后，候选里只剩另一类",
            listOf("t2"),
            ProactiveCandidateFilter.select(candidates, ProactiveIntensity.STANDARD, banned).map { it.id },
        )
        assertEquals(
            "没被拉黑时两条都在，证明上面那条是过滤而不是排序丢了",
            listOf("t1", "t2"),
            ProactiveCandidateFilter.select(candidates, ProactiveIntensity.STANDARD).map { it.id },
        )
    }

    @Test
    fun `样本不足时不判定为不感兴趣`() = runTest {
        repeat(2) { seedOutcome("d$it", ThoughtKind.MILESTONE, OutcomeState.DISMISSED, now - it * HOUR) }
        repeat(3) { seedOutcome("r$it", ThoughtKind.MILESTONE, OutcomeState.REPLIED, now - (it + 3) * HOUR) }

        val banned = outcomes.unpopularKinds(ASSISTANT, listOf(ThoughtKind.MILESTONE))

        assertTrue("划掉率没过半且样本不足，不该拉黑", banned.isEmpty())
        assertFalse(outcomes.isKindUnpopular(ASSISTANT, ThoughtKind.CARE))
    }

    // ══════════════════════════════════════════════════════════════

    private suspend fun outcome(id: String): ProactiveOutcomeEntity =
        requireNotNull(database.proactiveOutcomeDao().getById(id)) { "结果账里应该有 $id" }

    private suspend fun intimacy(): Float =
        requireNotNull(relations.get(ASSISTANT)) { "有记账就该有亲密度行" }.intimacy

    private suspend fun seedOutcome(id: String, kind: ThoughtKind, state: OutcomeState, sentAt: Long) {
        database.proactiveOutcomeDao().upsert(
            ProactiveOutcomeEntity(
                id = id,
                assistantId = ASSISTANT,
                sessionId = SESSION,
                kind = kind.name,
                sentAt = sentAt,
                state = state.name,
            ),
        )
    }

    private fun thought(id: String, kind: ThoughtKind, weight: Float) = PendingThoughtEntity(
        id = id,
        assistantId = ASSISTANT,
        kind = kind.name,
        seed = "测试用",
        weight = weight,
        createdAt = now,
    )
}
