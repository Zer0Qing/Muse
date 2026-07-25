package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.chat.MessageNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.uuid.Uuid

/**
 * 消息分支管理器（移植自 RikkaHub 消息分支 ViewModel 集成）。
 *
 * 为 ChatViewModel 管理消息分支状态。
 * 每个助手响应位置可以拥有多个分支（来自重新生成）。
 *
 * 用法：
 * 1. 用户发送消息时调用 [onMessageSent] → 创建分支追踪
 * 2. 助手响应时调用 [onAssistantResponse] → 添加到当前分支
 * 3. 调用 [regenerate] 在最后一个位置创建新分支
 * 4. 调用 [selectBranch] 在分支之间切换
 * 5. [displayMessages] 流提供当前视图（仅包含已选中的分支）
 */
class MessageBranchManager {

    private val _nodes = MutableStateFlow<List<MessageNode>>(emptyList())
    val nodes: StateFlow<List<MessageNode>> = _nodes.asStateFlow()

    private val _displayMessages = MutableStateFlow<List<UIMessage>>(emptyList())
    val displayMessages: StateFlow<List<UIMessage>> = _displayMessages.asStateFlow()

    /** 每个节点位置的当前分支状态。 */
    private val branchStates = mutableMapOf<String, Int>() // nodeId → 选中索引

    /** 从外部消息列表更新（例如从数据库加载）。 */
    fun syncFromMessages(messages: List<UIMessage>) {
        val nodes = messages.map { msg ->
            MessageNode(
                id = msg.id.toString(),
                messages = listOf(msg),
                selectIndex = branchStates[msg.id.toString()] ?: 0,
            )
        }
        _nodes.value = nodes
        refreshDisplay()
    }

    /** 用户发送新消息时调用。 */
    fun onMessageSent(message: UIMessage) {
        val node = MessageNode.from(message)
        _nodes.update { it + node }
        branchStates[node.id] = 0
        refreshDisplay()
    }

    /** 助手响应到达时调用。 */
    fun onAssistantResponse(message: UIMessage, isNewBranch: Boolean = false) {
        if (isNewBranch) {
            // 作为新分支添加到最后一个助手节点
            _nodes.update { nodes ->
                if (nodes.isEmpty()) return@update nodes
                val lastNode = nodes.last()
                if (lastNode.messages.firstOrNull()?.role == MessageRole.ASSISTANT) {
                    val updated = lastNode.addBranch(message)
                    branchStates[updated.id] = updated.selectIndex
                    nodes.dropLast(1) + updated
                } else {
                    val node = MessageNode.from(message)
                    branchStates[node.id] = 0
                    nodes + node
                }
            }
        } else {
            // 替换当前分支（流式更新）
            _nodes.update { nodes ->
                if (nodes.isEmpty()) return@update nodes
                val lastNode = nodes.last()
                if (lastNode.messages.firstOrNull()?.role == MessageRole.ASSISTANT &&
                    lastNode.messages.firstOrNull()?.id == message.id
                ) {
                    val updated = lastNode.replaceCurrent(message)
                    nodes.dropLast(1) + updated
                } else {
                    val node = MessageNode.from(message)
                    branchStates[node.id] = 0
                    nodes + node
                }
            }
        }
        refreshDisplay()
    }

    /** 切换到指定节点位置的另一个分支。 */
    fun selectBranch(nodeId: String, index: Int) {
        branchStates[nodeId] = index
        _nodes.update { nodes ->
            nodes.map { node ->
                if (node.id == nodeId) node.selectBranch(index) else node
            }
        }
        refreshDisplay()
    }

    /** 获取某个节点的当前分支索引。 */
    fun getBranchIndex(nodeId: String): Int = branchStates[nodeId] ?: 0

    /** 获取某个节点的分支总数。 */
    fun getBranchCount(nodeId: String): Int {
        return _nodes.value.firstOrNull { it.id == nodeId }?.branchCount ?: 1
    }

    /** 检查某个节点是否有多个分支。 */
    fun hasBranches(nodeId: String): Boolean = getBranchCount(nodeId) > 1

    /** 标记最后一个助手位置以进行重新生成。 */
    fun prepareRegeneration(): Boolean {
        val nodes = _nodes.value
        // 查找最后一个助手节点
        val lastAssistantIdx = nodes.indexOfLast { node ->
            node.messages.firstOrNull()?.role == MessageRole.ASSISTANT
        }
        if (lastAssistantIdx < 0) return false

        // 保留当前分支，但标记为需要创建新分支
        return true
    }

    /** 移除最后一个助手分支（用于重新生成）。 */
    fun removeLastBranch(): List<UIMessage> {
        val nodes = _nodes.value
        if (nodes.isEmpty()) return emptyList()

        val lastNode = nodes.last()
        if (lastNode.branchCount > 1 && lastNode.messages.firstOrNull()?.role == MessageRole.ASSISTANT) {
            // 移除当前分支，选中前一个
            val newSelectIndex = (lastNode.selectIndex - 1).coerceAtLeast(0)
            val updatedNode = lastNode.copy(
                messages = lastNode.messages.filterIndexed { i, _ -> i != lastNode.selectIndex },
                selectIndex = newSelectIndex,
            )
            branchStates[updatedNode.id] = newSelectIndex
            _nodes.update { it.dropLast(1) + updatedNode }
        }
        refreshDisplay()
        return _displayMessages.value
    }

    private fun refreshDisplay() {
        _displayMessages.value = _nodes.value.map { it.currentMessage }
    }
}
