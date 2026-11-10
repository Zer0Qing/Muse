package io.zer0.muse.tools

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 不在聊天循环里、却需要调用工具的**唯一审批闸门**。
 *
 * 存在的理由是防漂移：子代理与插件都会"从别处发起一次工具调用"，
 * 而审批判定（风险等级、会话权限模式、单工具策略、参数级硬拒）只该有一处定义。
 * 两处各写一份的必然结局是其中一份更宽松 —— 那宽松的份就是绕审批的洞。
 *
 * 两条路径，方向都是**只紧不松**：
 *
 *  1. **有审批通道**（前台会话已把 [ToolApprovalRouter.delegate] 注册好）：
 *     整次调用交给主会话审批链，模式/策略/全量参数都由它取，
 *     发起方的放行范围因此不会超过宿主会话。
 *  2. **没有审批通道**（后台任务、无界面）：本地用同一个解析器判定，且兜底更保守 ——
 *     风险取不到按 `HIGH`、只认用户显式配置的单工具策略（未配置 ≠ 已放行）、
 *     模式取全局默认（读不到按 `STRICT`）；并且**只有 Auto/Approved 才放行**：
 *     `Pending` 在没有卡片可弹时等同于拒绝，绝不静默放行。
 */
class ToolCallGate(
    private val approvalRouter: ToolApprovalRouter,
    private val toolRegistry: ToolRegistry,
    private val toolConfigStore: ToolConfigStore,
    private val settings: SettingsRepository? = null,
) {

    /** 闸门结论。[reason] 是给模型看的：要能让它知道该改道去做什么。 */
    data class Outcome(val allowed: Boolean, val reason: String = "") {
        companion object {
            val ALLOWED = Outcome(allowed = true)
        }
    }

    /**
     * @param toolName 被调用的工具名
     * @param toolCallId 本次调用的 id（审批卡按它回填）
     * @param argumentsJson 全量参数 JSON；判定用全量，审批卡只展示预览
     * @param subject 发起方称呼，出现在拒绝文案里（"子 agent" / "插件"）
     */
    suspend fun gate(toolName: String, toolCallId: String, argumentsJson: String, subject: String): Outcome {
        val args = parseToolArgs(toolName, argumentsJson)
        return if (approvalRouter.delegate != null) {
            viaBridge(toolName, toolCallId, argumentsJson, args, subject)
        } else {
            withoutBridge(toolName, args, subject)
        }
    }

    /** 有通道：交给主会话审批链。审批超时视为拒绝，让发起方改道而不是干等。 */
    private suspend fun viaBridge(
        toolName: String,
        toolCallId: String,
        argumentsJson: String,
        args: Map<String, Any?>,
        subject: String,
    ): Outcome {
        val approval = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) {
            approvalRouter.request(
                toolName = toolName,
                toolCallId = toolCallId,
                argsPreview = argumentsJson.take(ARGS_PREVIEW_CHARS),
                args = args,
            )
        } ?: ToolApprovalState.Denied(
            "审批等待超时(${APPROVAL_TIMEOUT_MS / 1000}s)",
            source = DenySource.TIMEOUT,
        )
        return when (approval) {
            is ToolApprovalState.Auto, is ToolApprovalState.Approved -> Outcome.ALLOWED
            is ToolApprovalState.Denied ->
                denied(toolName, "未获批准(${approval.reason.ifBlank { "已被拒绝" }}),$subject 无权调用")
            is ToolApprovalState.Pending, is ToolApprovalState.Answered ->
                denied(toolName, "审批状态未确定,$subject 已放弃调用")
        }
    }

    /** 无通道：本地判定，放行条件比主路径更窄（Pending 弹不出卡一律拒绝）。 */
    private suspend fun withoutBridge(toolName: String, args: Map<String, Any?>, subject: String): Outcome {
        val resolved = try {
            ToolPermissionResolver.resolve(
                toolName = toolName,
                risk = gateRiskLevel(toolName),
                mode = localPermissionMode(),
                perToolPolicy = explicitPolicyOf(toolName),
                args = args,
            )
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // 判定本身出错时按最保守方向处理：拒绝，不放行。
            Logger.w(TAG, "工具审批判定异常,按拒绝处理: tool=$toolName", e)
            ToolApprovalState.Denied(
                "审批判定异常(${e.message ?: e.javaClass.simpleName})",
                source = DenySource.SYSTEM,
            )
        }
        return when (resolved) {
            is ToolApprovalState.Auto, is ToolApprovalState.Approved -> Outcome.ALLOWED
            is ToolApprovalState.Denied ->
                denied(toolName, "未获批准(${resolved.reason.ifBlank { "当前权限策略不允许" }}),$subject 无权调用")
            is ToolApprovalState.Pending, is ToolApprovalState.Answered -> denied(
                toolName,
                "需要用户审批,但$subject 没有审批通道,$subject 已放弃调用" +
                    "(在主会话里调用该工具会弹出审批卡)",
            )
        }
    }

    private fun denied(toolName: String, reason: String): Outcome = Outcome(allowed = false, reason = "工具 '$toolName' $reason")

    private fun gateRiskLevel(toolName: String): ToolRiskLevel = try {
        toolRegistry.getToolRiskLevel(toolName) ?: ToolRiskLevel.HIGH
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Logger.w(TAG, "风险等级读取异常,按 HIGH 处理: tool=$toolName", e)
        ToolRiskLevel.HIGH
    }

    /** 只取用户显式配置的单工具策略；未配置必须是 null（**未配置 ≠ 用户放行**）。 */
    private suspend fun explicitPolicyOf(toolName: String): ToolApprovalPolicy? = try {
        toolConfigStore.getConfiguredPolicy(toolName)
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Logger.w(TAG, "单工具策略读取异常,按未配置处理: tool=$toolName", e)
        null
    }

    /** 本地判定用的会话模式：取全局默认；读不到按 STRICT（最保守）。 */
    private suspend fun localPermissionMode(): SessionPermissionMode = try {
        settings?.defaultSessionPermissionModeFlow?.first() ?: SessionPermissionMode.STRICT
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Logger.w(TAG, "会话权限模式读取失败,按 STRICT 处理", e)
        SessionPermissionMode.STRICT
    }

    /** 全量参数键值表（值归一为字符串，与参数级硬拒的读法一致）。 */
    private fun parseToolArgs(toolName: String, arguments: String): Map<String, Any?> = runCatching {
        (AppJson.parseToJsonElement(arguments) as? JsonObject)?.mapValues { (_, value) ->
            (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
        } ?: emptyMap()
    }.getOrElse {
        // 解析不了就给空参数：参数级硬拒看不到参数，靠风险等级兜底（未知即 HIGH）。
        Logger.w(TAG, "工具参数解析失败,按空参数判定: tool=$toolName")
        emptyMap()
    }

    companion object {
        private const val TAG = "ToolCallGate"

        /** 审批等待的独立超时：用户审卡不该占满整次执行的超时窗。 */
        const val APPROVAL_TIMEOUT_MS = 30_000L

        /** 审批卡参数预览长度；判定一律用全量参数，预览只给人看。 */
        const val ARGS_PREVIEW_CHARS = 200
    }
}
