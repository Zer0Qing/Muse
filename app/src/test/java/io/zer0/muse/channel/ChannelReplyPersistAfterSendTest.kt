package io.zer0.muse.channel

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-CHANNEL-F9: 自动回复必须先发送成功、再记入会话历史。
 *
 * 原实现先 appendTurn(assistant, reply) 再发送:发送失败→Retry→下轮重新生成
 * 新文本 reply',第二次 appendTurn 因 dispatchId 去重被跳过 → 会话库存的是第一次
 * 文本,而用户实际收到第二次,上下文漂移。
 */
class ChannelReplyPersistAfterSendTest {

    private fun source(): String = java.io.File("src/main/java/io/zer0/muse/channel/ChannelAutoReply.kt").readText()

    @Test
    fun `assistant turn is appended only after successful send`() {
        val src = source()
        val fnStart = src.indexOf("private suspend fun sendPreparedReply(")
        assertTrue("未找到 sendPreparedReply", fnStart >= 0)
        val fnEnd = src.indexOf("private sealed interface HandleResult", fnStart)
        val body = src.substring(fnStart, if (fnEnd > fnStart) fnEnd else src.length)

        val appendIdx = body.indexOf("ChannelConversationStore.appendTurn")
        val sendIdx = body.indexOf("channelManager.sendTextWithOptions")
        assertTrue("appendTurn 必须存在", appendIdx >= 0)
        assertTrue("sendTextWithOptions 必须存在", sendIdx >= 0)
        assertTrue(
            "appendTurn 必须在发送成功分支之后(不能先写历史再发送)",
            appendIdx > sendIdx,
        )
        assertTrue(
            "appendTurn 应位于 result.ok 成功分支内",
            body.indexOf("if (!result.ok)") < appendIdx,
        )
    }
}
