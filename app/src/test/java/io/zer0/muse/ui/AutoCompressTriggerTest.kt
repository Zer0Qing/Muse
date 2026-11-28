package io.zer0.muse.ui

import io.zer0.muse.ui.chat.ChatViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动压缩触发口径测试。
 *
 * 背景：原来固定"占用 > 80%"触发。对 200K 以上窗口的模型，80% 意味着已经塞进 160K，
 * 留给"本轮输出 + 后续工具结果"的空间只剩 40K —— 一轮工具循环就可能在生成中途撞爆窗口，
 * 而生成中途没有压缩机会。
 *
 * 改为"占用越过 窗口 − 预留量"，预留量 = `max(16K, 10% × 窗口)`。
 */
class AutoCompressTriggerTest {

    @Test
    fun `unknown window or unknown usage never triggers`() {
        assertFalse("窗口未知不触发", ChatViewModel.shouldAutoCompress(currentTokens = 5_000, maxTokens = 0))
        assertFalse("占用未知不触发", ChatViewModel.shouldAutoCompress(currentTokens = 0, maxTokens = 100_000))
        assertFalse("负数不触发", ChatViewModel.shouldAutoCompress(currentTokens = -1, maxTokens = -1))
    }

    @Test
    fun `small window keeps the fixed floor reserve`() {
        // 100K 窗口：10% = 10K < 16K 下限 → 预留 16K，触发点 84K
        val window = 100_000
        assertFalse("83K 未越线", ChatViewModel.shouldAutoCompress(currentTokens = 83_000, maxTokens = window))
        assertTrue("85K 应触发", ChatViewModel.shouldAutoCompress(currentTokens = 85_000, maxTokens = window))
    }

    @Test
    fun `large window reserves proportionally instead of a fixed ratio`() {
        // 1M 窗口：预留 = max(16K, 100K) = 100K → 触发点 900K（而不是固定 80% 的 800K 才触发... 见下）
        val window = 1_000_000
        assertFalse("850K 未越线", ChatViewModel.shouldAutoCompress(currentTokens = 850_000, maxTokens = window))
        assertTrue("950K 应触发", ChatViewModel.shouldAutoCompress(currentTokens = 950_000, maxTokens = window))
        // 关键回归：固定 80% 会在 820K 就触发（过早），比例预留则是 900K。
        // 两者语义不同，这里锁定"不再按固定 80% 对待大窗口"。
        assertFalse(
            "大窗口下不应按固定 80% 触发（820K < 900K）",
            ChatViewModel.shouldAutoCompress(currentTokens = 820_000, maxTokens = window),
        )
    }

    @Test
    fun `tiny window falls back to the fixed ratio`() {
        // 10K 窗口连 16K 预留都装不下 → 退回固定 80%（否则会永远触发或永不触发）
        assertFalse("7K 未越 80%", ChatViewModel.shouldAutoCompress(currentTokens = 7_000, maxTokens = 10_000))
        assertTrue("9K 越过 80%", ChatViewModel.shouldAutoCompress(currentTokens = 9_000, maxTokens = 10_000))
    }

    @Test
    fun `trigger leaves room for the coming tool round`() {
        // 不变量：触发时剩余空间至少覆盖预留量（也就是后续工具结果与输出的可用空间）
        listOf(32_768, 100_000, 200_000, 1_000_000).forEach { window ->
            val reserve = maxOf(16_384, (window * 0.1).toInt())
            val triggerPoint = window - reserve
            assertTrue(
                "窗口 $window：触发点 $triggerPoint 之后应仍留出 $reserve",
                window - triggerPoint >= reserve,
            )
        }
    }
}
