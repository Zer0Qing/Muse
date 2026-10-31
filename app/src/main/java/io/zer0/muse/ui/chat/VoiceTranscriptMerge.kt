package io.zer0.muse.ui.chat

/**
 * 尾部重叠折叠的最小长度:两个汉字重合多为巧合(「很好」/「好的」),二字及以上才认定为
 * 同一段文本的重复上报;英文按字符算,两个字符以上基本已是一个完整单词片段。
 */
private const val MIN_OVERLAP_CHARS = 2

/**
 * 把一次 ASR 文本并入本轮累积文本。
 *
 * ASR 各 Provider 的回调语义是「本次会话到目前为止的完整文本」,而语音对话允许 ASR 会话
 * 自行结束(服务端静音收尾)后重开继续累积,于是同一条累积文本会同时收到:
 *  - 整段重发的完整文本(已包含先前内容)
 *  - 只包含本会话新增部分的文本
 *  - 同一句中间结果在增长(「今天天」→「今天天气」)
 *
 * 规则:
 *  - [incoming] 为空 → 返回 [accumulated]
 *  - [accumulated] 为空 → 返回 [incoming]
 *  - [incoming] 以 [accumulated] 开头 → 返回 [incoming](服务端给了完整文本)
 *  - [accumulated] 以 [incoming] 开头 → 返回 [accumulated](服务端重发了旧文本)
 *  - 其余 → 追加;两侧首尾有重叠时重叠段只保留一份
 *
 * 首尾空白总是 trim,中文不补分隔符,仅当两侧都是 ASCII 字母数字(英文单词边界)时补一个空格。
 */
internal fun mergeTranscript(accumulated: String, incoming: String): String {
    val acc = accumulated.trim()
    val inc = incoming.trim()
    return when {
        inc.isEmpty() -> acc
        acc.isEmpty() -> inc
        inc.startsWith(acc) -> inc
        acc.startsWith(inc) -> acc
        else -> appendWithOverlap(acc, inc)
    }
}

/** 追加 [inc] 到 [acc]:两者首尾重叠的一段只保留一份。 */
private fun appendWithOverlap(acc: String, inc: String): String {
    val overlap = longestSuffixPrefixOverlap(acc, inc)
    if (overlap >= MIN_OVERLAP_CHARS) return acc + inc.substring(overlap)
    // 中文没有词间空格,补了反而多一个字;只有英文单词边界才需要一个空格
    val needsSpacer = acc.last().isAsciiWordChar() && inc.first().isAsciiWordChar()
    return if (needsSpacer) "$acc $inc" else acc + inc
}

/** [acc] 的后缀与 [inc] 的前缀重合的最长长度(同一段文本重复上报时的折叠长度)。 */
private fun longestSuffixPrefixOverlap(acc: String, inc: String): Int {
    for (len in minOf(acc.length, inc.length) downTo 1) {
        if (acc.regionMatches(acc.length - len, inc, 0, len)) return len
    }
    return 0
}

private fun Char.isAsciiWordChar(): Boolean = code < 0x80 && isLetterOrDigit()
