package io.zer0.muse.transformer

/**
 * B6-02: pelle-d-umore 情绪皮肤协议解析。
 *
 * 与应用自带 `<mood>...</mood>` 腹稿完全隔离：
 * - `<moodfx>rage</moodfx>` 是整条回复的全屏皮肤
 * - `[glow]...[/glow]` 等是内联文字特效
 * - 复制/朗读/导出一律使用 [cleanForExport] 清除协议标签
 */
object MoodSkinParser {

    val SUPPORTED_SKINS = setOf("rage", "rage2", "desire", "vuoto", "moonlight", "off")

    private val MOODFX_REGEX = Regex("""<moodfx>([\s\S]*?)</moodfx>""", RegexOption.IGNORE_CASE)

    private val INLINE_EFFECTS = listOf(
        "glow", "big", "huge", "whisper", "red", "shake", "blur", "glitch",
    )

    /**
     * 从 [content] 提取 `<moodfx>` 并剥离标签。
     *
     * @return (皮肤 id, 剥离后的正文)
     */
    fun extract(content: String, existing: String? = null): Pair<String?, String> {
        if (existing != null) return existing to content
        val match = MOODFX_REGEX.find(content) ?: return null to content
        val raw = match.groupValues[1].trim().lowercase()
        val skin = raw.takeIf { it in SUPPORTED_SKINS }
        val cleaned = content.removeRange(match.range)
        return skin to cleaned
    }


    /** 判断文本是否包含任何内联特效标签(用于选择富文本渲染路径)。 */
    fun containsInlineEffect(text: String): Boolean =
        INLINE_EFFECTS.any { effect ->
            Regex("""\[$effect\]([\s\S]*?)\[/$effect\]""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }
    /** 清除所有内联特效标签,保留被包裹的文字。 */
    fun stripInlineEffects(text: String): String {
        var result = text
        for (effect in INLINE_EFFECTS) {
            val regex = Regex("""\[$effect\]([\s\S]*?)\[/$effect\]""", RegexOption.IGNORE_CASE)
            result = regex.replace(result) { it.groupValues[1] }
        }
        return result
    }

    /** 导出/复制/朗读前统一清理:先剥 moodfx,再清内联特效。 */
    fun cleanForExport(text: String): String = stripInlineEffects(extract(text).second)
}
