package io.zer0.muse.ui.taskcard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务卡步骤摘要的测试。
 *
 * 用户反馈「一个卡里面展示的卡片和别处不一样、还有挤压」：截图里 show_card 步骤的标题
 * 下面跟着一段被截断的原始 JSON(`{"title":"vinyl_music_player","code":"<style>...`)。
 * 根因是步骤摘要一律取 `args.take(200)`，而 show_card 的参数就是整份 HTML。
 * 这里钉住：卡片类工具的摘要必须是卡片标题，不是参数原文。
 *
 * 纯 JVM 测试（不启 Robolectric）：被测的是摘要决策本身，不需要 Android 运行时。
 */
class TaskCardStepDetailTest {

    private fun detail(name: String, args: String): String = TaskCardData.stepDetailFor(name, args, TaskCardData.DelegateAgentArgs("", ""))

    @Test
    fun `show_card step detail is the card title not the raw json`() {
        val args = """{"title":"vinyl_music_player","code":"<style>:root{--bg:var(--muse-bg)}</style><div>player</div>"}"""

        val out = detail("show_card", args)

        assertEquals("vinyl_music_player", out)
        assertFalse("步骤摘要不能是原始 JSON,实际: $out", out.contains("\"code\""))
        assertFalse("步骤摘要不能是 HTML,实际: $out", out.contains("<style>"))
    }

    @Test
    fun `render_data step detail is also the card title`() {
        assertEquals("q4_revenue", detail("render_data", """{"title":"q4_revenue","data":{"a":1}}"""))
    }

    @Test
    fun `card step without title shows no detail instead of raw json`() {
        assertEquals("", detail("show_card", """{"code":"<div>no title here</div>"}"""))
    }

    @Test
    fun `card title is truncated to the step detail budget`() {
        val longTitle = "t".repeat(200)

        val out = detail("show_card", """{"title":"$longTitle","code":"<div>x</div>"}""")

        assertTrue("摘要应截断,实际长度 ${out.length}", out.length <= 60)
    }

    @Test
    fun `malformed card args do not crash and do not leak raw text`() {
        assertEquals("", detail("show_card", "not-json-at-all"))
    }

    @Test
    fun `ordinary tools still show their arguments as detail`() {
        val args = """{"query":"今日天气"}"""

        assertEquals(args, detail("web_search", args))
    }

    @Test
    fun `ordinary tool detail is capped at 200 chars`() {
        val out = detail("web_search", "x".repeat(500))

        assertEquals(200, out.length)
    }

    @Test
    fun `delegate agent detail stays the delegated task`() {
        val out = TaskCardData.stepDetailFor(
            "delegate_agent",
            """{"assistantId":"a1","task":"帮我整理这周的会议纪要"}""",
            TaskCardData.DelegateAgentArgs("a1", "帮我整理这周的会议纪要"),
        )

        assertEquals("帮我整理这周的会议纪要", out)
    }
}
