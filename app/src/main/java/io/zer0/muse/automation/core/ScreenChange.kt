package io.zer0.muse.automation.core

/**
 * 动作前后的**画面变化证据**（对比清单 P1-06「滚动证据 / 动作后观察」）。
 *
 * 要回答的问题很朴素：刚才这一下**到底有没有效果**。
 * 之前的工具只说"已滑动"，可滑动到边界时同样是"已滑动" —— 模型据此以为翻页了，
 * 接着按新页面的坐标去点，点到的还是旧页面的位置。
 *
 * 三态而不是两态：读不到前后画面时（通道断了、读屏超时）必须说"无法确认"，
 * 不能因为"没看到变化"就断言"没变化"。
 */
object ScreenChange {

    enum class Evidence { CHANGED, UNCHANGED, UNKNOWN }

    /** 用两次读屏的指纹比对得出结论。任一指纹为空表示没读到。 */
    fun compare(before: String?, after: String?): Evidence = when {
        before.isNullOrBlank() || after.isNullOrBlank() -> Evidence.UNKNOWN
        before == after -> Evidence.UNCHANGED
        else -> Evidence.CHANGED
    }

    /** 滑动后的结论（给模型看的一句话）。 */
    fun scrollResult(evidence: Evidence): String = when (evidence) {
        Evidence.CHANGED -> "已滑动，内容已移动"
        Evidence.UNCHANGED ->
            "已滑动，但画面没有变化：可能已到边界，或这块区域不可滚动。" +
                "**不要重复滑动** —— 先 screen_read 看当前位置，再决定下一步"
        Evidence.UNKNOWN -> "已滑动，但读不到前后画面，**无法确认是否生效**（通道可能不稳）"
    }

    /** 点击/输入后的结论。 */
    fun afterActionResult(evidence: Evidence): String = when (evidence) {
        Evidence.CHANGED -> "画面已变化"
        Evidence.UNCHANGED -> "画面未变化（这一步可能没有生效，或本就不改变界面）"
        Evidence.UNKNOWN -> "画面无法确认（读屏失败）"
    }

    /** 从读屏结果算指纹；各字段缺失时退化成空串（仍可比较）。 */
    fun fingerprintOf(info: ScreenInfo): String = AutomationObservationRegistry.fingerprintOf(
        packageName = info.packageName,
        activityName = info.activityName,
        nodeTexts = info.nodes.mapNotNull { it.text },
    )
}
