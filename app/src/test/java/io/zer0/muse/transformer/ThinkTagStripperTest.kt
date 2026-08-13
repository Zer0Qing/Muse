package io.zer0.muse.transformer

import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.0.74: stripThinkTags 剥离测试 — 含闭合/未闭合/嵌套边界场景。 */
class ThinkTagStripperTest {

    @Test
    fun `strip closed think block`() {
        assertEquals("标题", stripThinkTags("<think>思考内容</think>标题"))
    }

    @Test
    fun `strip closed block with newlines`() {
        assertEquals("标题", stripThinkTags("<think>\n第一行思考\n第二行思考\n</think>标题"))
    }

    @Test
    fun `strip unclosed think at start`() {
        // 用户反馈场景: 模型只输出 <think> 开头未闭合 → 标题被污染
        assertEquals("", stripThinkTags("<think>对话主题是关于摄影器材的选择"))
    }

    @Test
    fun `strip unclosed think after closed block`() {
        assertEquals("正文", stripThinkTags("<think>A</think>正文<think>B"))
    }

    @Test
    fun `keep text without think tags`() {
        assertEquals("普通标题", stripThinkTags("普通标题"))
    }

    @Test
    fun `strip all closed blocks`() {
        assertEquals("甲乙", stripThinkTags("<think>1</think>甲<think>2</think>乙"))
    }

    @Test
    fun `strip quotes after tags`() {
        assertEquals("\"标题\"", stripThinkTags("<think>x</think>\"标题\""))
    }

    @Test
    fun `handle empty input`() {
        assertEquals("", stripThinkTags(""))
        assertEquals("", stripThinkTags("   "))
    }

    @Test
    fun `strip thinking variant`() {
        assertEquals("标题", stripThinkTags("<thinking>推理</thinking>标题"))
    }

    @Test
    fun `strip case insensitive`() {
        assertEquals("标题", stripThinkTags("<THINK>推理</THINK>标题"))
    }
}
