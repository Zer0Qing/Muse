package io.zer0.muse.data.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 主动消息判定引擎的单测。
 *
 * 这个引擎只回答「现在能不能说」，所以测试覆盖：
 *  - 每一道硬闸都能独立拦截（免打扰 / 用户刚说过话 / 正在生成 / 冷却 / 太频繁）
 *  - 预算的地板、天花板、学习值三层关系
 *  - 反馈增量的不对称性
 *
 * 「该说什么」不在这里测 —— 那由生成那一步看着最近对话决定，判定引擎不参与。
 *
 * 用固定时间戳而不是 System.currentTimeMillis()：跨日/跨时的断言
 * 依赖绝对时间会变成 flaky，而这套逻辑最怕的就是偶发。
 */
class ProactiveTimingEngineTest {

    /** 2026-10-25 14:00 本地时间（下午，不在任何默认免打扰时段内）。 */
    private val baseNow = localMillis(2026, 10, 25, 14, 0)

    // ══════════════════════════════════════════════════════════════
    // 时机：能不能说
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `免打扰时段内直接拦截`() {
        val decision = ProactiveTimingEngine.evaluate(
            input().copy(now = localMillis(2026, 10, 25, 23, 30)),
        )
        assertEquals(ProactiveTimingEngine.Verdict.QUIET_HOUR, decision.verdict)
        assertFalse(decision.shouldSend)
    }

    @Test
    fun `全天允许时深夜也放行`() {
        val decision = ProactiveTimingEngine.evaluate(
            input().copy(now = localMillis(2026, 10, 25, 23, 30), allDayAllowed = true),
        )
        assertEquals(ProactiveTimingEngine.Verdict.OK, decision.verdict)
    }

    @Test
    fun `用户刚说过话不发`() {
        val decision = ProactiveTimingEngine.evaluate(
            input().copy(lastUserMessageAt = baseNow - 3 * 60 * 1000L),
        )
        assertEquals(ProactiveTimingEngine.Verdict.USER_ACTIVE, decision.verdict)
    }

    @Test
    fun `用户说话已过去 11 分钟就不再算活跃`() {
        val decision = ProactiveTimingEngine.evaluate(
            input().copy(lastUserMessageAt = baseNow - 11 * 60 * 1000L),
        )
        assertEquals(ProactiveTimingEngine.Verdict.OK, decision.verdict)
    }

    @Test
    fun `正在生成回复时不插话`() {
        val decision = ProactiveTimingEngine.evaluate(input().copy(isGenerating = true))
        assertEquals(ProactiveTimingEngine.Verdict.IN_CONVERSATION, decision.verdict)
    }

    @Test
    fun `冷却期内不发`() {
        val decision = ProactiveTimingEngine.evaluate(
            input().copy(relation = relation().copy(cooldownUntil = baseNow + 24 * 3600_000L)),
        )
        assertEquals(ProactiveTimingEngine.Verdict.COOLDOWN, decision.verdict)
    }

    @Test
    fun `距上一条主动消息太近不发`() {
        val decision = ProactiveTimingEngine.evaluate(
            input().copy(lastProactiveSentAt = baseNow - 30 * 60 * 1000L),
        )
        assertEquals(ProactiveTimingEngine.Verdict.TOO_SOON, decision.verdict)
    }

    @Test
    fun `间隔够了就能发`() {
        val decision = ProactiveTimingEngine.evaluate(
            input().copy(lastProactiveSentAt = baseNow - 4 * 3600_000L),
        )
        assertEquals(ProactiveTimingEngine.Verdict.OK, decision.verdict)
    }

    @Test
    fun `上一轮还在生成时也按太近拦下`() {
        // 结果账是空的（消息还没落库），但上一轮的开跑时间已经登记。
        // 这正是生成窗口内的重复发送来源：只看结果账就会以为「上一轮不存在」。
        val decision = ProactiveTimingEngine.evaluate(
            input().copy(lastProactiveSentAt = 0L, lastProactiveAttemptAt = baseNow - 5 * 60 * 1000L),
        )
        assertEquals(ProactiveTimingEngine.Verdict.TOO_SOON, decision.verdict)
        assertFalse(decision.shouldSend)
    }

    @Test
    fun `上一轮开跑已过去很久就不再拦`() {
        val decision = ProactiveTimingEngine.evaluate(
            input().copy(lastProactiveAttemptAt = baseNow - 4 * 3600_000L),
        )
        assertEquals(ProactiveTimingEngine.Verdict.OK, decision.verdict)
    }

    @Test
    fun `尝试时间不会掩盖更近的发送时间`() {
        // 两个时间戳同时存在时，谁更近就以谁为准。
        val decision = ProactiveTimingEngine.evaluate(
            input().copy(
                lastProactiveSentAt = baseNow - 10 * 60 * 1000L,
                lastProactiveAttemptAt = baseNow - 5 * 3600_000L,
            ),
        )
        assertEquals(ProactiveTimingEngine.Verdict.TOO_SOON, decision.verdict)
    }

    @Test
    fun `账本状态不再影响判定结论`() {
        // 素材来自最近对话，不来自账本。同样的输入只要时机和额度都过，就该放行。
        val decision = ProactiveTimingEngine.evaluate(input())
        assertEquals(ProactiveTimingEngine.Verdict.OK, decision.verdict)
        assertTrue(decision.shouldSend)
    }

    // ══════════════════════════════════════════════════════════════
    // 预算
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `预算耗尽时如实报告用的是预算`() {
        val decision = ProactiveTimingEngine.evaluate(
            input().copy(
                relation = relation().copy(
                    // 标准档每天 15 条，发满之后才是真正的「额度用完」
                    spentToday = 15,
                    intimacy = 0.15f,
                    intensity = ProactiveIntensity.STANDARD.name,
                ),
            ),
        )
        assertEquals(ProactiveTimingEngine.Verdict.NO_BUDGET, decision.verdict)
    }

