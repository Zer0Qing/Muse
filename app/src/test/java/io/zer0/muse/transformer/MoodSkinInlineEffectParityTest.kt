package io.zer0.muse.transformer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内联特效正则改成预编译之后的行为对照。
 *
 * 这里把「每次调用现编 8 条正则」的旧实现原样留在测试里当参照物：
 * 新实现必须是它的逐字节等价物，任何一种输入都不许出现口径漂移。
 * 另外钉住几条期望输出，防止两边一起改错还互相印证。
 */
class MoodSkinInlineEffectParityTest {

    private val effects = listOf("glow", "big", "huge", "whisper", "red", "shake", "blur", "glitch")

    /** 改前的实现：每次调用为每个特效现编一条正则。 */
    private fun legacyContains(text: String): Boolean = effects.any { effect ->
        Regex("""\[$effect\]([\s\S]*?)\[/$effect\]""", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }

    /** 改前的实现：每次调用为每个特效现编一条正则，再依次替换。 */
    private fun legacyStrip(text: String): String {
        var result = text
        for (effect in effects) {
            val regex = Regex("""\[$effect\]([\s\S]*?)\[/$effect\]""", RegexOption.IGNORE_CASE)
            result = regex.replace(result) { it.groupValues[1] }
        }
        return result
    }

    private val samples = mapOf(
        "有特效" to "正文[glow]发光的字[/glow]结尾",
        "无特效" to "普通正文，一个方括号都没有",
        "大小写混写" to "正文[Glow]大写[/GLOW]与[Big]也大写[/bIg]",
        "多段" to "[glow]一[/glow]夹在中间[red]二[/red][shake]三[/shake]",
        "同名嵌套" to "[glow]外[glow]内[/glow]外[/glow]",
        "未知特效" to "[glowx]不算特效[/glowx] 与 [blurred]也不算[/blurred]",
        "没闭合" to "正文[glow]没有闭合的标签",
        "方括号普通用法" to "[1] 脚注与 [链接](https://a.b)",
    )

    @Test
    fun `containsInlineEffect matches the per call compilation`() {
        for ((name, text) in samples) {
            assertEquals(
                "containsInlineEffect 与改前不一致: $name",
                legacyContains(text),
                MoodSkinParser.containsInlineEffect(text),
            )
        }
    }

    @Test
    fun `stripInlineEffects matches the per call compilation`() {
        for ((name, text) in samples) {
            assertEquals(
                "stripInlineEffects 与改前不一致: $name",
                legacyStrip(text),
                MoodSkinParser.stripInlineEffects(text),
            )
        }
    }

    @Test
    fun `expected outputs are pinned`() {
        assertTrue(MoodSkinParser.containsInlineEffect("正文[glow]亮[/glow]"))
        assertFalse(MoodSkinParser.containsInlineEffect("正文没有特效"))
        assertTrue("大小写不敏感", MoodSkinParser.containsInlineEffect("正文[GlOw]亮[/GLOW]"))

        assertEquals("正文亮的字结尾", MoodSkinParser.stripInlineEffects("正文[glow]亮的字[/glow]结尾"))
        assertEquals("原样的正文", MoodSkinParser.stripInlineEffects("原样的正文"))
        assertEquals(
            "大小写混写也要剥干净",
            "大写与小写",
            MoodSkinParser.stripInlineEffects("[Glow]大写[/GLOW]与[Big]小写[/bIg]"),
        )
        assertEquals(
            "多段依次剥离，后一段不受前一段影响",
            "一二三",
            MoodSkinParser.stripInlineEffects("[glow]一[/glow][red]二[/red][shake]三[/shake]"),
        )
        assertEquals(
            "未知特效名不是特效",
            "[glowx]不算特效[/glowx]",
            MoodSkinParser.stripInlineEffects("[glowx]不算特效[/glowx]"),
        )
    }

    @Test
    fun `cleanForExport still strips moodfx then inline effects`() {
        val raw = "<moodfx>rage</moodfx>正文[glow]亮的字[/glow]"
        assertEquals("正文亮的字", MoodSkinParser.cleanForExport(raw))
        assertEquals("剥特效与改前一致", legacyStrip("正文[glow]亮的字[/glow]"), MoodSkinParser.cleanForExport(raw))
    }
}
