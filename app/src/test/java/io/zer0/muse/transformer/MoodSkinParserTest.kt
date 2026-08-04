package io.zer0.muse.transformer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoodSkinParserTest {

    @Test
    fun extract_moodfxDoesNotTouchMood() {
        val input = """
            <mood>
            Vibe: 冷静
            </mood>
            <moodfx>rage</moodfx>
            正文内容 [glow]重点[/glow]
        """.trimIndent()

        val (skin, content) = MoodSkinParser.extract(input)

        assertEquals("rage", skin)
        assertEquals(false, content.contains("<moodfx>"))
        assertEquals(true, content.contains("<mood>"))
        assertEquals("重点", MoodSkinParser.stripInlineEffects(content).substringAfter("正文内容 ").trim())
    }

    @Test
    fun cleanForExport_removesAllProtocolTags() {
        val input = "<moodfx>desire</moodfx> 你好 [huge]世界[/huge] [red]危险[/red]"
        val cleaned = MoodSkinParser.cleanForExport(input)
        assertEquals(" 你好 世界 危险", cleaned)
    }

    @Test
    fun extract_ignoresUnknownSkin() {
        val input = "<moodfx>nope</moodfx>正文"
        val (skin, content) = MoodSkinParser.extract(input)
        assertNull(skin)
        assertEquals("正文", content.trim())
    }
}
