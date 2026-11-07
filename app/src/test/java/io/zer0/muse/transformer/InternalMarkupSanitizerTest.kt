package io.zer0.muse.transformer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InternalMarkupSanitizerTest {
    @Test
    fun stripsInternalBlocksFromDisplayButKeeps正文() {
        assertEquals(
            "你好\n这是正文",
            InternalMarkupSanitizer.stripForDisplay("<think>内部思考</think>你好\n<mod>内部腹稿</mod>这是正文"),
        )
    }

    @Test
    fun stripsUnclosedInternalBlockToAvoidLeakingThoughts() {
        assertEquals("正文", InternalMarkupSanitizer.stripForDisplay("正文<thinking>还没结束的思考"))
    }

    @Test
    fun extractsMoodFromMoodAndModTags() {
        assertEquals("心情不错\n准备认真回答", InternalMarkupSanitizer.extractMood("<mood>心情不错</mood><mod>准备认真回答</mod>正文"))
        assertNull(InternalMarkupSanitizer.extractMood("只有正文"))
    }

    @Test
    fun extractsMoodFromBracketTags() {
        assertEquals(
            "先稳住\n认真回答",
            InternalMarkupSanitizer.extractMood("[mood]先稳住[/mood][mod]认真回答[/mod]正文"),
        )
    }

    // ── v2.6.7 性能改动：快速路径的行为等价性 ─────────────────────────

    /** 纯文本（不含 '<' 与 '['）走快速路径，内容不得变。 */
    @Test
    fun plainTextFastPathKeepsContent() {
        assertEquals("普通一句话", InternalMarkupSanitizer.stripForDisplay("  普通一句话  "))
        assertEquals("多行\n文本", InternalMarkupSanitizer.stripForDisplay("多行\n文本"))
    }

    /** 快速路径仍要收敛 3 个以上连续空行（旧实现也做）。 */
    @Test
    fun plainTextFastPathStillCollapsesBlankRuns() {
        assertEquals("上\n\n下", InternalMarkupSanitizer.stripForDisplay("上\n\n\n\n下"))
    }

    /** 含 '[' 但无可识别标记（markdown 链接）不能被破坏。 */
    @Test
    fun bracketWithoutMarkerIsPreserved() {
        assertEquals("[链接](https://a.b) 正文", InternalMarkupSanitizer.stripForDisplay("[链接](https://a.b) 正文"))
    }

    /** 含尖括号但不是内部标记（代码片段）不能被误删。 */
    @Test
    fun angleBracketWithoutMarkerIsPreserved() {
        assertEquals("List<String>", InternalMarkupSanitizer.stripForDisplay("List<String>"))
    }

    /** 空白文本返回空串，不得抛错。 */
    @Test
    fun blankTextReturnsEmpty() {
        assertEquals("", InternalMarkupSanitizer.stripForDisplay("   \n  "))
        assertEquals("", InternalMarkupSanitizer.stripForDisplay(""))
    }

    /**
     * 状态记号：展示层保留、导出层剥掉。
     *
     * 展示层不能剥——剥完正文可能变成空串，下游「正文为空就不渲染」的判据会把整条
     * 气泡吞掉（走查 §十六 实测：助手气泡整条消失）。本地化在气泡层用
     * MessageMarkers.localize 做。复制/导出则不该带这行状态。
     */
    @Test
    fun keepsStateMarkersForDisplayButStripsThemOnExport() {
        val markers = io.zer0.muse.data.session.MessageMarkers
        assertEquals("正文" + markers.interruptedSuffix, InternalMarkupSanitizer.stripForDisplay("正文" + markers.interruptedSuffix))
        assertEquals("正文\n\n[已中断]", InternalMarkupSanitizer.stripForDisplay("正文\n\n[已中断]"))
        assertEquals("正文", InternalMarkupSanitizer.exportContent("正文" + markers.interruptedSuffix))
        assertEquals("正文", InternalMarkupSanitizer.exportContent("正文\n\n[已中断]"))
        assertEquals("正文", InternalMarkupSanitizer.exportContent("正文" + markers.truncatedSuffix))
    }

    /** 记号换成当前语言的那句话；正文里的普通文字不受影响。 */
    @Test
    fun localizeReplacesMarkersWithTheCurrentLanguageSentence() {
        val markers = io.zer0.muse.data.session.MessageMarkers
        assertEquals(
            "正文\n\n已中断",
            markers.localize("正文" + markers.interruptedSuffix, "已中断", "被截断"),
        )
        assertEquals("正文\n\n[Interrupted]", markers.localize("正文\n\n[已中断]", "[Interrupted]", "Truncated"))
        assertEquals("正文", markers.localize("正文", "[Interrupted]", "Truncated"))
    }
}
