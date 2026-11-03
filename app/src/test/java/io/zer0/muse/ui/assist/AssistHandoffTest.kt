package io.zer0.muse.ui.assist

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 系统助理的上下文转写（对比清单 P1-04）。
 *
 * 守的是"如实转述"：有什么写什么，没有的段落整段不出现。
 * 拼一堆"（未知）"会让模型以为拿到了信息，反而更容易乱猜。
 */
class AssistHandoffTest {

    @Before
    fun setUp() = AssistInbox.resetForTests()

    @After
    fun tearDown() = AssistInbox.resetForTests()

    @Test
    fun `a full snapshot becomes a readable prompt`() {
        val prompt = AssistHandoff.buildPrompt(
            AssistSnapshot(
                packageName = "com.example.shop",
                activityName = "com.example.shop.DetailActivity",
                visibleText = "订单详情\n总计 ¥128",
                contentText = "https://example.com/order/1",
            ),
        )

        assertTrue(prompt.contains("com.example.shop"))
        assertTrue(prompt.contains("订单详情"))
        assertTrue(prompt.contains("https://example.com/order/1"))
        assertTrue("要标明来源: $prompt", prompt.startsWith("[SYSTEM_ASSISTANT_CONTEXT]"))
    }

    @Test
    fun `an empty snapshot says so instead of pretending`() {
        val prompt = AssistHandoff.buildPrompt(AssistSnapshot())

        assertTrue(prompt.contains("assistant_context_unavailable"))
        assertFalse("不能凭空说看到了屏幕: $prompt", prompt.contains("text="))
    }

    @Test
    fun `blank fields are omitted rather than rendered as unknown`() {
        val prompt = AssistHandoff.buildPrompt(
            AssistSnapshot(packageName = "com.example.app", activityName = "  ", visibleText = "  "),
        )

        assertTrue(prompt.contains("com.example.app"))
        assertFalse("空字段不该出现: $prompt", prompt.contains("hint="))
        assertFalse(prompt.contains("text="))
        assertFalse(prompt.contains("未知"))
    }

    @Test
    fun `long screen text is capped so the draft stays usable`() {
        val long = "字".repeat(AssistHandoff.MAX_TEXT_CHARS + 800)

        val prompt = AssistHandoff.buildPrompt(AssistSnapshot(visibleText = long))

        assertTrue("上限之外的文字必须被截断", prompt.length < long.length)
        assertTrue(prompt.length <= AssistHandoff.MAX_TEXT_CHARS + 200)
    }

    @Test
    fun `screenshot is never claimed`() {
        // Assist 没有稳定的截图通道，hasScreenshot 恒为 false，提示里不能出现"我看到屏幕"
        val prompt = AssistHandoff.buildPrompt(AssistSnapshot(packageName = "a.b", visibleText = "x"))

        assertFalse(prompt.contains("截图"))
        assertFalse(prompt.contains("看到屏幕"))
    }

    @Test
    fun `the inbox holds one prompt and clears after consuming`() {
        AssistInbox.publish("来自助理的上下文")
        assertEquals("来自助理的上下文", AssistInbox.pending.value)

        val taken = AssistInbox.consume()

        assertEquals("来自助理的上下文", taken)
        assertNull("取走后必须清空，否则旋转屏幕会重复填入", AssistInbox.pending.value)
    }

    @Test
    fun `a blank prompt is not published`() {
        AssistInbox.publish("   ")

        assertNull(AssistInbox.pending.value)
    }
}
