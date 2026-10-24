package io.zer0.muse.transformer

import io.zer0.muse.ui.markdown.appendCardStateToMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.8: `card_state` 尾巴的统一出口 —— 三个出口各自剥什么，必须可验证。
 *
 * 背景：card_state 原来只有「一个写入点、一个剥离点（气泡渲染）」，其余十余处原文读者
 * 直接读 content，导致 JSON 漏进复制/分享/转发/草稿/引用/消息地图/收藏/导出/会话预览，
 * 以及被全文索引命中。现在剥离责任收到 [InternalMarkupSanitizer] 的三个出口上。
 *
 * 这条测试守四件事：
 *  1. 展示与导出出口都剥掉 card_state，用户看不到 JSON；
 *  2. 检索出口不含 card_state 内容（否则能搜到 `cardId=` / JSON 键值）；
 *  3. 不含 card_state 的普通文本一个字都不许改（回归保护）；
 *  4. 无标记文本走快速路径，不重建字符串、不跑正则。
 */
class InternalMarkupSanitizerCardStateTest {

    private fun withCardState(text: String = "把睡眠调到 6 小时"): String = appendCardStateToMessage(
        text = text,
        cardId = "msg-123",
        data = """{"hours":6,"from":8}""",
    )

    // ── 出口一：displayContent（展示） ────────────────────────────────

    @Test
    fun `displayContent drops the card state tail`() {
        val displayed = InternalMarkupSanitizer.displayContent(withCardState())

        assertEquals("气泡只显示那句话", "把睡眠调到 6 小时", displayed)
        assertFalse("不能残留标记", displayed.contains("card_state"))
        assertFalse("不能残留 cardId", displayed.contains("cardId="))
        assertFalse("不能残留 JSON", displayed.contains("hours"))
    }

    @Test
    fun `displayContent also strips internal markup blocks`() {
        val raw = "<think>内部思考</think>" + withCardState("正文")

        val displayed = InternalMarkupSanitizer.displayContent(raw)

        assertEquals("正文", displayed)
    }

    @Test
    fun `displayContent truncates an unclosed tail instead of leaking json`() {
        // 尾巴写了一半（崩溃/截断）：宁可丢状态，也不能把 JSON 漏给用户。
        val raw = "正文\n" + InternalMarkupSanitizer.CARD_STATE_OPEN + "cardId=msg-1\n{\"hours\":6"

        val displayed = InternalMarkupSanitizer.displayContent(raw)

        assertEquals("正文", displayed)
    }

    @Test
    fun `displayContent drops every tail when there are several`() {
        val raw = withCardState("第一句") + withCardState("第二句")

        val displayed = InternalMarkupSanitizer.displayContent(raw)

        assertFalse(displayed.contains("card_state"))
        assertFalse(displayed.contains("cardId="))
        assertTrue("正文都要留着", displayed.contains("第一句") && displayed.contains("第二句"))
    }

    // ── 出口二：exportContent（复制 / 分享 / 导出 / 朗读） ─────────────

    @Test
    fun `exportContent drops card state and inline effect tags`() {
        val raw = withCardState("你好 [huge]世界[/huge]")

        val exported = InternalMarkupSanitizer.exportContent(raw)

        assertEquals("你好 世界", exported)
        assertFalse(exported.contains("card_state"))
    }

    @Test
    fun `exportContent keeps plain text identical`() {
        assertEquals("普通一句话", InternalMarkupSanitizer.exportContent("普通一句话"))
        assertEquals("多行\n文本", InternalMarkupSanitizer.exportContent("多行\n文本"))
    }

    // ── 出口三：searchableContent（检索 / 索引） ──────────────────────

    @Test
    fun `searchableContent drops card state so it cannot be searched`() {
        val searchable = InternalMarkupSanitizer.searchableContent(withCardState())

        assertEquals("把睡眠调到 6 小时", searchable)
        assertFalse(searchable.contains("card_state"))
        assertFalse("JSON 键值不能进索引", searchable.contains("hours"))
        assertFalse("卡片身份不能进索引", searchable.contains("cardId="))
    }

    @Test
    fun `searchableContent keeps internal blocks untouched`() {
        // 取舍说明：mood/think 是否入索引是另一个决策，本次不顺手改，
        // 免得搜索行为静默漂移。检索出口只负责 card_state。
        val raw = "<mood>先稳住</mood>正文"

        assertEquals(raw, InternalMarkupSanitizer.searchableContent(raw))
    }

    @Test
    fun `searchableContent keeps plain text identical`() {
        assertEquals("普通一句话", InternalMarkupSanitizer.searchableContent("普通一句话"))
        assertEquals("  带缩进  ", InternalMarkupSanitizer.searchableContent("  带缩进  "))
    }

    // ── 回归保护 + 性能快速路径 ───────────────────────────────────────

    @Test
    fun `text without markers is not rebuilt by stripCardState`() {
        val plain = "我没有卡片状态"
        // assertSame 证明没走任何替换/裁剪：同一个实例原样返回。
        assertSame(plain, InternalMarkupSanitizer.stripCardState(plain))
    }

    @Test
    fun `literal bracket word is not mistaken for a card state tail`() {
        // 用户正文里手写 [card_state]（讨论协议本身时）不能被吃掉。
        val literal = "这个协议叫 [card_state]，它包住 JSON"
        assertSame(literal, InternalMarkupSanitizer.stripCardState(literal))
        assertEquals(literal, InternalMarkupSanitizer.displayContent(literal))
    }

    @Test
    fun `markdown link and code fence survive all three exits`() {
        val raw = "[链接](https://a.b) 和 `List<String>`"

        assertEquals(raw, InternalMarkupSanitizer.displayContent(raw))
        assertEquals(raw, InternalMarkupSanitizer.exportContent(raw))
        assertEquals(raw, InternalMarkupSanitizer.searchableContent(raw))
    }

    @Test
    fun `blank text returns empty from every exit`() {
        assertEquals("", InternalMarkupSanitizer.displayContent("   \n  "))
        assertEquals("", InternalMarkupSanitizer.exportContent("   \n  "))
        assertEquals("   \n  ", InternalMarkupSanitizer.searchableContent("   \n  "))
    }

    @Test
    fun `containsCardState is a cheap probe with no regex`() {
        assertTrue(InternalMarkupSanitizer.containsCardState(withCardState()))
        assertFalse(InternalMarkupSanitizer.containsCardState("普通文本"))
        assertFalse("markdown 链接不算", InternalMarkupSanitizer.containsCardState("[链接](https://a.b)"))
    }
}
