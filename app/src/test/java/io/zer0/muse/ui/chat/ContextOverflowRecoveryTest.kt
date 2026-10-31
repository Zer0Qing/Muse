package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 上下文溢出（请求超出模型窗口）恢复的单测。
 *
 * 覆盖四件事：
 *  1. 判据：各 provider 的溢出文案能识别，非溢出的 400 / 限流文案不能；
 *  2. 一次机会：第一次溢出压缩后重发，第二次不再恢复；
 *  3. 压缩器腾不出空间（原样返回）时不重发，直接报原错误；
 *  4. 接线：runner 的溢出分支确实在「报错出口」之前，且用压缩后的历史重发。
 *
 * 说明：`ChatToolLoopRunner.streamRound` 的宿主是 ChatViewModel，单独驱动它需要替身
 * 几十个成员，因此「压缩 + 重发」的行为在这里用抽出的纯逻辑 + 源码接线断言钉住。
 */
class ContextOverflowRecoveryTest {

    // ── 判据 ─────────────────────────────────────────────────────────────

    @Test
    fun `recognizes overflow messages from different providers`() {
        val overflowMessages =
            listOf(
                // OpenAI 官方
                "HTTP 400 [invalid request]: This model's maximum context length is 128000 tokens. " +
                    "However, your messages resulted in 137077 tokens. Please reduce the length of the " +
                    "messages. [code=context_length_exceeded] (invalid_request_error)",
                // Anthropic
                "HTTP 400 [invalid request] (invalid_request_error): prompt is too long: 210000 tokens > 200000 maximum",
                // Gemini
                "HTTP 400 (INVALID_ARGUMENT) [invalid argument]: The input token count (1050000) exceeds " +
                    "the maximum number of tokens allowed (1048576).",
                // DashScope / Qwen
                "HTTP 400 (invalid argument): Range of input length should be [1, 129024]",
                // Kimi
                "HTTP 400 [invalid request]: exceeded model token limit",
                // 请求体过大
                "HTTP 413 [payload too large]: request entity too large",
                // 中文中转站
                "HTTP 400: 输入过长，请缩短对话",
            )
        overflowMessages.forEach { message ->
            assertTrue("应判为溢出: $message", ContextOverflowSignals.matches(message))
        }
    }

    @Test
    fun `does not treat other 400 or rate limit as overflow`() {
        val otherErrors =
            listOf(
                "HTTP 400 [invalid request]: Invalid parameter: temperature",
                "HTTP 400 [invalid request]: max_tokens is too large for this model",
                "HTTP 429 [rate limited]: Rate limit reached for tokens per min (TPM)",
                "HTTP 401 [authentication failed]: Invalid API key",
                "流式已输出内容但未完成,已保留当前内容(可重试生成)",
                "",
            )
        otherErrors.forEach { message ->
            assertFalse("不应判为溢出: $message", ContextOverflowSignals.matches(message))
        }
        assertFalse(ContextOverflowSignals.matches(null))
    }

    // ── 一次机会 ─────────────────────────────────────────────────────────

    @Test
    fun `first overflow compresses once and resends with the compressed history`() = runBlocking {
        val original = historyOf(6)
        val compressed = historyOf(2)
        val recovery = ContextOverflowRecovery()
        var compressCalls = 0

        val recovered =
            recoverFromContextOverflow(
                error = OPENAI_OVERFLOW,
                streamInterrupted = false,
                recovery = recovery,
                compress = {
                    compressCalls++
                    compressed
                },
            )

        assertEquals(1, compressCalls)
        assertEquals("压缩后的历史应交给调用方重发", compressed, recovered)
        assertTrue("重发用的是压缩后的历史，而不是原历史", recovered!!.size < original.size)
        assertTrue("机会应被占用", recovery.used)
    }

    @Test
    fun `second overflow in the same request is not recovered again`() = runBlocking {
        val recovery = ContextOverflowRecovery()
        var compressCalls = 0
        val compress: suspend () -> List<UIMessage>? = {
            compressCalls++
            historyOf(2)
        }

        val first =
            recoverFromContextOverflow(OPENAI_OVERFLOW, streamInterrupted = false, recovery = recovery, compress = compress)
        val second =
            recoverFromContextOverflow(OPENAI_OVERFLOW, streamInterrupted = false, recovery = recovery, compress = compress)

        assertTrue("第一次应恢复", first != null)
        assertNull("第二次应直接报原错误,不再恢复", second)
        assertEquals("整个请求生命周期只压缩一次", 1, compressCalls)
    }

