package io.zer0.muse.ui.chat

import io.zer0.muse.tools.ToolApprovalState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审批倒计时冻结/恢复的时序回归(全部走 runTest 虚拟时钟,不用真实等待)。
 *
 * 钉住三件事:
 *  1. 冻结期间时间不走 —— 暂停 5 分钟回来,不会凭空多出一次超时;
 *  2. 恢复后从剩余时长继续 —— 已经走掉的部分不退还,也不重置回 30 秒;
 *  3. 冻结期间被外部处理(批准/拒绝、审批从注册表注销)时立即收尾,不留悬挂协程。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalTimeoutPauseWindowTest {

    @Test
    fun `foreground focused approval still times out after thirty seconds`() = runTest {
        val startedAt = testScheduler.currentTime
        val outcome =
            awaitApprovalOutcome(
                timeoutMs = TIMEOUT_MS,
                pauseBudgetMs = PAUSE_BUDGET_MS,
                pauseSignals = { ApprovalPause(folded = false, paused = false) },
                isStillRegistered = { true },
                awaitOutcome = { slice -> withTimeoutOrNull(slice) { awaitCancellation() } },
            )
        assertEquals(ApprovalWaitOutcome.TimedOut, outcome)
        assertEquals("未冻结时仍是原来的 30 秒", TIMEOUT_MS, testScheduler.currentTime - startedAt)
    }

    @Test
    fun `paused window consumes no remaining time and resumes with the full remainder`() = runTest {
        var paused = true
        var outcome: ApprovalWaitOutcome? = null
        val job =
            launch {
                outcome =
                    awaitApprovalOutcome(
                        timeoutMs = TIMEOUT_MS,
                        pauseBudgetMs = PAUSE_BUDGET_MS,
                        pauseSignals = { ApprovalPause(folded = false, paused = paused) },
                        isStillRegistered = { true },
                        awaitOutcome = { slice -> withTimeoutOrNull(slice) { awaitCancellation() } },
                    )
            }
        advanceTimeBy(FIVE_MINUTES_MS)
        runCurrent()
        assertNull("冻结期间不应出现终局", outcome)
        assertTrue("冻结期间协程应仍在等待", job.isActive)

        val resumedAt = testScheduler.currentTime
        paused = false
        advanceUntilIdle()
        assertEquals(ApprovalWaitOutcome.TimedOut, outcome)
        // 恢复时可能正好排着一个冻结期间的探活段,所以允许一个步长的误差;关键是没重置成 30 秒。
        val elapsedAfterResume = testScheduler.currentTime - resumedAt
        assertTrue("恢复后不应短于原剩余时长($elapsedAfterResume)", elapsedAfterResume >= TIMEOUT_MS)
        assertTrue("恢复后应只剩原剩余时长,误差不超过一个探活段($elapsedAfterResume)", elapsedAfterResume <= TIMEOUT_MS + STEP_MS)
    }

    @Test
    fun `resumed countdown continues from the remaining seconds instead of restarting`() = runTest {
        var paused = false
        var outcome: ApprovalWaitOutcome? = null
        val job =
            launch {
                outcome =
                    awaitApprovalOutcome(
                        timeoutMs = TIMEOUT_MS,
                        pauseBudgetMs = PAUSE_BUDGET_MS,
                        pauseSignals = { ApprovalPause(folded = false, paused = paused) },
                        isStillRegistered = { true },
                        awaitOutcome = { slice -> withTimeoutOrNull(slice) { awaitCancellation() } },
                    )
            }
        // 先在前台走掉 20 秒,再离开 5 分钟,回来时只应剩 10 秒。
        advanceTimeBy(20_000L)
        runCurrent()
        assertNull(outcome)

        paused = true
        advanceTimeBy(FIVE_MINUTES_MS)
        runCurrent()
        assertNull(outcome)

        val resumedAt = testScheduler.currentTime
        paused = false
        advanceUntilIdle()
        assertEquals(ApprovalWaitOutcome.TimedOut, outcome)
        val elapsedAfterResume = testScheduler.currentTime - resumedAt
        val expectedRemaining = TIMEOUT_MS - 20_000L
        assertTrue("恢复后不应短于已消耗外的剩余时长($elapsedAfterResume)", elapsedAfterResume >= expectedRemaining)
        assertTrue("剩下 10 秒,不应被重置成 30 秒($elapsedAfterResume)", elapsedAfterResume <= expectedRemaining + STEP_MS)
    }

    @Test
    fun `approval resolved elsewhere during the pause settles immediately`() = runTest {
        var paused = true
        val deferred = CompletableDeferred<ToolApprovalState>()
        var outcome: ApprovalWaitOutcome? = null
        val job =
            launch {
                outcome =
                    awaitApprovalOutcome(
                        timeoutMs = TIMEOUT_MS,
                        pauseBudgetMs = PAUSE_BUDGET_MS,
                        pauseSignals = { ApprovalPause(folded = false, paused = paused) },
                        isStillRegistered = { true },
                        awaitOutcome = { slice -> withTimeoutOrNull(slice) { deferred.await() } },
                    )
            }
        advanceTimeBy(3 * STEP_MS)
        runCurrent()
        assertNull(outcome)

        deferred.complete(ToolApprovalState.Denied("denied from another path"))
        advanceUntilIdle()
        assertEquals(
            ApprovalWaitOutcome.Resolved(ToolApprovalState.Denied("denied from another path")),
            outcome,
        )
        assertFalse("外部处理后协程不应继续等待", job.isActive)
    }

    @Test
    fun `approval dropped from the registry during the pause is abandoned`() = runTest {
        var paused = true
        var registered = true
        var outcome: ApprovalWaitOutcome? = null
        val job =
            launch {
                outcome =
                    awaitApprovalOutcome(
                        timeoutMs = TIMEOUT_MS,
                        pauseBudgetMs = PAUSE_BUDGET_MS,
                        pauseSignals = { ApprovalPause(folded = false, paused = paused) },
                        isStillRegistered = { registered },
                        awaitOutcome = { slice -> withTimeoutOrNull(slice) { awaitCancellation() } },
                    )
            }
        advanceTimeBy(3 * STEP_MS)
        runCurrent()
        assertNull(outcome)

        // 会话被删 / 生成被停:注册表里已经没有这个审批,不会再有结论回来。
        registered = false
        advanceUntilIdle()
        assertEquals(ApprovalWaitOutcome.Abandoned, outcome)
        assertFalse("不应留下永不结束的协程", job.isActive)
    }

    @Test
    fun `folded reading freezes without consuming the pause budget`() = runTest {
        var folded = true
        var outcome: ApprovalWaitOutcome? = null
        val job =
            launch {
                outcome =
                    awaitApprovalOutcome(
                        timeoutMs = TIMEOUT_MS,
                        pauseBudgetMs = PAUSE_BUDGET_MS,
                        pauseSignals = { ApprovalPause(folded = folded, paused = folded) },
                        isStillRegistered = { true },
                        awaitOutcome = { slice -> withTimeoutOrNull(slice) { awaitCancellation() } },
                    )
            }
        advanceTimeBy(PAUSE_BUDGET_MS * 2)
        runCurrent()
        assertNull("折叠阅读属用户主动行为,不受暂停预算约束", outcome)
        assertTrue(job.isActive)

        folded = false
        advanceUntilIdle()
        assertEquals(ApprovalWaitOutcome.TimedOut, outcome)
    }

    @Test
    fun `unfocused pause stops waiting once its budget is exhausted`() = runTest {
        val startedAt = testScheduler.currentTime
        val outcome =
            awaitApprovalOutcome(
                timeoutMs = TIMEOUT_MS,
                pauseBudgetMs = PAUSE_BUDGET_MS,
                pauseSignals = { ApprovalPause(folded = false, paused = true) },
                isStillRegistered = { true },
                awaitOutcome = { slice -> withTimeoutOrNull(slice) { awaitCancellation() } },
            )
        assertEquals("用户一直不回来时仍要收尾", ApprovalWaitOutcome.TimedOut, outcome)
        assertEquals(PAUSE_BUDGET_MS, testScheduler.currentTime - startedAt)
    }

    @Test
    fun `remote session nobody is watching still ends as a denial after the freeze window`() = runTest {
        // A2A / 后台生成:该会话从未显示给用户(displayedSessionId 全程不是它),冻结判据恒为真。
        // 时间窗口可以放宽到冻结上限,结论不能变:到头来仍是超时拒绝,不是放行、不是悬挂。
        val startedAt = testScheduler.currentTime
        val outcome =
            awaitApprovalOutcome(
                timeoutMs = TIMEOUT_MS,
                pauseBudgetMs = PAUSE_BUDGET_MS,
                pauseSignals = {
                    ApprovalPause(
                        folded = false,
                        paused =
                        shouldPauseApprovalTimeout(
                            foldedPaused = false,
                            displayedSessionId = "session-user-is-watching",
                            sessionId = "session-from-remote-caller",
                            appInForeground = true,
                        ),
                    )
                },
                isStillRegistered = { true },
                awaitOutcome = { slice -> withTimeoutOrNull(slice) { awaitCancellation() } },
            )
        assertEquals("无人审阅时必须拒绝,只是拒绝来得晚", ApprovalWaitOutcome.TimedOut, outcome)
        assertFalse("不得以放行收尾", outcome is ApprovalWaitOutcome.Resolved)
        assertEquals("等到的是冻结窗口,既不是 30 秒也不是永久", PAUSE_BUDGET_MS, testScheduler.currentTime - startedAt)
    }

    @Test
    fun `session left in the background still ends as a denial after the freeze window`() = runTest {
        // 同一条会话(焦点没变),但应用退到后台 —— 另一条冻结路径,同样只放宽窗口不改结论。
        val outcome =
            awaitApprovalOutcome(
                timeoutMs = TIMEOUT_MS,
                pauseBudgetMs = PAUSE_BUDGET_MS,
                pauseSignals = {
                    ApprovalPause(
                        folded = false,
                        paused =
                        shouldPauseApprovalTimeout(
                            foldedPaused = false,
                            displayedSessionId = "s1",
                            sessionId = "s1",
                            appInForeground = false,
                        ),
                    )
                },
                isStillRegistered = { true },
                awaitOutcome = { slice -> withTimeoutOrNull(slice) { awaitCancellation() } },
            )
        assertEquals(ApprovalWaitOutcome.TimedOut, outcome)
        assertFalse("不得以放行收尾", outcome is ApprovalWaitOutcome.Resolved)
    }

    @Test
    fun `backgrounded app freezes the countdown of the focused session`() {
        assertTrue(
            shouldPauseApprovalTimeout(
                foldedPaused = false,
                displayedSessionId = "s1",
                sessionId = "s1",
                appInForeground = false,
            ),
        )
    }

    @Test
    fun `foreground focused session keeps counting down`() {
        assertFalse(
            shouldPauseApprovalTimeout(
                foldedPaused = false,
                displayedSessionId = "s1",
                sessionId = "s1",
                appInForeground = true,
            ),
        )
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val PAUSE_BUDGET_MS = 10 * 60_000L
        const val FIVE_MINUTES_MS = 5 * 60_000L
        const val STEP_MS = 200L
    }
}
