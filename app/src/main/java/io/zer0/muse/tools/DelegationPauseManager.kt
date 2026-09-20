package io.zer0.muse.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** awaitPauseDecision 的结构化结束原因。 */
    enum class PauseOutcome {
        /** 用户提交了正常决策。 */
        DECIDED,
        /** 等待超过策略配置的超时时间。 */
        TIMED_OUT,
        /** 等待因委派取消或管理器清理而结束。 */
        CANCELLED,
        ;

        companion object {
            /** 兼容常见命名的超时别名。 */
            val TIMEOUT: PauseOutcome get() = TIMED_OUT
            /** 兼容美式拼写的取消别名。 */
            val CANCELED: PauseOutcome get() = CANCELLED
        }
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
        /** 所属父委派 requestId;根委派为 null。 */
        val parentRequestId: String? = null,
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
        /** 结构化结束原因;旧构造调用默认表示用户提交了决策。 */
        val outcome: PauseOutcome = PauseOutcome.DECIDED,
        /** 超时或取消等非正常结束时的可测试诊断信息。 */
        val reason: String? = null,
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

    /** parentRequestId -> 当前父委派下活跃的暂停 requestId。 */
    private val activeRequestIdsByParent = ConcurrentHashMap<String, MutableSet<String>>()

    /** requestId -> parentRequestId,用于 await finally 时精确移除映射。 */
    private val parentRequestIdByRequest = ConcurrentHashMap<String, String>()

    /** 已取消的 requestId 集合(用于 cancelDelegation 标记)。 */
    private val cancelledRequests = ConcurrentHashMap.newKeySet<String>()

    /** 串行化注册/清理,避免 clearAll 与新暂停请求竞争导致 Deferred 丢失。 */
    private val stateLock = Any()

    /**
     * 在协程中等待用户对暂停点的决策。
     * 调用方(suspend 函数)会在此挂起,直到用户响应或超时。
     *
     * 超时返回 [PauseOutcome.TIMED_OUT] + [PauseDecision.REJECT]；外部取消则原样
     * 重抛 [CancellationException],不把协程取消伪装成用户拒绝。
     */
    suspend fun awaitPauseDecision(request: PauseRequest, policy: PausePolicy): PauseResponse {
        val deferred = CompletableDeferred<PauseResponse>()
        val parentRequestId = resolveParentRequestId(request)
        val cancelledBeforeRegistration = synchronized(stateLock) {
            if (isCancellationRequested(request.requestId, parentRequestId)) {
                true
            } else {
                val previous = pendingResponses.putIfAbsent(request.requestId, deferred)
                require(previous == null) { "暂停请求已在等待中: ${request.requestId}" }
                if (parentRequestId != null) {
                    activeRequestIdsByParent.computeIfAbsent(parentRequestId) {
                        ConcurrentHashMap.newKeySet<String>()
                    }.add(request.requestId)
                    parentRequestIdByRequest[request.requestId] = parentRequestId
                }
                _activePauses.value = _activePauses.value + (request.requestId to request)
                false
            }
        }
        if (cancelledBeforeRegistration) {
            return cancelledResponse("委派已取消")
        }

        return try {
            val timeoutMs = policy.autoTimeoutSec.coerceAtLeast(0).toLong() * MILLIS_PER_SECOND
            withTimeoutOrNull(timeoutMs) {
                deferred.await()
            } ?: PauseResponse(
                decision = PauseDecision.REJECT,
                outcome = PauseOutcome.TIMED_OUT,
                reason = "暂停等待超时,自动拒绝",
            )
        } catch (e: CancellationException) {
            throw e
        } finally {
            removePendingRequest(request.requestId, deferred)
        }
    }

    /** 用户提交决策。 */
    fun submitDecision(requestId: String, response: PauseResponse) {
        synchronized(stateLock) {
            pendingResponses[requestId]?.complete(response)
        }
    }

    /**
     * 取消指定委派(用户主动取消)。
     *
     * [requestId] 可以是暂停 requestId,也可以是父委派 requestId。父委派取消时,
     * 该父委派下所有当前等待的暂停 Deferred 都会收到 CANCEL。
     */
    fun cancelDelegation(requestId: String) {
        synchronized(stateLock) {
            cancelledRequests.add(requestId)
            val requestIds = linkedSetOf(requestId) + collectActiveRequestIds(requestId)
            requestIds.forEach { activeRequestId ->
                pendingResponses[activeRequestId]?.complete(cancelledResponse("委派已取消"))
            }
        }
    }

    /** 检查委派是否已被取消。 */
    fun isCancelled(requestId: String): Boolean = synchronized(stateLock) {
        isCancellationRequested(requestId, parentRequestIdByRequest[requestId])
    }

    /** 清理已结束的取消标记。 */
    fun clearCancellation(requestId: String) {
        synchronized(stateLock) {
            cancelledRequests.remove(requestId)
        }
    }

    /** 清空所有状态(切换会话时调用)。 */
    fun clearAll() {
        synchronized(stateLock) {
            // 先唤醒所有等待者,再清理索引;否则清理 pendingResponses 会让协程永久挂起。
            pendingResponses.values.forEach { deferred ->
                deferred.complete(cancelledResponse("暂停管理器已清理"))
            }
            _activePauses.value = emptyMap()
            pendingResponses.clear()
            activeRequestIdsByParent.clear()
            parentRequestIdByRequest.clear()
            cancelledRequests.clear()
        }
    }

    private fun removePendingRequest(
        requestId: String,
        deferred: CompletableDeferred<PauseResponse>,
    ) {
        synchronized(stateLock) {
            pendingResponses.remove(requestId, deferred)
            _activePauses.value = _activePauses.value - requestId
            val parentRequestId = parentRequestIdByRequest.remove(requestId)
            if (parentRequestId != null) {
                activeRequestIdsByParent[parentRequestId]?.let { requestIds ->
                    requestIds.remove(requestId)
                    if (requestIds.isEmpty()) {
                        activeRequestIdsByParent.remove(parentRequestId, requestIds)
                    }
                }
            }
        }
    }

    /** 解析显式父 id;旧调用方未传时 taskId 即父委派 id。 */
    private fun resolveParentRequestId(request: PauseRequest): String? {
        request.parentRequestId?.takeIf { it.isNotBlank() }?.let { return it }
        val separator = request.taskId.lastIndexOf('/')
        return if (separator > 0 && separator < request.taskId.lastIndex) {
            request.taskId.substring(0, separator)
        } else {
            request.taskId.takeIf { it.isNotBlank() }
        }
    }

    /** 判断自身或其父委派链是否已被取消。 */
    private fun isCancellationRequested(
        requestId: String,
        parentRequestId: String?,
    ): Boolean {
        if (cancelledRequests.contains(requestId)) return true
        var currentParent = parentRequestId
        while (currentParent != null) {
            if (cancelledRequests.contains(currentParent)) return true
            currentParent = parentRequestIdByRequest[currentParent]
        }
        return cancelledRequests.any { cancelledId ->
            requestId.startsWith("$cancelledId/")
        }
    }

    /** 收集父委派及所有后代的活跃暂停 requestId。 */
    private fun collectActiveRequestIds(parentRequestId: String): Set<String> {
        val result = linkedSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(parentRequestId)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            activeRequestIdsByParent[current].orEmpty().forEach { childRequestId ->
                if (result.add(childRequestId)) queue.addLast(childRequestId)
            }
        }
        return result
    }

    private fun cancelledResponse(reason: String): PauseResponse = PauseResponse(
        decision = PauseDecision.CANCEL,
        outcome = PauseOutcome.CANCELLED,
        reason = reason,
    )

    private companion object {
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
