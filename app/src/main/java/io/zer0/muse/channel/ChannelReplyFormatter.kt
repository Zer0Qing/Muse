package io.zer0.muse.channel

/**
 * 渠道回复的文本整形：拆段 + 压空行。
 *
 * 为什么需要它：群聊与单聊平台的消息体多是**纯文本**，模型输出里的
 * markdown 段落空行（`\n\n`）会原样显示成一大片空白；而 QQ 官方机器人
 * 对一条入站消息最多允许 **5 次**被动回复，正好可以把长回复拆成几条发，
 * 读起来像真人连发，顺带把空行问题一并消掉。
 *
 * 只做保守整形，不改写内容：
 *  - 不改 markdown 标记（`**`、`#`、代码块），避免破坏代码片段
 *  - 不截断字数（截断会让语义断在半句上，交给平台自己限制）
 */
object ChannelReplyFormatter {

    /** QQ 官方：一条入站消息最多被动回复 5 次，超出会被判超限。 */
    const val MAX_SEGMENTS: Int = 5

    /**
     * 把一段回复拆成若干条消息。
     *
     * 切分优先按空行（段落），段数超过 [max] 时把尾部合并到最后一段，
     * 而不是硬切文本 —— 硬切会断在句子中间，比少拆一条更难看。
     *
     * @return 拆分后的段；全为空白时返回空列表（调用方据此判为无可发送内容）
     */
    fun segments(text: String, max: Int = MAX_SEGMENTS): List<String> {
        val blocks = text
            .replace("\r\n", "\n")
            .split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        // 单出口：段数超上限时合并尾部，而不是硬切文本 ——
        // 硬切会断在句子中间，比少拆一条更难看。
        return when {
            max <= 0 || blocks.isEmpty() -> emptyList()
            blocks.size <= max -> blocks
            else -> blocks.take(max - 1) + blocks.drop(max - 1).joinToString("\n")
        }
    }

    /**
     * 压掉段内的连续空行与首尾空白。
     *
     * 只在「不分段直接发」的路径上用；拆段后每段本身已经是干净的，
     * 这里主要防单段回复里残留 `\n\n\n` 这种连模型自己都不在意的空行。
     */
    fun flatten(text: String): String = text
        .replace("\r\n", "\n")
        .replace(Regex("\n{2,}"), "\n")
        .trim()

    /**
     * 整段回复的最终形态：单段就压平，多段就拆分。
     *
     * 供调用方决定「发一条还是发几条」，避免两处各写一套判断。
     */
    fun plan(text: String, max: Int = MAX_SEGMENTS): List<String> {
        val segs = segments(text, max)
        return if (segs.size <= 1) segs.map { flatten(it) }.filter { it.isNotEmpty() } else segs
    }
}
