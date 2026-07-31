package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage

/**
 * 表情包情绪检测器(v1.0.52) — 本地关键词打分,无网络依赖。
 *
 * 用途:检测最近对话中用户消息的情绪强度,供 ChatStreamCoordinator 调制
 * 表情包工具暴露概率(情绪强烈时更倾向于让 AI 发表情包)。
 *
 * 设计:
 *  - 纯函数无状态,便于单测
 *  - 中英文关键词词典 + 语气符号加成(！！！/😭/😂 等)
 *  - 输出主导情绪 + 强度(0..1),NEUTRAL 时强度为 0
 *
 * 注意:这是轻量启发式,不做真正的 NLP。词典覆盖常见口语表达即可,
 * 误判代价低(最多影响一次表情包概率),因此不做复杂消歧。
 */
object StickerEmotionDetector {

    enum class EmotionType { ANGRY, SAD, JOYFUL, EXCITED, NEUTRAL }

    data class EmotionResult(
        val dominant: EmotionType,
        /** 0..1,NEUTRAL 恒为 0。 */
        val intensity: Float,
    )

    // ── 关键词词典(命中即计分) ──────────────────────────────────────────
    // 按类分组,每组内词条权重相同;语气符号单独加权。

    private val ANGRY_WORDS = listOf(
        "生气", "愤怒", "气死", "烦死", "烦", "讨厌", "无语", "恶心", "滚", "闭嘴",
        "吵", "暴躁", "火大", "不满", "投诉", "差评", "垃圾", "坑", "离谱", "过分",
        "什么鬼", "搞什么", "服了", "麻了", "炸了", "受不了", "受够", "呵呵", "呵",
        "fuck", "angry", "wtf", "气", "烦人",
    )

    private val SAD_WORDS = listOf(
        "难过", "伤心", "悲伤", "委屈", "失落", "沮丧", "低落", "emo", "崩溃", "想哭",
        "哭", "呜呜", "唉", "哎", "叹气", "孤独", "寂寞", "迷茫", "焦虑", "压力",
        "累", "疲惫", "没意思", "没劲", "算了", "罢了", "放弃", "绝望", "心累", "内耗",
        "sad", "cry", "depressed", "泪",
    )

    private val JOYFUL_WORDS = listOf(
        "哈哈", "嘻嘻", "开心", "高兴", "快乐", "不错", "棒", "好耶", "太好了", "满意",
        "舒服", "爽", "赞", "喜欢", "爱了", "可爱", "好笑", "有意思", "有趣", "香",
        "happy", "great", "nice", "love", "耶", "哈",
    )

    private val EXCITED_WORDS = listOf(
        "卧槽", "牛逼", "牛", "绝了", "太棒", "太强", "顶", "冲", "起飞", "燃",
        "炸裂", "震撼", "惊艳", "神仙", "yyds", "awesome", "amazing", "无敌", "超预期",
        "厉害", "优秀", "哇", "哇塞", "啊啊啊", "好强", "真香",
    )

    // 语气符号:单独计分,增强所有非 NEUTRAL 情绪
    private val STRONG_PUNCTUATION = listOf("！！！", "!!!", "？？？", "???", "😡", "😭", "😂", "🤣", "🔥", "💢", "😤")

    // ── 公开接口 ─────────────────────────────────────────────────────────

    /**
     * 检测最近用户消息的情绪。
     *
     * @param messages 用户消息列表(只取 USER 角色,内部按传入顺序取最近 [limit] 条)
     * @param limit 参与检测的最大消息条数(默认 6)
     */
    fun detectEmotion(messages: List<UIMessage>, limit: Int = 6): EmotionResult {
        val userTexts = messages
            .filter { it.role == MessageRole.USER && it.content.isNotBlank() }
            .takeLast(limit)
            .joinToString("\n") { it.content.lowercase() }
        if (userTexts.isBlank()) return EmotionResult(EmotionType.NEUTRAL, 0f)

        val angry = score(ANGRY_WORDS, userTexts)
        val sad = score(SAD_WORDS, userTexts)
        val joyful = score(JOYFUL_WORDS, userTexts)
        val excited = score(EXCITED_WORDS, userTexts)

        val punctuationBoost = STRONG_PUNCTUATION.count { userTexts.contains(it) } * 0.15f

        val candidates = listOf(
            EmotionType.ANGRY to angry,
            EmotionType.SAD to sad,
            EmotionType.JOYFUL to joyful,
            EmotionType.EXCITED to excited,
        )
        val (dominant, rawScore) = candidates.maxByOrNull { it.second } ?: return EmotionResult(EmotionType.NEUTRAL, 0f)

        val total = rawScore + punctuationBoost
        if (total <= 0f) return EmotionResult(EmotionType.NEUTRAL, 0f)

        // 归一化:3 分以上视为满强度(约等于 3 个强关键词命中)
        val intensity = (total / 3f).coerceIn(0f, 1f)
        return EmotionResult(dominant, intensity)
    }

    // ── 内部实现 ─────────────────────────────────────────────────────────

    /** 统计文本中命中词典的词数。 */
    private fun score(dictionary: List<String>, text: String): Float {
        return dictionary.count { text.contains(it) }.toFloat()
    }
}
