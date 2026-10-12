package io.zer0.muse.channel

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-CHANNEL-F7: 回发上下文令牌解密失败必须转终态 BLOCKED,不得无限 Retry。
 *
 * 原实现解密失败返回 Unavailable → HandleResult.Retry → retryDispatch 无上限
 * (封顶 5 分钟一次,永不终止),占住共享派发器节拍并刷日志;与 Agent checkpoint
 * 不可读转 BLOCKED 的既有口径不一致。
 */
class ChannelReplyTokenDecryptBlockedTest {

    private fun source(): String = java.io.File("src/main/java/io/zer0/muse/channel/ChannelAutoReply.kt").readText()

    @Test
    fun `decrypt failure maps to blocked not retry`() {
        val src = source()
        assertTrue(
            "解密失败分支必须转 HandleResult.Blocked",
            src.contains("is ContextTokenOverride.DecryptFailed -> HandleResult.Blocked"),
        )
        assertTrue(
            "不应再把 Unavailable 映射为 Retry",
            !src.contains("ContextTokenOverride.Unavailable -> HandleResult.Retry"),
        )
    }

    @Test
    fun `resolve distinguishes absent from decrypt failure`() {
        val src = source()
        assertTrue(
            "无令牌走 Absent(正常放行)",
            src.contains("ContextTokenOverride.Absent"),
        )
        assertTrue(
            "解密异常捕获后返回 DecryptFailed",
            src.contains("return ContextTokenOverride.DecryptFailed(\"context_token_unreadable\")"),
        )
    }
}