    @Test
    fun `标准档发到第 14 条时仍有额度`() {
        val decision = ProactiveTimingEngine.evaluate(
            input().copy(
                relation = relation().copy(
                    spentToday = 14,
                    intensity = ProactiveIntensity.STANDARD.name,
                ),
            ),
        )
        assertEquals(ProactiveTimingEngine.Verdict.OK, decision.verdict)
    }

    @Test
    fun `标准档每天拿到 15 条额度`() {
        val relation = relation().copy(intimacy = 0.15f, intensity = ProactiveIntensity.STANDARD.name)
        // 额度只看档位，不再被亲密度打折
        assertEquals(15f, ProactiveTimingEngine.remainingBudget(relation), 0.001f)
    }

    @Test
    fun `黏人档额度不受亲密度影响`() {
        // intimacy 为 0 也应该是不限，而不是回到一个很小的地板值
        val relation = relation().copy(intimacy = 0f, intensity = ProactiveIntensity.CLINGY.name)
        val remaining = ProactiveTimingEngine.remainingBudget(relation)
        assertTrue("黏人档应当不限，实际 $remaining", remaining > 1_000_000f)
    }

    @Test
    fun `安静档固定 3 条不受亲密度影响`() {
        val relation = relation().copy(intimacy = 1f, intensity = ProactiveIntensity.QUIET.name)
        assertEquals(3f, ProactiveTimingEngine.remainingBudget(relation), 0.001f)
    }

    @Test
    fun `剩余额度随今日已发递减`() {
        val idle = relation().copy(intimacy = 0.5f, intensity = ProactiveIntensity.ACTIVE.name, spentToday = 0)
        val used = idle.copy(spentToday = 3)
        assertEquals(30f, ProactiveTimingEngine.remainingBudget(idle), 0.001f)
        assertEquals(27f, ProactiveTimingEngine.remainingBudget(used), 0.001f)
    }

    @Test
    fun `发满后额度归零`() {
        val relation = relation().copy(intensity = ProactiveIntensity.RESERVED.name, spentToday = 8)
        assertEquals(0f, ProactiveTimingEngine.remainingBudget(relation), 0.001f)
    }

    @Test
    fun `互动回补不再改变每日条数`() {
        val base = relation().copy(intimacy = 0.5f, spentToday = 2, intensity = ProactiveIntensity.STANDARD.name)
        val earned = base.copy(earnedToday = 1f)
        assertEquals(
            ProactiveTimingEngine.remainingBudget(base),
            ProactiveTimingEngine.remainingBudget(earned),
            0.001f,
        )
    }

    @Test
    fun `跨日需要重置预算计数`() {
        val relation = relation().copy(budgetDate = "2026-10-24")
        assertTrue(ProactiveTimingEngine.needsBudgetReset(relation, "2026-10-25"))
        assertFalse(ProactiveTimingEngine.needsBudgetReset(relation, "2026-10-24"))
    }

    // ══════════════════════════════════════════════════════════════
    // 反馈 → 亲密度
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `回复的加分远大于划掉的扣分`() {
        val replied = ProactiveLearning.intimacyDelta(OutcomeState.REPLIED)
        val dismissed = ProactiveLearning.intimacyDelta(OutcomeState.DISMISSED)
        assertTrue(replied > 0f)
        assertTrue(dismissed < 0f)
        // 划掉要连划好几次才抵得上一次回复 —— 因为划掉很可能只是他现在忙
        assertTrue(
            "正负反馈量级应明显不对称：reply=$replied dismiss=$dismissed",
            abs(replied) > abs(dismissed) * 1.5f,
        )
    }

    @Test
    fun `亲密度被钳在 0 到 1 之间`() {
        assertEquals(1f, ProactiveLearning.applyOutcome(0.99f, OutcomeState.REPLIED), 0.001f)
        assertEquals(0f, ProactiveLearning.applyOutcome(0.01f, OutcomeState.DISMISSED), 0.001f)
    }

    @Test
    fun `样本太少时不下不受欢迎的结论`() {
        // 3 条全划掉也还不算 —— 样本不足
        assertFalse(ProactiveLearning.isKindUnpopular(dismissed = 3, total = 3))
        // 4 条里 3 条被划掉才算
        assertTrue(ProactiveLearning.isKindUnpopular(dismissed = 3, total = 4))
        // 4 条里 2 条被划掉不算
        assertFalse(ProactiveLearning.isKindUnpopular(dismissed = 2, total = 4))
    }

    // ══════════════════════════════════════════════════════════════
    // 构造辅助
    // ══════════════════════════════════════════════════════════════

    /**
     * 构造辅助一律零参默认 + copy。
     *
     * 这样每个用例只写出它真正关心的那一两个差异，读的人不用数位置。
     */
    private fun input(): ProactiveTimingEngine.Input = ProactiveTimingEngine.Input(
        now = baseNow,
        relation = relation(),
        allowedHourStart = 8,
        allowedHourEnd = 22,
        allDayAllowed = false,
        lastUserMessageAt = 0L,
        isGenerating = false,
        lastProactiveSentAt = 0L,
    )

    private fun relation(): ProactiveRelationEntity = ProactiveRelationEntity(
        assistantId = "default",
        intimacy = 0.5f,
        cooldownUntil = 0L,
        intensity = ProactiveIntensity.STANDARD.name,
        budgetDate = "2026-10-25",
        spentToday = 0,
        earnedToday = 0f,
    )

    /** 构造本地时区的时间戳，避免用 fixed offset 在别的时区跑测试时漂移。 */
    private fun localMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        java.util.Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis
}
