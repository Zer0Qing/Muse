package io.zer0.muse.data.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户反馈「获得主动控制节奏能力后，一条主动消息都不发了」的回归测试。
 *
 * 覆盖四处：
 *  1. 免打扰时段 ↔ 允许窗口的映射往返（保存再打开，起止不互换）
 *  2. 允许窗口为空 / 与引擎静默时段全重合的诊断
 *  3. ScoreEngine 拦截原因可复现（因子 + 阈值 + verdict）
 *  4. shouldSend 缺失 / 解析失败的区分（见 ProactiveDecisionParserTest）
 */
class QuietHoursWindowTest {

    // ── 1. 往返：语义映射必须严格互逆 ────────────────────────────────

    /** 默认配置：允许 08-22 → 免打扰 22-08。 */
    @Test
    fun `default allowed window maps to quiet hours 22 to 8`() {
        val quiet = QuietHoursWindow.AllowedWindow(8, 22).toQuietHours()
        assertEquals(22, quiet.startHour)
        assertEquals(8, quiet.endHour)
    }

    /** 免打扰 22-08 → 允许 08-22。 */
    @Test
    fun `quiet hours 22 to 8 maps back to allowed 8 to 22`() {
        val allowed = QuietHoursWindow.QuietHours(22, 8).toAllowed()
        assertEquals(8, allowed.startHour)
        assertEquals(22, allowed.endHour)
    }

    /**
     * 关键回归：用户在弹窗里保存一次，起止不得互换。
     *
     * 旧实现把「免打扰」和「允许」两个方向散落在 UI 里（初值取 allowedHourEnd、
     * 保存时写回 allowedHourEnd = s），一次保存要跨两次反向映射。
     * 本测试锁住「读出来 → 原样写回 → 再读出来」这个环是恒等的。
     */
    @Test
    fun `saving the dialog unchanged does not swap start and end`() {
        val stored = QuietHoursWindow.AllowedWindow(startHour = 8, endHour = 22)

        // 打开弹窗：读出免打扰时段
        val dialog = stored.toQuietHours()
        assertEquals(22, dialog.startHour)
        assertEquals(8, dialog.endHour)

        // 用户没动滑块，直接保存：把免打扰时段写回允许窗口
        val rewritten = dialog.toAllowed()

        assertEquals("保存后允许开始不应被改动", 8, rewritten.startHour)
        assertEquals("保存后允许结束不应被改动", 22, rewritten.endHour)
        assertFalse("默认配置不应变成空窗口", rewritten.isEmpty)
    }

    /** 所有 24×24 组合下映射都严格互逆，不只是默认值那一组。 */
    @Test
    fun `mapping is involutive for every hour pair`() {
        for (start in 0..23) {
            for (end in 0..23) {
                val allowed = QuietHoursWindow.AllowedWindow(start, end)
                val roundTripped = allowed.toQuietHours().toAllowed()
                assertEquals("start=$start end=$end 往返后 start 变了", start, roundTripped.startHour)
                assertEquals("start=$start end=$end 往返后 end 变了", end, roundTripped.endHour)
            }
        }
    }

    /** 用户把免打扰调成 23-07，存下来应是允许 07-23（跨夜免打扰）。 */
    @Test
    fun `editing quiet hours writes the complementary allowed window`() {
        val edited = QuietHoursWindow.QuietHours(startHour = 23, endHour = 7)
        val allowed = edited.toAllowed()

        assertEquals(7, allowed.startHour)
        assertEquals(23, allowed.endHour)
        // 允许 07-23 应覆盖 17 个小时，且包含白天、不含深夜
        assertEquals(16, allowed.hours().size)
        assertTrue(7 in allowed.hours())
        assertTrue(22 in allowed.hours())
        assertFalse(23 in allowed.hours())
        assertFalse(3 in allowed.hours())
    }

    // ── 2. 空窗口诊断 ──────────────────────────────────────────────

    /** 起止相同 = 空窗口 = 永远不会发送。 */
    @Test
    fun `equal start and end is an empty window`() {
        assertTrue(QuietHoursWindow.AllowedWindow(9, 9).isEmpty)
        assertTrue(QuietHoursWindow.AllowedWindow(9, 9).hours().isEmpty())
        assertTrue(QuietHoursWindow.QuietHours(9, 9).isEmptyAllowed)
    }

    /** 非空窗口才覆盖小时。 */
    @Test
    fun `non empty windows cover hours correctly`() {
        assertEquals(14, QuietHoursWindow.AllowedWindow(8, 22).hours().size)
        assertEquals(8, QuietHoursWindow.AllowedWindow(22, 6).hours().size) // 跨夜 22-06
    }

    /** 允许 23-06 整段落在引擎静默时段（22-08）内 → 结构上永不发送。 */
    @Test
    fun `allowed window fully inside engine quiet hours is detected`() {
        assertTrue(
            QuietHoursWindow.fullyShadowedByEngineQuietHours(
                QuietHoursWindow.AllowedWindow(23, 6),
                engineQuietStart = ENGINE_QUIET_START,
                engineQuietEnd = ENGINE_QUIET_END,
            ),
        )
    }

    /** 允许 22-08 与引擎静默时段完全一致 → 同样是结构上永不发送。 */
    @Test
    fun `allowed window equal to engine quiet hours is detected`() {
        assertTrue(
            QuietHoursWindow.fullyShadowedByEngineQuietHours(
                QuietHoursWindow.AllowedWindow(22, 8),
                engineQuietStart = ENGINE_QUIET_START,
                engineQuietEnd = ENGINE_QUIET_END,
            ),
        )
    }

    /** 正常白天窗口不该被误判。 */
    @Test
    fun `daytime allowed window is not flagged as shadowed`() {
        assertFalse(
            QuietHoursWindow.fullyShadowedByEngineQuietHours(
                QuietHoursWindow.AllowedWindow(8, 22),
                engineQuietStart = ENGINE_QUIET_START,
                engineQuietEnd = ENGINE_QUIET_END,
            ),
        )
    }

    /** 空窗口不参与「被遮蔽」判定（已由 isEmpty 单独表达，避免重复报警）。 */
    @Test
    fun `empty allowed window is not reported as shadowed`() {
        assertFalse(
            QuietHoursWindow.fullyShadowedByEngineQuietHours(
                QuietHoursWindow.AllowedWindow(9, 9),
                engineQuietStart = ENGINE_QUIET_START,
                engineQuietEnd = ENGINE_QUIET_END,
            ),
        )
    }

    private companion object {
        /** 引擎的静默时段。与产品默认一致，测试不依赖具体实现类的常量。 */
        const val ENGINE_QUIET_START = 22
        const val ENGINE_QUIET_END = 8
    }
}
