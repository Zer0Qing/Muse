package io.zer0.muse.data.miniphone

import io.zer0.muse.miniphone.api.MpReplyEvent
import kotlinx.coroutines.flow.FlowCollector

/**
 * 模型输出被切成的一段:要念出来的,还是只当文字发的。
 *
 * [Speech] 只是「模型要求念这一段」,最终能不能成为 [MpReplyEvent.Voice] 还要看
 * 有没有中文、宿主合成是否成功 —— 两条都可能降级成 [MpReplyEvent.Text]。
 */
internal sealed interface MiniPhoneScriptPart {
    val text: String

    /** `[语音]……[/语音]` 标记内的一段。 */
    data class Speech(override val text: String) : MiniPhoneScriptPart

    /** 标记之外的一段。 */
    data class Plain(override val text: String) : MiniPhoneScriptPart
}

/**
 * 一轮回复的语音标记切分。
 *
 * 约定只有两个标记:`[语音]` 开头、`[/语音]` 结尾。标记内的一段按顺序变成 [MiniPhoneScriptPart.Speech],
 * 标记外的一律是 [MiniPhoneScriptPart.Plain],标记本身不会留在任何一段的正文里。
 *
 * 单轮最多 [MAX_VOICE_SEGMENTS] 段语音:模型偶尔会连着标十几段,超出的段降级成文本,
 * 既不让一次回复变成长语音连播,也不吞掉模型本来要说的内容。
 *
 * 没有闭合的标记按普通文字处理(`[语音]` 只被抹掉,内容当文本发):半截标记说明模型没写完,
 * 这时合成出来的多半也是半句话。
 */
internal object MiniPhoneScript {

    /** 单轮回复最多合成几段语音。 */
    const val MAX_VOICE_SEGMENTS: Int = 5

    private const val OPEN_TAG: String = "[语音]"
    private const val CLOSE_TAG: String = "[/语音]"

    /** 非贪婪匹配成对标记;`[\s\S]` 让段内换行也算内容。 */
    private val BLOCK: Regex = Regex("""\[语音\]([\s\S]*?)\[/语音\]""")

    /**
     * 按标记切段。
     *
     * @param raw 已剥掉 think 标签的模型输出
     * @param maxVoice 本次最多产出几段 [MiniPhoneScriptPart.Speech]
     * @return 按出现顺序排好的段落;空白段落不产出,全是空白时返回空列表
     */
    fun parse(raw: String, maxVoice: Int = MAX_VOICE_SEGMENTS): List<MiniPhoneScriptPart> {
        if (raw.isBlank()) return emptyList()
        val parts = mutableListOf<MiniPhoneScriptPart>()
        var cursor = 0
        var spoken = 0
        for (match in BLOCK.findAll(raw)) {
            parts.appendPlain(raw.substring(cursor, match.range.first))
            val inner = match.groupValues[1].trim()
            if (inner.isNotEmpty()) {
                if (spoken < maxVoice) {
                    spoken += 1
                    parts += MiniPhoneScriptPart.Speech(inner)
                } else {
                    parts += MiniPhoneScriptPart.Plain(inner)
                }
            }
            cursor = match.range.last + 1
        }
        parts.appendPlain(raw.substring(cursor))
        return parts
    }

    /** 标记外的一段:顺手抹掉落单的标记,再按是否空白决定要不要留下。 */
    private fun MutableList<MiniPhoneScriptPart>.appendPlain(chunk: String) {
        val text = chunk.replace(OPEN_TAG, "").replace(CLOSE_TAG, "").trim()
        if (text.isNotEmpty()) add(MiniPhoneScriptPart.Plain(text))
    }
}

/** 汉字区间:基本区、扩展 A、兼容表意、扩展 B 起的大字面。标点不算。 */
private val ChineseCodeRanges: List<IntRange> = listOf(
    0x4E00..0x9FFF,
    0x3400..0x4DBF,
    0xF900..0xFAFF,
    0x20000..0x2FA1F,
)

/**
 * 这段文字里有没有中文。
 *
 * 双语角色会把原文和译文一起贴出来:英文那半段没有汉字,交给中文音色念只会得到一个
 * 念歪英文的声音,还白花一次合成额度 —— 所以它是「要不要合成」的判据。
 */
internal fun hasChineseCharacters(text: String): Boolean = text.any { char ->
    ChineseCodeRanges.any { range -> char.code in range }
}

/**
 * 把一轮回复摊成一串事件:标记外发文本,标记内合成语音,合成不成也发文本。
 *
 * 顺序就是模型输出的顺序 —— 每段语音都等合成结果出来再继续下一段,所以界面收到的
 * 事件次序与模型写下的一致(先说的话先出现),代价是这段语音之后的文本要等它合成完。
 *
 * @param output 已剥掉 think 标签的回复正文
 * @param synthesize 语音合成入口;返回 null 表示这一段合成失败
 */
internal suspend fun FlowCollector<MpReplyEvent>.emitVoiceScript(output: String, synthesize: suspend (String) -> Pair<String, Long>?) {
    val parts = MiniPhoneScript.parse(output)
    if (parts.isEmpty()) {
        emit(MpReplyEvent.Failed)
        return
    }
    for (part in parts) {
        when (part) {
            is MiniPhoneScriptPart.Plain -> emit(MpReplyEvent.Text(part.text))
            is MiniPhoneScriptPart.Speech -> emit(speechEvent(part.text, synthesize))
        }
    }
}

/** 一段标记内的文本:有中文才合成,合成失败或没有中文都退化成文本。 */
private suspend fun speechEvent(text: String, synthesize: suspend (String) -> Pair<String, Long>?): MpReplyEvent {
    if (!hasChineseCharacters(text)) return MpReplyEvent.Text(text)
    val voice = synthesize(text) ?: return MpReplyEvent.Text(text)
    return MpReplyEvent.Voice(uri = voice.first, durationMs = voice.second)
}
