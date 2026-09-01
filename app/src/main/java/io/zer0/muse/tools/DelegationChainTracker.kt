package io.zer0.muse.tools

import io.zer0.muse.ui.taskcard.DelegationNodeStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * v1.201: 委派链路追踪器。
 *
 * 维护当前会话内所有委派请求的链路状态,供 UI 实时展示。
 * 一个 requestId 对应一个链路节点(可能包含 subResults 形成树形结构)。
 *
 * v1.202 扩展:
 *  - [onDelegationStarted] 支持 parentRequestId(默认 null),非空时把新节点
 *    作为子节点递归插入到父节点的 subNodes 中(而不是顶层 _chains),
 *    使团队工作流的树形结构能在 UI 呈现。
 *  - 新增 [setSubNodes] / [updateSubNode],供 TeamWorkflowExecutor 填充/更新子节点。
 */
class DelegationChainTracker {

    /** 单个委派节点(对应一次 delegate_agent 调用)。 */
    data class ChainNode(
        val requestId: String,
        val parentRequestId: String?,
        val task: String,
        val targetType: String,         // "assistant" | "team"
        val targetId: String,
        val targetName: String,
        val status: DelegationNodeStatus,
        val startedAt: Long,
        val finishedAt: Long? = null,
        val errorMessage: String? = null,
        val resultPreview: String? = null,  // 结果预览(前 200 字)
        val subNodes: List<ChainNode> = emptyList(),
    )

    private val _chains = MutableStateFlow<Map<String, ChainNode>>(emptyMap())
    val chains: StateFlow<Map<String, ChainNode>> = _chains.asStateFlow()

    /**
     * 记录委派开始。
     *
     * @param parentRequestId 父委派 id(团队工作流成员执行时传入);为 null 时作为顶层节点
     */
    fun onDelegationStarted(
        requestId: String,
        parentRequestId: String? = null,
        task: String,
        targetType: String,
        targetId: String,
        targetName: String,
    ) {
        val node = ChainNode(
            requestId = requestId,
            parentRequestId = parentRequestId,
            task = task,
            targetType = targetType,
            targetId = targetId,
            targetName = targetName,
            status = DelegationNodeStatus.RUNNING,
            startedAt = System.currentTimeMillis(),
        )
        _chains.update { current ->
            if (parentRequestId == null) {
                // 顶层节点:直接放入 _chains
                current + (requestId to node)
            } else {
                // 子节点:递归查找父节点并加入其 subNodes(不放入顶层 _chains)
                insertSubNode(current, parentRequestId, node) ?: current
            }
        }
    }

    /** 记录委派完成。 */
    fun onDelegationFinished(
        requestId: String,
        success: Boolean,
        resultText: String,
        error: String? = null,
        subResults: List<DelegationContract.DelegationResult> = emptyList(),
    ) {
        _chains.update { current ->
            updateNode(current, requestId) { node ->
                node.copy(
                    status = if (success) DelegationNodeStatus.COMPLETED
                             else DelegationNodeStatus.FAILED,
                    finishedAt = System.currentTimeMillis(),
                    errorMessage = error,
                    resultPreview = resultText.take(200),
                )
            } ?: current
        }
    }

    /**
     * 填充子节点(团队工作流每个成员的结果)。
     *
     * 在 _chains 中找到 [requestId] 对应的节点,设置其 subNodes,原子更新 StateFlow。
     */
    fun setSubNodes(requestId: String, subNodes: List<ChainNode>) {
        _chains.update { current ->
            updateNode(current, requestId) { node ->
                node.copy(subNodes = subNodes)
            } ?: current
        }
    }

