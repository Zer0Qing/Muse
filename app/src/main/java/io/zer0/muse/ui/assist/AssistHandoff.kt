package io.zer0.muse.ui.assist

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 系统助理（Assist / 长按 Home 或电源键）交给我们的上下文。
 *
 * 字段全部可空：不同 ROM、不同应用能提供的东西差别很大，
 * 拿不到的部分必须留空，**不能编**。
 */
data class AssistSnapshot(
    val packageName: String? = null,
    val activityName: String? = null,
    /** AssistStructure 里可见的文本（已按屏幕顺序拼接）。 */
    val visibleText: String? = null,
    /** AssistContent 里声明的网页标题 / 正文提示。 */
    val contentText: String? = null,
    /** 是否真的拿到了截图（目前不采集，恒为 false —— 不要谎报）。 */
    val hasScreenshot: Boolean = false,
)

/**
 * 把 Assist 上下文压成一段给模型看的开场提示。
 *
 * 只做"如实转述"：有什么写什么，没有的段落整段不出现。
 * 拼出一堆"（未知）"会让模型以为拿到了信息，反而更容易乱猜。
 */
object AssistHandoff {

    /** 屏幕文字上限：Assist 结构里可能有整页列表，全塞进输入框会挤掉用户自己的话。 */
    const val MAX_TEXT_CHARS = 2_000

    fun buildPrompt(snapshot: AssistSnapshot): String {
        val where = listOfNotNull(
            snapshot.packageName?.takeIf { it.isNotBlank() },
            snapshot.activityName?.takeIf { it.isNotBlank() },
        ).joinToString(" / ")
        val text = snapshot.visibleText
            ?.takeIf { it.isNotBlank() }
            ?.trim()
            ?.take(MAX_TEXT_CHARS)
        val content = snapshot.contentText?.takeIf { it.isNotBlank() }?.trim()
        return buildString {
            appendLine("（来自系统助理）")
            if (where.isNotEmpty()) appendLine("当前界面：$where")
            if (content != null) appendLine("内容提示：$content")
            if (text != null) {
                appendLine("屏幕上的文字：")
                append(text)
            }
            if (where.isEmpty() && content == null && text == null) {
                append("我刚刚唤起了助手，但没能读到当前界面的内容。")
            }
        }.trim()
    }
}

/**
 * Assist 与界面之间的信箱。
 *
 * 为什么需要它：Session 跑在系统进程发起的服务里，拿不到聊天页的 ViewModel。
 * 用一条单向的信箱把"刚拿到的上下文"交给界面，界面决定是填进输入框还是丢弃 ——
 * **不自动发送**：助手替用户按下发送键，等于替用户说话。
 */
object AssistInbox {

    private val _pending = MutableStateFlow<String?>(null)

    /** 界面订阅它：非空表示有一份待处理的助理上下文。 */
    val pending: StateFlow<String?> = _pending

    fun publish(prompt: String) {
        if (prompt.isBlank()) return
        _pending.value = prompt
    }

    /** 界面取走并清空（避免旋转屏幕后重复填入）。 */
    fun consume(): String? {
        val value = _pending.value
        _pending.value = null
        return value
    }

    internal fun resetForTests() {
        _pending.value = null
    }
}
