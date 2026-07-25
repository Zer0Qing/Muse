package io.zer0.muse.data.chat

import io.zer0.ai.core.UIMessage

/**
 * 消息分支节点（移植自 RikkaHub MessageNode）。
 *
 * 会话中的每个位置可以持有多个备选消息（分支）。
 * [selectIndex] 决定当前激活的是哪个分支。
 *
 * 示例：用户发送 "hello" → 助手响应分支 A。
 * 用户点击 "重新生成" → 助手响应分支 B。
 * MessageNode 持有 [A, B]，selectIndex=1（展示 B）。
 * 用户可以通过将 selectIndex 改为 0 切换回 A。
 */
data class MessageNode(
    val id: String,
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
) {
    /** 该节点当前激活的消息。 */
    val currentMessage: UIMessage
        get() = messages.getOrNull(selectIndex.coerceIn(0, messages.lastIndex))
            ?: messages.last()

    /** 分支数量。 */
    val branchCount: Int get() = messages.size

    /** 是否存在多个可切换的分支。 */
    val hasBranches: Boolean get() = messages.size > 1

    /** 切换到另一个分支。返回更新了 selectIndex 的新节点。 */
    fun selectBranch(index: Int): MessageNode {
        val safeIndex = index.coerceIn(0, messages.lastIndex)
        return copy(selectIndex = safeIndex)
    }

    /** 添加一个新分支（例如来自重新生成）。自动选中该新分支。 */
    fun addBranch(message: UIMessage): MessageNode {
        return copy(
            messages = messages + message,
            selectIndex = messages.size, // 选中新增的分支
        )
    }

    /** 替换当前分支的消息。 */
    fun replaceCurrent(message: UIMessage): MessageNode {
        val updated = messages.toMutableList()
        if (selectIndex in updated.indices) {
            updated[selectIndex] = message
        }
        return copy(messages = updated)
    }

    companion object {
        /** 从单条消息创建一个节点。 */
        fun from(message: UIMessage): MessageNode =
            MessageNode(
                id = message.id.toString(),
                messages = listOf(message),
                selectIndex = 0,
            )
    }
}

/**
 * 将 UIMessage 列表转换为 MessageNode 列表。
 * 每条用户消息都会开启一个新的节点组（用户 + 助手配对）。
 */
fun List<UIMessage>.toNodes(): List<MessageNode> {
    if (isEmpty()) return emptyList()
    return map { MessageNode.from(it) }
}

/**
 * 将节点重新展平为 UIMessage 列表（使用已选中的分支）。
 */
fun List<MessageNode>.toMessages(): List<UIMessage> =
    map { it.currentMessage }