    /**
     * 更新单个子节点状态(工作流成员完成时调用)。
     *
     * 找到父节点 [parentRequestId] 的 subNodes 中对应 [childRequestId] 的节点,更新状态。
     */
    fun updateSubNode(
        parentRequestId: String,
        childRequestId: String,
        success: Boolean,
        resultText: String?,
        error: String?,
    ) {
        _chains.update { current ->
            updateNode(current, parentRequestId) { parent ->
                val newSubs = parent.subNodes.map { child ->
                    if (child.requestId == childRequestId) {
                        child.copy(
                            status = if (success) DelegationNodeStatus.COMPLETED
                                     else DelegationNodeStatus.FAILED,
                            finishedAt = System.currentTimeMillis(),
                            resultPreview = resultText?.take(200),
                            errorMessage = error,
                        )
                    } else {
                        child
                    }
                }
                parent.copy(subNodes = newSubs)
            } ?: current
        }
    }

    /** 清空链路(切换会话时调用)。 */
    fun clear() {
        _chains.value = emptyMap()
    }

    /** 获取顶级链路节点(parentRequestId == null)。 */
    fun getRoots(): List<ChainNode> = _chains.value.values
        .filter { it.parentRequestId == null }
        .sortedBy { it.startedAt }

    /**
     * 递归查找父节点并把子节点加入其 subNodes。
     * 返回 null 表示未找到父节点。
     */
    private fun insertSubNode(
        nodes: Map<String, ChainNode>,
        parentRequestId: String,
        child: ChainNode,
    ): Map<String, ChainNode>? {
        // 顶层命中
        val parent = nodes[parentRequestId]
        if (parent != null) {
            val updatedParent = parent.copy(subNodes = parent.subNodes + child)
            return nodes + (parentRequestId to updatedParent)
        }
        // 递归在 subNodes 中查找
        var updated = false
        val newMap = nodes.mapValues { (_, node) ->
            val newSubs = insertSubNodeInList(node.subNodes, parentRequestId, child)
            if (newSubs !== node.subNodes) {
                updated = true
                node.copy(subNodes = newSubs)
            } else {
                node
            }
        }
        return if (updated) newMap else null
    }

    /** 在 [list] 中递归查找父节点并插入子节点;未变更则返回原列表引用。 */
    private fun insertSubNodeInList(
        list: List<ChainNode>,
        parentRequestId: String,
        child: ChainNode,
    ): List<ChainNode> {
        var updated = false
        val newList = list.map { node ->
            if (node.requestId == parentRequestId) {
                updated = true
                node.copy(subNodes = node.subNodes + child)
            } else {
                val newSubs = insertSubNodeInList(node.subNodes, parentRequestId, child)
                if (newSubs !== node.subNodes) {
                    updated = true
                    node.copy(subNodes = newSubs)
                } else {
                    node
                }
            }
        }
        return if (updated) newList else list
    }

    /**
     * 递归查找并更新节点。
     * 返回 null 表示未找到 [requestId] 对应节点。
     */
    private fun updateNode(
        nodes: Map<String, ChainNode>,
        requestId: String,
        transform: (ChainNode) -> ChainNode,
    ): Map<String, ChainNode>? {
        // 顶层命中
        val node = nodes[requestId]
        if (node != null) {
            return nodes + (requestId to transform(node))
        }
        // 递归在 subNodes 中查找
        var updated = false
        val newMap = nodes.mapValues { (_, n) ->
            val newSubs = updateNodeInList(n.subNodes, requestId, transform)
            if (newSubs !== n.subNodes) {
                updated = true
                n.copy(subNodes = newSubs)
            } else {
                n
            }
        }
        return if (updated) newMap else null
    }

    /** 在 [list] 中递归查找并更新节点;未变更则返回原列表引用。 */
    private fun updateNodeInList(
        list: List<ChainNode>,
        requestId: String,
        transform: (ChainNode) -> ChainNode,
    ): List<ChainNode> {
        var updated = false
        val newList = list.map { node ->
            if (node.requestId == requestId) {
                updated = true
                transform(node)
            } else {
                val newSubs = updateNodeInList(node.subNodes, requestId, transform)
                if (newSubs !== node.subNodes) {
                    updated = true
                    node.copy(subNodes = newSubs)
                } else {
                    node
                }
            }
        }
        return if (updated) newList else list
    }
}
