package io.zer0.muse.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中断/截断记号的读侧契约（走查 §十五 三-N4）。
 *
 * 这个对象是"库里存语义、界面出文字"的边界：写进去的必须与语言无关，
 * 读出来时必须认得修复前落库的中文原句，否则老会话在英文界面上永远显示中文。
 */
class MessageMarkersTest {
    @Test
    fun recognisesBothTheNewTokenAndTheLegacyChineseMarker() {
        assertTrue(MessageMarkers.isInterrupted("正文" + MessageMarkers.interruptedSuffix))
        assertTrue("修复前的中文写法也要认得出", MessageMarkers.isInterrupted("正文\n\n[已中断]"))
        assertFalse(MessageMarkers.isInterrupted("普通回复"))
    }

    @Test
    fun stripsTheTrailingMarkerFromEitherGeneration() {
        assertEquals("partial", MessageMarkers.stripInterrupted("partial" + MessageMarkers.interruptedSuffix))
        assertEquals("partial", MessageMarkers.stripInterrupted("partial\n\n[已中断]"))
        assertEquals("partial", MessageMarkers.stripInterrupted("partial[已中断]"))
        assertEquals("只有记号时也剥干净", "", MessageMarkers.stripInterrupted(MessageMarkers.INTERRUPTED))
    }

    @Test
    fun leavesMarkersInsideTheBodyAlone() {
        // 只有结尾的记号才是状态；中间出现的这串字符属于正文内容，不能顺手删掉。
        val content = "开头" + MessageMarkers.INTERRUPTED + "结尾"
        assertEquals(content, MessageMarkers.stripInterrupted(content))
    }

    @Test
    fun truncatedRecognisesTheCurrentLanguageSentenceAsLegacy() {
        val marker = "（回复因长度限制被截断）"
        assertTrue(MessageMarkers.isTruncated("正文" + MessageMarkers.truncatedSuffix, marker))
        assertTrue("修复前落库的是当时语言的那句话", MessageMarkers.isTruncated("正文\n\n$marker", marker))
        assertFalse(MessageMarkers.isTruncated("普通回复", marker))
    }

    @Test
    fun stripsTheTruncatedSuffix() {
        assertEquals("正文", MessageMarkers.stripTruncated("正文" + MessageMarkers.truncatedSuffix))
        assertEquals("没标记时原样返回", "正文", MessageMarkers.stripTruncated("正文"))
    }
}