    @Test
    fun `non overflow error never triggers compression`() = runBlocking {
        val recovery = ContextOverflowRecovery()
        var compressCalls = 0

        val recovered =
            recoverFromContextOverflow(
                error = "HTTP 400 [invalid request]: Invalid parameter: temperature",
                streamInterrupted = false,
                recovery = recovery,
                compress = {
                    compressCalls++
                    historyOf(2)
                },
            )

        assertNull(recovered)
        assertEquals(0, compressCalls)
        assertFalse("未命中判据不应占用机会", recovery.used)
    }

    @Test
    fun `interrupted stream is never treated as overflow`() = runBlocking {
        val recovery = ContextOverflowRecovery()
        var compressCalls = 0

        val recovered =
            recoverFromContextOverflow(
                error = OPENAI_OVERFLOW,
                streamInterrupted = true,
                recovery = recovery,
                compress = {
                    compressCalls++
                    historyOf(2)
                },
            )

        assertNull(recovered)
        assertEquals(0, compressCalls)
    }

    @Test
    fun `compressor with nothing to compress gives up without a second attempt`() = runBlocking {
        val recovery = ContextOverflowRecovery()
        var compressCalls = 0
        val compress: suspend () -> List<UIMessage>? = {
            compressCalls++
            // 压缩器无净收益 / 无可压缩区间时的表现：原样返回，runner 侧折算成 null
            null
        }

        val first =
            recoverFromContextOverflow(OPENAI_OVERFLOW, streamInterrupted = false, recovery = recovery, compress = compress)
        val second =
            recoverFromContextOverflow(OPENAI_OVERFLOW, streamInterrupted = false, recovery = recovery, compress = compress)

        assertNull("压不动就不恢复", first)
        assertNull(second)
        assertEquals("压不动也不重试第二次", 1, compressCalls)
    }

    // ── 接线 ─────────────────────────────────────────────────────────────

    @Test
    fun `runner recovers before the error outlet and resends the compressed history`() {
        val source =
            listOf(
                Path.of("src/main/java/io/zer0/muse/ui/chat/ChatToolLoopRunner.kt"),
                Path.of("app/src/main/java/io/zer0/muse/ui/chat/ChatToolLoopRunner.kt"),
            )
                .firstOrNull(Files::exists)
                ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
                ?: error("ChatToolLoopRunner.kt not found")

        val recoveryBlock =
            source.substringAfter("// 上下文溢出（请求超出模型窗口）恢复")
                .substringBefore("streamError?.let {")
        assertTrue("恢复走统一闸门", recoveryBlock.contains("recoverFromContextOverflow("))
        assertTrue("压缩复用现成压缩器", recoveryBlock.contains("compressForOverflowRecovery("))
        assertTrue(
            "压缩后的历史要重发本轮",
            recoveryBlock.contains("return streamRound(") &&
                recoveryBlock.contains("history = overflowHistory"),
        )
        assertTrue(
            "压缩后的历史要回写给整轮循环",
            recoveryBlock.contains("conversationHistory.clear()") &&
                recoveryBlock.contains("conversationHistory.addAll(overflowHistory)"),
        )

        // 恢复必须发生在「错误出口」之前：streamError 先尝试恢复，失败才走最终的
        // updateAssistant + StreamRoundResult.Error。
        val recoveryIndex = source.indexOf("// 上下文溢出（请求超出模型窗口）恢复")
        // 用错误出口里独有的那一行做锚（不用跨行文本：文件是 CRLF，拼 \n 会匹配不到）
        val errorOutletIndex = source.indexOf("val displayMsg = ErrorMessages.resolve(host.appContext, it)")
        assertTrue("未找到溢出恢复分支", recoveryIndex > 0)
        assertTrue("未找到错误出口", errorOutletIndex > 0)
        assertTrue("恢复分支必须在报错出口之前", recoveryIndex < errorOutletIndex)
    }

    private fun historyOf(size: Int): List<UIMessage> = (1..size).map { index ->
        UIMessage(
            role = if (index % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
            content = "message $index",
        )
    }

    private companion object {
        const val OPENAI_OVERFLOW: String =
            "HTTP 400 [invalid request]: This model's maximum context length is 128000 tokens. " +
                "However, your messages resulted in 137077 tokens. [code=context_length_exceeded]"
    }
}
