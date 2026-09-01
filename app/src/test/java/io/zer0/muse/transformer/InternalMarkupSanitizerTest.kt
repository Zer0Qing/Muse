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
}
