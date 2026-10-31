package io.zer0.muse.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主动消息回复解析的单测。
 *
 * 重点是 `thought_id`：它是「这条消息说的是哪件事」的唯一凭据，
 * 上层靠它把牵挂标记为已说出口，进而让「反复念叨同一件事就自动放弃」生效。
 * 解析不出来时不会报错、不会影响发送，只会让账本悄悄停止退让 ——
 * 正是这种静默失效需要测试盯着。
 */
class ProactiveReplyParserTest {

    @Test
    fun `JSON 里的 thought_id 会被解析出来`() {
        val raw = """{"skip": false, "content": "那件事后来怎么样了", "tone": "关心", "thought_id": "th_123"}"""

        val result = ProactiveReplyParser.parse(raw)

        assertTrue(result is ProactiveReplyParser.Result.Message)
        assertEquals("th_123", (result as ProactiveReplyParser.Result.Message).thoughtId)
        assertEquals("那件事后来怎么样了", result.content)
    }

    @Test
    fun `空 thought_id 视为没有`() {
        // 提示词要求「不是从牵挂来的就留空字符串」，留空是正常输出，不是畸形输出。
        val raw = """{"skip": false, "content": "刚看到个东西想起你", "tone": "分享", "thought_id": ""}"""

        val result = ProactiveReplyParser.parse(raw) as ProactiveReplyParser.Result.Message

        assertNull(result.thoughtId)
    }

    @Test
    fun `缺 thought_id 字段不影响正文解析`() {
        // 老格式与不肯多写一个字段的模型都要能正常发送。
        val raw = """{"skip": false, "content": "吃饭了吗", "tone": "随口一提"}"""

        val result = ProactiveReplyParser.parse(raw) as ProactiveReplyParser.Result.Message

        assertNull(result.thoughtId)
        assertEquals("吃饭了吗", result.content)
    }

    @Test
    fun `字符串 null 不算有效 id`() {
        val raw = """{"skip": false, "content": "嗯嗯", "thought_id": "null"}"""

        val result = ProactiveReplyParser.parse(raw) as ProactiveReplyParser.Result.Message

        assertNull(result.thoughtId)
    }

    @Test
    fun `skip 为真时忽略 content 与 thought_id`() {
        val raw = """{"skip": true, "reason": "没什么可接的", "content": "不该被采用", "thought_id": "th_1"}"""

        val result = ProactiveReplyParser.parse(raw)

        assertTrue(result is ProactiveReplyParser.Result.Skip)
    }

    @Test
    fun `超长正文按句子边界截到上限以内`() {
        val sentence = "今天路过那家店，想起你说过想吃他们家的面，不知道你现在还想不想去。"
        val longBody = sentence.repeat(10)
        val raw = """{"skip": false, "content": "$longBody", "tone": "关心"}"""

        val result = ProactiveReplyParser.parse(raw) as ProactiveReplyParser.Result.Message

        assertTrue(
            "截断后不得超过上限：${result.content.length}",
            result.content.length <= ProactiveReplyParser.MAX_CONTENT_CHARS,
        )
        assertTrue("应停在完整句子：${result.content.takeLast(8)}", result.content.endsWith("。"))
        assertTrue("截断应保留原文开头", longBody.startsWith(result.content))
    }

    @Test
    fun `一个字视为没说`() {
        val raw = """{"skip": false, "content": "嗯"}"""

        val result = ProactiveReplyParser.parse(raw)

        assertTrue("一个字的正文不该被发出去", result is ProactiveReplyParser.Result.Skip)
    }

    @Test
    fun `只有标点也视为没说`() {
        val raw = """{"skip": false, "content": "。。。！"}"""

        val result = ProactiveReplyParser.parse(raw)

        assertTrue(result is ProactiveReplyParser.Result.Skip)
    }
}
