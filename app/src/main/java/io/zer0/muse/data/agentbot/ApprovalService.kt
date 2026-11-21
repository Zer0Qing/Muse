package io.zer0.muse.data.agentbot

import io.zer0.common.Logger

/**
 * 审批服务 —— 把"要不要允许这一步"变成一次可核验、只结算一次的决定。
 *
 * ## 三类错误它专门防着
 *
 * 1. **重复执行**：用户双击"批准"，工具跑两遍。用 CAS 只结算一次。
 * 2. **过期当拒绝**：用户没看见不等于用户拒绝。`EXPIRED` 与 `DENIED` 分开，
 *    主 Bot 汇报时不能说"你拒绝了"——那是假的。
 * 3. **参数漂移后仍执行**：审批卡展示的是 A，实际执行的是 B。
 *    参数哈希与资源版本都绑进审批，任何一项变了旧批准立即失效。
 *
 * ## 期限从哪算
 *
 * 从 `readyAt`（卡片可被读取的时刻）算，不是从 `createdAt`。
 * 否则主 Bot 一卡顿，用户的思考时间就被悄悄吃掉。
 */
class ApprovalService(
    private val repository: AgentBotRepository,
    private val now: () -> Long = { System.currentTimeMillis() },
    /**
     * 审批结算后把任务拉回队列。
     *
     * 缺了这一步，任务会永远停在 WAITING_APPROVAL —— 用户点了"允许"，
     * 卡片消失，但什么都没发生。那是最容易让人失去信任的失败方式。
     *
     * 为 null 时跳过（纯 JVM 测试）。
     */
    private val onSettled: (suspend (taskId: String, approvalId: String, approved: Boolean) -> Unit)? = null,
) {

    /**
     * 结算结果。
     *
     * [alreadySettled] 与 [rejected] 必须分开：前者是"你已经决定过了"，
     * 后者是"这次不算数"。合并成一个布尔会让界面无法给出正确提示。
     */
    sealed interface Outcome {
        /** 本次结算成功，附带最终状态。 */
        data class Settled(val status: String) : Outcome

        /** 已被结算过（重复点击 / 并发）。返回既有状态供界面回显。 */
        data class AlreadySettled(val status: String) : Outcome

        /** 参数或版本已变，旧批准失效。 */
        data class Stale(val reason: String) : Outcome

        /** 已过期，且**不视为用户授权**。 */
        data class Expired(val status: String) : Outcome
    }

    /**
     * 用户批准。
     *
     * 执行前会再次核对参数哈希与资源版本 —— 卡片展示与实际执行必须同源。
     */
    suspend fun approve(
        approvalId: String,
        /** 当前实际要执行的参数哈希。与审批记录不一致时拒绝。 */
        currentArgumentsHash: String,
        /** 当前资源版本。与审批记录不一致时拒绝。 */
        currentResourceVersion: String = "",
        actor: String = ACTOR_USER,
    ): Outcome = settle(approvalId, ApprovalStatus.APPROVED, currentArgumentsHash, currentResourceVersion, actor, "")

    /** 用户拒绝。明确拒绝不可自动改道执行相同副作用。 */
    suspend fun deny(
        approvalId: String,
        reason: String = "",
        actor: String = ACTOR_USER,
    ): Outcome {
        // 拒绝不需要核对参数：拒绝的语义与参数无关。
        val current = repository.getApproval(approvalId)
            ?: return Outcome.Stale("审批不存在")
        if (ApprovalStatus.isSettled(current.status)) {
            return Outcome.AlreadySettled(current.status)
        }
        val ok = repository.settleApproval(approvalId, ApprovalStatus.DENIED, actor, reason)
        if (ok) {
            // 拒绝也要复位任务：让它从 WAITING_APPROVAL 出来，
            // 由上层决定是改道、重试还是如实告诉用户"这一步没做"。
            runCatching {
                onSettled?.invoke(current.taskId, approvalId, false)
            }.onFailure {
                Logger.w("ApprovalService", "审批已拒绝但任务复位失败: ${current.taskId}", it)
            }
        }
        return if (ok) Outcome.Settled(ApprovalStatus.DENIED) else Outcome.AlreadySettled(current.status)
    }

    /**
     * 统一结算路径。
     *
     * 顺序固定为「查 → 核 → 结算」，核验在 CAS 之前：
     * 先 CAS 再核验会让一个不该生效的批准把状态占掉。
     */
    private suspend fun settle(
        approvalId: String,
        target: String,
        currentArgumentsHash: String,
        currentResourceVersion: String,
        actor: String,
        reason: String,
    ): Outcome {
        val approval = repository.getApproval(approvalId)
            ?: return Outcome.Stale("审批不存在")

        if (ApprovalStatus.isSettled(approval.status)) {
            return Outcome.AlreadySettled(approval.status)
        }

        // 过期不视为授权：即便用户此刻点了批准，卡片上的期限已经过了。
        if (approval.expiresAt > 0 && approval.expiresAt < now()) {
            repository.settleApproval(approvalId, ApprovalStatus.EXPIRED, ACTOR_SYSTEM, "expired_before_approval")
            // 过期不等于用户拒绝，但任务同样要复位 —— 否则它永远停在 WAITING_APPROVAL。
            settleAndNotify(approval.taskId, approvalId, approved = false)
            return Outcome.Expired(ApprovalStatus.EXPIRED)
        }

        // 参数漂移：卡片上写的是 A，现在要执行的是 B。
        if (approval.argumentsHash.isNotBlank() && approval.argumentsHash != currentArgumentsHash) {
            repository.settleApproval(
                approvalId,
                ApprovalStatus.INVALIDATED,
                ACTOR_SYSTEM,
                "arguments_changed",
            )
            settleAndNotify(approval.taskId, approvalId, approved = false)
            return Outcome.Stale("参数已变更，旧的批准失效")
        }
        if (approval.resourceVersion.isNotBlank() &&
            currentResourceVersion.isNotBlank() &&
            approval.resourceVersion != currentResourceVersion
        ) {
            repository.settleApproval(
                approvalId,
                ApprovalStatus.INVALIDATED,
                ACTOR_SYSTEM,
                "resource_changed",
            )
            settleAndNotify(approval.taskId, approvalId, approved = false)
            return Outcome.Stale("资源已变更，旧的批准失效")
        }

        val ok = repository.settleApproval(approvalId, target, actor, reason)
        if (ok) {
            // 结算成功就立刻把任务拉回队列。
            //
            // 注意这里是 fire-and-forget：即便回调失败，审批本身已经结算成功，
            // 不能因为"拉不回任务"就告诉用户"审批失败"。
            runCatching {
                onSettled?.invoke(approval.taskId, approvalId, target == ApprovalStatus.APPROVED)
            }.onFailure {
                Logger.w("ApprovalService", "审批已结算但任务复位失败: ${approval.taskId}", it)
            }
        }
        return if (ok) {
            Outcome.Settled(target)
        } else {
            // CAS 失败说明并发下别人先结算了。
            val latest = repository.getApproval(approvalId)
            Outcome.AlreadySettled(latest?.status ?: ApprovalStatus.CANCELLED)
        }
    }

    /** 取消（任务被用户停掉时，挂着的审批一并取消）。 */
    suspend fun cancel(approvalId: String, reason: String = "task_cancelled"): Outcome {
        val current = repository.getApproval(approvalId)
            ?: return Outcome.Stale("审批不存在")
        if (ApprovalStatus.isSettled(current.status)) return Outcome.AlreadySettled(current.status)
        val ok = repository.settleApproval(approvalId, ApprovalStatus.CANCELLED, ACTOR_SYSTEM, reason)
        return if (ok) Outcome.Settled(ApprovalStatus.CANCELLED) else Outcome.AlreadySettled(current.status)
    }

    /**
     * 扫一遍过期。
     *
     * 由定时对账调用，不依赖界面存活 —— 用户可能已经退后台。
     */
    suspend fun sweepExpired(): Int {
        val count = repository.expireApprovals()
        if (count > 0) Logger.i("ApprovalService", "过期审批 ${count} 条")
        return count
    }

    /**
     * 主 Bot 能不能替用户批准？不能。
     *
     * 这个方法存在的意义是让调用方**显式**表达"我知道这里有个边界"，
     * 而不是在某处写一句 `settleApproval(APPROVED)` 就绕过去了。
     */
    fun canBotSelfApprove(): Boolean = false

    /**
     * 结算已发生但不会进入正常路径时的统一收尾。
     *
     * ## 为什么早退分支也要回调
     *
     * 过期、参数漂移、资源漂移 —— 这三种情况下审批卡已经结算（用户看不到它了），
     * 但任务还停在 WAITING_APPROVAL。不回调的话它永远出不来：
     *
     *   - `expireApprovals` 只扫 PENDING，卡已结算，扫不到；
     *   - 状态机里 WAITING_APPROVAL 没有别的出口。
     *
     * 用户看到"参数已变更"，然后这个任务只能取消。
     */
    private suspend fun settleAndNotify(taskId: String, approvalId: String, approved: Boolean) {
        runCatching { onSettled?.invoke(taskId, approvalId, approved) }.onFailure {
            Logger.w("ApprovalService", "审批已结算但任务复位失败: $taskId", it)
        }
    }
    companion object {
        const val ACTOR_USER = "user"
        const val ACTOR_SYSTEM = "system"
    }
}
