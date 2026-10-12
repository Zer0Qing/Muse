package io.zer0.muse.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-UI-F5: 压缩完成回调不得把「已切走的会话」的 checkpoint 水位线写成全局状态。
 *
 * 压缩跑在 IO 协程,完成时用户可能已切到 B 会话。原实现无条件执行
 * `checkpointCoveredSessionId = sessionId; refresh; updateContextTokenCount()`,
 * 于是 B 会话读到 A 的覆盖集 → 上下文占用率按错会话计算。
 */
class ChatCompressionWatermarkGuardTest {

    private fun source(): String = java.io.File("src/main/java/io/zer0/muse/ui/chat/ChatCompressionCoordinator.kt").readText()

    @Test
    fun `compression completion guards watermark against stale session`() {
        val src = source()
        // 用本次新增的注释锚点定位(文件里另有降级分支也用 displayedSessionId 判定)
        val start = src.indexOf("AUD-UI-F5")
        assertTrue("未找到 AUD-UI-F5 防护块", start >= 0)
        val body = src.substring(start, (start + 700).coerceAtMost(src.length))
        assertTrue(
            "仅在仍显示该会话时才写 checkpointCoveredSessionId",
            body.contains("host.checkpointCoveredSessionId = sessionId"),
        )
        assertTrue(
            "仅在仍显示该会话时才重算上下文 token",
            body.contains("host.updateContextTokenCount()"),
        )
    }

    @Test
    fun `no unconditional watermark write remains`() {
        val src = source()
        val guard = src.indexOf("AUD-UI-F5")
        assertTrue("未找到 AUD-UI-F5 防护块", guard >= 0)
        // 防护块之内应同时出现：判定、水位线写入、token 重算
        val block = src.substring(guard, (guard + 700).coerceAtMost(src.length))
        assertTrue(block.contains("host.displayedSessionId() == sessionId"))
        assertTrue(block.contains("host.checkpointCoveredSessionId = sessionId"))
    }
}
