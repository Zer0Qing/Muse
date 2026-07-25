package io.zer0.muse.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.201: 委派暂停/取消管理器。
 *
 * 管理正在进行的委派任务的暂停、恢复、取消状态。
 * 与 DelegationPausePoint 配合,在关键节点等待用户决策。
 */
class DelegationPauseManager {

    /** 暂停决策类型。 */
    enum class PauseDecision {
        APPROVE,        // 批准继续
        MODIFY,         // 修改后继续(携带新输入)
        REJECT,         // 拒绝并终止
        CANCEL,         // 取消整个委派
    }

    /** 暂停请求(展示给用户)。 */
    data class PauseRequest(
        val requestId: String,
        val taskId: String,
        val taskTitle: String,
        val taskDescription: String,
        val targetType: String,         // "assistant" | "team"
        val targetName: String,
        val reason: String,             // 为什么需要确认
        val intermediateResult: String? = null,  // 中间结果(可选)
        val options: List<PauseOption> = listOf(PauseOption.APPROVE, PauseOption.REJECT),
    )

    /** 暂停选项(用户可选择)。 */
    data class PauseOption(
        val id: String,
        val label: String,
        val decision: PauseDecision,
        val isDestructive: Boolean = false,
    ) {
        companion object {
            val APPROVE = PauseOption("approve", "批准继续", PauseDecision.APPROVE)
            val REJECT = PauseOption("reject", "拒绝", PauseDecision.REJECT, isDestructive = true)
            val CANCEL = PauseOption("cancel", "取消委派", PauseDecision.CANCEL, isDestructive = true)
            val MODIFY = PauseOption("modify", "修改后继续", PauseDecision.MODIFY)
        }
    }

    /** 用户的响应。 */
    data class PauseResponse(
        val decision: PauseDecision,
        val modifiedInput: String? = null,  // MODIFY 时的修改后输入
    )

    /** 暂停点配置策略。 */
    @Serializable
    data class PausePolicy(
        val pauseBeforeTeam: Boolean = false,        // 团队工作流执行前
        val pauseBeforeEachMember: Boolean = false,  // 团队每个成员执行前
        val pauseOnIntermediateResult: Boolean = false, // 中间结果产出后
        val pauseOnHighRisk: Boolean = true,         // 高风险任务前(默认开启)
        val autoTimeoutSec: Int = 300,               // 暂停等待超时(秒),超时自动拒绝
    )

    /** 当前活跃的暂停请求(requestId -> PauseRequest)。 */
    private val _activePauses = MutableStateFlow<Map<String, PauseRequest>>(emptyMap())
    val activePauses: StateFlow<Map<String, PauseRequest>> = _activePauses.asStateFlow()

    /** 等待中的 CompletableDeferred,用于协程挂起等待用户响应。 */
    private val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<PauseResponse>>()

    /** 已取消的 requestId 集合(用于 cancelDelegation 标记)。 */
    private val cancelledRequests = ConcurrentHashMap.newKeySet<String>()

    /**
     * 在协程中等待用户对暂停点的决策。
     * 调用方(suspend 函数)会在此挂起,直到用户响应或超时。
     */
    suspend fun awaitPauseDecision(request: PauseRequest, policy: PausePolicy): PauseResponse {
        val deferred = CompletableDeferred<PauseResponse>()
        pendingResponses[request.requestId] = deferred
        _activePauses.update { it + (request.requestId to request) }

        return try {
            withTimeoutOrNull(policy.autoTimeoutSec * 1000L) {
                deferred.await()
            } ?: PauseResponse(PauseDecision.REJECT, "暂停等待超时,自动拒绝")
        } finally {
            pendingResponses.remove(request.requestId)
            _activePauses.update { it - request.requestId }
        }
    }

    /** 用户提交决策。 */
    fun submitDecision(requestId: String, response: PauseResponse) {
        pendingResponses[requestId]?.complete(response)
    }

    /** 取消指定委派(用户主动取消)。 */
    fun cancelDelegation(requestId: String) {
        cancelledRequests.add(requestId)
        pendingResponses[requestId]?.complete(PauseResponse(PauseDecision.CANCEL))
    }

    /** 检查委派是否已被取消。 */
    fun isCancelled(requestId: String): Boolean = cancelledRequests.contains(requestId)

    /** 清理已结束的取消标记。 */
    fun clearCancellation(requestId: String) {
        cancelledRequests.remove(requestId)
    }

    /** 清空所有状态(切换会话时调用)。 */
    fun clearAll() {
        _activePauses.value = emptyMap()
        pendingResponses.clear()
        cancelledRequests.clear()
    }
}
