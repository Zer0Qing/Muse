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
                    spentToday = 3,
                    intimacy = 0.15f,
                    intensity = ProactiveIntensity.STANDARD.name,
                ),
            ),
        )
        assertEquals(ProactiveTimingEngine.Verdict.NO_BUDGET, decision.verdict)
    }

    @Test
    fun `新用户在标准档下拿到地板额度`() {
        val relation = relation().copy(intimacy = 0.15f, intensity = ProactiveIntensity.STANDARD.name)
        // 标准档地板 2，学习值 1 + 0.15*4 = 1.6，取大者 → 2
        val remaining = ProactiveTimingEngine.remainingBudget(relation)
        assertEquals(2f, remaining, 0.001f)
    }

    @Test
    fun `等级地板优先于学习值`() {
        // 黏人档地板 5，但 intimacy 极低 → 仍然是 5
        val relation = relation().copy(intimacy = 0f, intensity = ProactiveIntensity.CLINGY.name)
        assertEquals(5f, ProactiveTimingEngine.remainingBudget(relation), 0.001f)
    }

    @Test
    fun `等级天花板压住学习值`() {
        // 安静档天花板 3，intimacy 拉满（学习值 5）→ 也只能 3
        val relation = relation().copy(intimacy = 1f, intensity = ProactiveIntensity.QUIET.name)
        assertEquals(3f, ProactiveTimingEngine.remainingBudget(relation), 0.001f)
    }

    @Test
    fun `黏人档不设上限`() {
        // 「不设上限」指的是不截断，不是白送额度：基础值仍是 5，
        // 但用户互动回补的额度可以一直往上加，不会被天花板截住。
        val relation =
            relation().copy(
                intimacy = 1f,
                intensity = ProactiveIntensity.CLINGY.name,
                earnedToday = 200f,
            )
        val remaining = ProactiveTimingEngine.remainingBudget(relation)
        assertTrue("黏人档不应被截断，实际 $remaining", remaining > 200f)
    }

    @Test
    fun `用户互动能回补额度`() {
        val base = relation().copy(intimacy = 0.5f, spentToday = 2)
        val earned = base.copy(earnedToday = 1f)
        val before = ProactiveTimingEngine.remainingBudget(base)
        val after = ProactiveTimingEngine.remainingBudget(earned)
        assertTrue("回补后剩余额度应增加：$before → $after", after > before)
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
