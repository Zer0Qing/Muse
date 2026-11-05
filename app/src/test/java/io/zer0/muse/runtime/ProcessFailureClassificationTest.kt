package io.zer0.muse.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子进程失败分类器（DSHGO 阶段 1 的最小状态机）。
 *
 * 要守住的三条判据，每条都配了反向断言：
 *  1. 「根本没起来」≠「起来后死了」—— 前者的正确动作是修环境，重试无意义；
 *  2. **用户主动停止绝不能算失败** —— 否则任何计数型恢复策略都会被正常操作污染；
 *  3. 磁盘满单独成类 —— 继续重试只会继续写坏。
 */
class ProcessFailureClassificationTest {

    @Test
    fun `从未就绪且非零退出判为启动失败且不值得重试`() {
        val kind = classifyProcessExit(exitCode = 127, everReady = false)
        assertEquals(ProcessFailureKind.NEVER_STARTED, kind)
        assertFalse("起不来还自动重试，只会把日志刷满", kind.autoRetry)
    }

    @Test
    fun `退出码为 0 但从未就绪不谎报成崩溃`() {
        // 进程自己安静退掉了。报"崩溃"会把人往环境坏了的方向带。
        assertEquals(ProcessFailureKind.NO_RESULT, classifyProcessExit(exitCode = 0, everReady = false))
    }

    @Test
    fun `用户主动停止优先于一切其他信号`() {
        // 即使同时满足超时与「用户请求停止」，也必须报「用户停的」：
        // 把用户主动停止说成异常退出，会让人以为进程又崩了。
        val kind = classifyProcessExit(
            exitCode = 137,
            everReady = true,
            timedOut = true,
            userRequestedStop = true,
        )
        assertEquals(ProcessFailureKind.USER_STOPPED, kind)
        assertFalse(kind.autoRetry)
    }

    @Test
    fun `磁盘满从输出特征里认出来并且不重试`() {
        val kind = classifyProcessExit(
            exitCode = 1,
            everReady = true,
            outputTail = "Error: ENOSPC: no space left on device, write",
        )
        assertEquals(ProcessFailureKind.NO_SPACE, kind)
        assertFalse("存储满了还继续写，只会继续坏", kind.autoRetry)
    }

    @Test
    fun `就绪后多久死决定是早期还是晚期`() {
        val early = classifyProcessExit(exitCode = 1, everReady = true, uptimeMs = 3_000)
        val late = classifyProcessExit(exitCode = 1, everReady = true, uptimeMs = 40 * 60_000)
        assertEquals(ProcessFailureKind.CRASHED_EARLY, early)
        assertEquals(ProcessFailureKind.CRASHED_LATE, late)
        assertTrue("这两类都值得重试一次", early.autoRetry && late.autoRetry)
    }

    @Test
    fun `窗口边界用注入值而不是硬编码魔数`() {
        // 阈值可注入：正好等于窗口时算"活过了窗口"（边界不摇摆）
        val atWindow = classifyProcessExit(
            exitCode = 1,
            everReady = true,
            uptimeMs = 60_000,
            healthyWindowMs = 60_000,
        )
        assertEquals(ProcessFailureKind.CRASHED_LATE, atWindow)
    }

    @Test
    fun `超时类别保留可重试语义`() {
        val kind = classifyProcessExit(exitCode = null, everReady = true, timedOut = true)
        assertEquals(ProcessFailureKind.TIMED_OUT, kind)
        assertTrue(kind.autoRetry)
    }

    @Test
    fun `每一类都必须有人话原因且不能重复`() {
        val summaries = ProcessFailureKind.entries.map { it.summary }
        assertTrue("存在空原因文案", summaries.none { it.isBlank() })
        assertEquals("原因文案必须各不相同", summaries.size, summaries.toSet().size)
    }
}
