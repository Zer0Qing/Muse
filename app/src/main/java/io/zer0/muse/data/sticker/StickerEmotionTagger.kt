package io.zer0.muse.data.sticker

import io.zer0.muse.ui.chat.StickerEmotionDetector

/**
 * v2.7.x(E4): 表情注册时打情绪标签。
 *
 * 为什么用词典而不是视觉模型:
 *  计划里明确写了「不做全量随机拼图 + 视觉模型选择」。表情库常有几百张,
 *  逐张跑视觉模型意味着导入一次就要等几十秒并花钱,而**文件名与配文里本来就写着情绪**
 *  ("生气猫.png"、"小猫歪头 委屈")。词典能拿到 80% 的价值,代价是 0 秒和 0 元。
 *  剩下的部分交给手动标注(`setEmotions`)补,而不是让模型猜。
 *
 * 标签是**可空的**:没标到的条目保持空列表。空列表在选取时按"不限情绪"处理,
 * 所以打标签这个功能不会让任何一张表情突然选不出来 —— 这是"宁漏不错"的另一面:
 * 漏标可以让表情普通,错标不能让表情消失。
 */
object StickerEmotionTagger {

    /** 参与打分的文本片段上限:文件名 + 配文 + 分类名,不必长。 */
    private const val MAX_TEXT_CHARS = 400

    /**
     * E4:低使用退场的观察期(30 天)。
     *
     * 短于这个时间就退场,等于删掉还没轮到被使用的表情:
     * 一张表情可能只是因为「这周没聊到那个话题」而零使用,那不叫低使用。
     */
    const val RETIRE_AFTER_MILLIS = 30L * 24 * 60 * 60 * 1000

    /** 一张表情最多带几个情绪标签(防止一个常见字把整包都标成"开心")。 */
    const val MAX_TAGS = 3

    /**
     * 由注册时已知的文本打标签。
     *
     * 三个来源一起看:文件名常常最直白(`哈哈笑.gif`),配文是导入素材里的描述,
     * 分类名则是用户自己起的包名("怼人专用")。
     */
    fun tagOf(fileName: String, caption: String? = null, category: String? = null): List<String> {
        val text = buildString {
            append(fileName.lowercase())
            caption?.let { append(' ').append(it.lowercase()) }
            category?.let { append(' ').append(it.lowercase()) }
        }.take(MAX_TEXT_CHARS)
        return StickerEmotionDetector.emotionsOf(text).take(MAX_TAGS).map { it.name.lowercase() }
    }

    /**
     * 条目是否属于给定情绪集合。
     *
     * 没打标签的条目返回 true:它是"没有立场",不是"不属于任何情绪"。
     * 把它当 false 的话,一个没标过的包会在标上第一个标签的那一刻整体消失。
     */
    fun matches(itemEmotions: List<String>, wanted: List<String>): Boolean {
        if (wanted.isEmpty()) return true
        if (itemEmotions.isEmpty()) return true
        return itemEmotions.any { emotion -> wanted.any { it.equals(emotion, ignoreCase = true) } }
    }
}
