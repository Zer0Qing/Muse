package io.zer0.muse.tools

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 委派时带多少上下文（协作语义显式化 · 第二部分）。
 *
 * 这个构造器只管"截最近 8 条 + 剥掉非文本字段"，两件事都能静默出事：
 *
 *  - 截错方向（拿最早 8 条）→ 子助手看到的是会话开头，
 *    用户刚说的那句根本没带过去，答非所问却看不出为什么；
 *  - 忘了剥字段 → 请求里带着**孤儿 toolCalls**（有 tool_calls 却没有对应结果）
 *    或给纯文本模型发图片，Provider 直接拒；这类失败发生在子线程里，
 *    界面上只表现为"委派没结果"。
 */
class DelegationContextBuilderTest {

    private fun text(content: String) = UIMessage(role = MessageRole.USER, content = content)

    @Test
    fun `只带最近若干条而不是最早的`() {
        val messages = (1..20).map { text("第 $it 句") }

        val picked = DelegationContextBuilder.build(messages, maxMessages = 8)

        assertEquals(8, picked.size)
        assertEquals("第 13 句", picked.first().content)
        assertEquals("第 20 句", picked.last().content)
    }

    @Test
    fun `空内容与纯空白不进上下文`() {
        val messages = listOf(text("有用的"), text("   "), text(""), text("\n\t"))

        val picked = DelegationContextBuilder.build(messages)

        assertEquals(listOf("有用的"), picked.map { it.content })
    }

    @Test
    fun `默认不把带图的消息递给纯文本子助手`() {
        val withImage = UIMessage(
            role = MessageRole.USER,
            content = "看这张",
            imageUrls = listOf("file:///sdcard/a.png"),
        )

        val dropped = DelegationContextBuilder.build(listOf(text("前一句"), withImage))

        assertEquals("图片消息整条被丢掉，而不是留着 URL", listOf("前一句"), dropped.map { it.content })
        assertTrue(dropped.none { it.imageUrls.isNotEmpty() })
    }

    @Test
    fun `显式允许带图时也只保留图，工具元数据仍要剥掉`() {
        val mixed = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "带图也带工具调用",
            imageUrls = listOf("file:///sdcard/a.png"),
            toolCalls = listOf(ToolCall("call-1", "quick_note_add", """{"title":"idea"}""")),
            reasoning = "内部推理不该外传",
            voiceUri = "file:///sdcard/v.mp3",
        )

        val kept = DelegationContextBuilder.build(listOf(mixed), includeImages = true).single()

        assertEquals(listOf("file:///sdcard/a.png"), kept.imageUrls)
        assertNull("孤儿 toolCalls 会让请求被拒", kept.toolCalls)
        assertNull(kept.toolCallId)
        assertNull(kept.reasoning)
        assertNull("语音不属于图片通路", kept.voiceUri)
    }

    @Test
    fun `纯文本通路上思考痕迹与旁路字段一律清空`() {
        // 注意：带 imageUrls 的消息在纯文本通路上是**整条被丢掉**（见上一条测试），
        // 所以这里测的是"没有图、但带着一堆旁路字段"的消息 —— 它必须留下文本、剥掉其余。
        val dirty = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "一句正常话",
            videoFileUri = "file:///v.mp4",
            voiceUri = "file:///v.mp3",
            voiceDurationMs = 1200L,
            toolCalls = listOf(ToolCall("c1", "t", "{}")),
            reasoning = "r",
            citationUrls = listOf("https://x"),
            mood = "开心",
            quotedContent = "引用",
            reaction = "👍",
        )

        val clean = DelegationContextBuilder.build(listOf(dirty)).single()

        assertEquals("一句正常话", clean.content)
        assertTrue(clean.imageUrls.isEmpty())
        assertTrue(clean.imageBase64List.isEmpty())
        assertNull(clean.videoFileUri)
        assertNull(clean.voiceUri)
        assertEquals(0L, clean.voiceDurationMs)
        assertNull("孤儿 toolCalls 会让请求被 provider 拒", clean.toolCalls)
        assertNull(clean.reasoning)
        assertTrue(clean.citationUrls.isEmpty())
        assertNull(clean.mood)
        assertNull(clean.quotedContent)
        assertNull(clean.reaction)
    }

    @Test
    fun `默认上限就是那个文档里的条数`() {
        val messages = (1..30).map { text("句 $it") }

        val picked = DelegationContextBuilder.build(messages)

        assertEquals(DelegationContextBuilder.DEFAULT_MAX_MESSAGES, picked.size)
        assertEquals(8, DelegationContextBuilder.DEFAULT_MAX_MESSAGES)
        assertNotNull(picked.last())
    }
}
