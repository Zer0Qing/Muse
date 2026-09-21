package io.zer0.muse.ui.chat

import io.zer0.ai.core.UIMessage

/**
 * 聊天列表的渲染层展示单元。
 *
 * 把"每条消息一个列表项"细化为"单条 / 一组连续工具调用"，
 * 用于把多轮工具操作从"一长串分散卡片"收敛为"一张操作组卡"。
 *
 * 注意:这只是**渲染层**分组,不改变消息数据本身。
 * [messageIds] 记录该展示单元覆盖的消息 id,供滚动定位/搜索高亮做"消息 → 列表项"索引映射。
 */
sealed interface ChatDisplayItem {
    /** 该展示单元覆盖的消息 id(按原顺序)。 */
    val messageIds: List<String>

    /** 单条消息。 */
    data class Single(val msg: UIMessage) : ChatDisplayItem {
        override val messageIds: List<String> get() = listOf(msg.id.toString())
    }

    /** 一组**连续**的可聚合消息(如连续的工具调用)。成立条件:数量 >= [ChatDisplayGrouper.MIN_RUN_SIZE]。 */
    data class Grouped(val msgs: List<UIMessage>) : ChatDisplayItem {
        override val messageIds: List<String> get() = msgs.map { it.id.toString() }
    }
}

/**
 * 把消息列表按"连续可聚合段"分组。
 *
 * 规则:
 *  - 连续 >= [MIN_RUN_SIZE] 条满足 [isGroupable] 的消息 → 一个 [ChatDisplayItem.Grouped];
 *  - 其余消息各自成 [ChatDisplayItem.Single];
 *  - 单条可聚合消息**不**成组(一条工具调用单独成"组"反而更怪,保持单卡)。
 *
 * 判定条件由调用方以 [isGroupable] 传入(例如"有 toolCallInfo 且没有任务卡"),
 * 这样 taskCard 之类的 UI 状态不必泄漏进本函数 —— 保持纯函数、可单测。
 */
object ChatDisplayGrouper {

    /** 成组所需的最少连续条数。 */
    const val MIN_RUN_SIZE = 2

    fun group(
        messages: List<UIMessage>,
        isGroupable: (UIMessage) -> Boolean,
    ): List<ChatDisplayItem> {
        if (messages.isEmpty()) return emptyList()

        val result = ArrayList<ChatDisplayItem>(messages.size)
        var index = 0
        while (index < messages.size) {
            if (!isGroupable(messages[index])) {
                result += ChatDisplayItem.Single(messages[index])
                index++
                continue
            }
            // 收集连续可聚合段
            var end = index
            while (end < messages.size && isGroupable(messages[end])) end++
            val run = messages.subList(index, end)
            if (run.size >= MIN_RUN_SIZE) {
                result += ChatDisplayItem.Grouped(run.toList())
            } else {
                result += ChatDisplayItem.Single(run.first())
            }
            index = end
        }
        return result
    }

    /**
     * 建立"消息 id → 展示单元下标"的索引,供滚动定位/搜索高亮在分组后仍能定位。
     *
     * 搜索跳转的目标若是组内某条消息,应跳到该组的列表项下标。
     */
    fun indexByMessageId(items: List<ChatDisplayItem>): Map<String, Int> {
        val map = HashMap<String, Int>()
        items.forEachIndexed { itemIndex, item ->
            item.messageIds.forEach { id -> map.putIfAbsent(id, itemIndex) }
        }
        return map
    }
}
