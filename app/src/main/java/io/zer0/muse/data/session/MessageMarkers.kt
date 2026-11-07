package io.zer0.muse.data.session

/**
 * 助手正文里的带内记号：它记的是"这一条停在哪儿"，不是给人读的一句话。
 *
 * 原来直接把「[已中断]」写进 assistant.content 落库，界面切成英文后那一行仍是中文
 * （走查 §十五 三-N4）。记号本身从不显示：渲染时由界面换成当前语言的说法；
 * 旧的中文写法在读取侧折回同一记号，存量会话不必迁移也能跟着界面走。
 */
object MessageMarkers {
    /** 生成被中断的记号。 */
    const val INTERRUPTED: String = "[msg:interrupted]"

    /** 修复前落库的中文写法，只用于读取侧兼容。 */
    private const val LEGACY_INTERRUPTED: String = "[已中断]"

    /** 追加时用的分隔,与 [stripInterrupted] 认得的写法保持一致。 */
    val interruptedSuffix: String = "\n\n$INTERRUPTED"

    /** 这条正文是否被标成中断过（含旧的中文写法）。 */
    fun isInterrupted(content: String): Boolean = content.contains(INTERRUPTED) || content.contains(LEGACY_INTERRUPTED)

    /** 去掉结尾的中断记号,返回断点前的正文;没有记号时原样返回。 */
    fun stripInterrupted(content: String): String {
        var out = content
        for (suffix in STRIPPED_SUFFIXES) {
            if (out.endsWith(suffix)) {
                out = out.removeSuffix(suffix)
                break
            }
        }
        return out.trimEnd()
    }

    /**
     * 回复被长度限制截断的记号。
     *
     * 以前落库的是 `err_reply_truncated` 那句**当时语言**的文案,于是"继续生成"按钮
     * 的判据（拿当前语言的文案去比对正文）一切语言就失效——功能坏掉而不只是显示不对。
     */
    const val TRUNCATED: String = "[msg:truncated]"

    val truncatedSuffix: String = "\n\n$TRUNCATED"

    /**
     * 这条正文是否被标成截断过。
     *
     * [legacyMarker] 是调用方按当前语言读到的旧文案：修复前落库的正文里写的是那句话，
     * 没有别的办法认出它，只能继续比一次。
     */
    fun isTruncated(content: String, legacyMarker: String): Boolean =
        content.contains(TRUNCATED) || (legacyMarker.isNotBlank() && content.contains(legacyMarker))

    /** 去掉结尾的截断记号。 */
    fun stripTruncated(content: String): String {
        var out = content
        for (suffix in listOf(truncatedSuffix, TRUNCATED)) {
            if (out.endsWith(suffix)) {
                out = out.removeSuffix(suffix)
                break
            }
        }
        return out.trimEnd()
    }

    private val STRIPPED_SUFFIXES: List<String> = listOf(
        interruptedSuffix,
        INTERRUPTED,
        "\n\n$LEGACY_INTERRUPTED",
        LEGACY_INTERRUPTED,
    )

    /**
     * 把正文里的记号换成当前界面语言的那句话。
     *
     * 展示层**不能**提前删掉记号：删完正文可能变成空串，而下游一串「正文为空就不渲染」
     * 的判据会把整条气泡吞掉（走查 §十六 实测：助手气泡只剩操作图标，正文一片空白）。
     * 所以记号留在正文里，到这里才换成一句话。
     */
    fun localize(content: String, interruptedText: String, truncatedText: String): String = content
        .replace(INTERRUPTED, interruptedText)
        .replace(LEGACY_INTERRUPTED, interruptedText)
        .replace(TRUNCATED, truncatedText)

    /**
     * 把正文里的记号统一成与语言无关的写法，供**落库**用。
     *
     * 会话列表的预览列是一份写进库的快照，一旦把某一种语言的句子写进去，
     * 切语言后那一行就永远停在旧语言（走查 §十六 实测：英文界面的会话副标题仍是「[已中断]」）。
     * 所以快照里只放记号，话留给渲染层说。
     *
     * 截断的旧写法只有"当前语言的那句话"可比（[legacyTruncatedText]），
     * 别的语言历史值认不出来，只能原样留着——不猜。
     */
    fun neutralize(content: String, legacyTruncatedText: String): String {
        var out = content.replace(LEGACY_INTERRUPTED, INTERRUPTED)
        if (legacyTruncatedText.isNotBlank()) out = out.replace(legacyTruncatedText, TRUNCATED)
        return out
    }
}
