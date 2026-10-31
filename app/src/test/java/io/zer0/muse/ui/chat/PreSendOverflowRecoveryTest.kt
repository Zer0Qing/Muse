package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 发送前上下文溢出自救（ChatGenerationController）的单测。
 *
 * 拦截本身不被移除，只是在它前面加一次「压缩 + 重估」：
 *  - 命中拦截 → 压一轮 → 重新估算 → 不超了就正常发送；
 *  - 压完仍超 / 压不动 → 保持拦截，报原错误（文案不变）；
 *  - 全程只压一次，不重试。
 *
 * 闸门与运行中那条（[ContextOverflowRecoveryTest]）刻意**独立**：一个是本地估算，
 * 一个是 provider 真实拒绝，两种触发源（理由见 ChatGenerationController 内的注释）。
 */
class PreSendOverflowRecoveryTest {

    @Test
    fun `blocked send compresses once and continues when the new payload fits`() = runBlocking {
        val original = historyOf(12)
        val compressed = historyOf(4)
        var compressCalls = 0
        var overflowChecks = 0

        val recovered =
            recoverPreSendOverflow(
                history = original,
                compress = {
                    compressCalls++
                    compressed
                },
                stillOverflows = {
                    overflowChecks++
                    false
                },
            )

        assertEquals(1, compressCalls)
        assertEquals(1, overflowChecks)
        assertEquals("压完不超窗就正常发送", compressed, recovered)
    }

    @Test
    fun `still over the hard limit after compression keeps the block`() = runBlocking {
        val original = historyOf(12)
        var compressCalls = 0
        var overflowChecks = 0

        val recovered =
            recoverPreSendOverflow(
                history = original,
                compress = {
                    compressCalls++
                    historyOf(4)
                },
                stillOverflows = {
                    overflowChecks++
                    true
                },
            )

        assertNull("压完仍超窗 → 保持拦截,报原错误", recovered)
        assertEquals(1, compressCalls)
        assertEquals("重估一次即可,不反复压", 1, overflowChecks)
    }

    @Test
    fun `compressor that cannot free space gives up without re-estimating`() = runBlocking {
        val original = historyOf(12)
        var compressCalls = 0
        var overflowChecks = 0

        val unavailable =
            recoverPreSendOverflow(
                history = original,
                compress = {
                    compressCalls++
                    null
                },
                stillOverflows = {
                    overflowChecks++
                    false
                },
            )
        assertNull("压缩不可用 / 腾不出空间 → 直接报错", unavailable)

        val unchanged =
            recoverPreSendOverflow(
                history = original,
                compress = {
                    compressCalls++
                    // 压缩器原样返回的折算结果在调用方是 null，这里也覆盖"条数没变"
                    original
                },
                stillOverflows = {
                    overflowChecks++
                    false
                },
            )
        assertNull("没压短 → 不自救", unchanged)

        assertEquals(2, compressCalls)
        assertEquals("没压短就不必再估一次 payload", 0, overflowChecks)
    }

    @Test
    fun `pre-send block attempts recovery before refusing the send`() {
        val source =
            listOf(
                Path.of("src/main/java/io/zer0/muse/ui/chat/ChatGenerationController.kt"),
                Path.of("app/src/main/java/io/zer0/muse/ui/chat/ChatGenerationController.kt"),
            )
                .firstOrNull(Files::exists)
                ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
                ?: error("ChatGenerationController.kt not found")

        val recoveryIndex = source.indexOf("recoverPreSendOverflow(")
        // 用单行锚点定位（文件是 CRLF，跨行文本拼接会匹配不到）
        val blockIndex = source.indexOf("contextOverflowBlocked = true")
        assertTrue("未找到发送前自救调用", recoveryIndex > 0)
        assertTrue("未找到拦截置位", blockIndex > 0)
        assertTrue("自救必须发生在拦截置位之前", recoveryIndex < blockIndex)

        val block = source.substring(recoveryIndex, blockIndex)
        assertTrue("压完要回写历史", block.contains("truncatedHistory = compressedHistory"))
        assertTrue("压完要重新估算 payload", block.contains("TokenEstimator.estimate(prefixMessages + candidate)"))

        // 拦截仍然保留（最末安全网），只是前面多了一次自救
        assertTrue("拦截不得被移除", block.contains("if (compressedHistory != null)"))
        assertTrue("拦截文案路径仍在", source.substring(blockIndex).contains("refuse to send"))
    }

    private fun historyOf(size: Int): List<UIMessage> = (1..size).map { index ->
        UIMessage(
            role = if (index % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
            content = "message $index",
        )
    }
}
