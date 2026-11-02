package io.zer0.muse.data.plugin

import android.content.Context
import io.zer0.muse.tools.script.SkillBridge
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.coroutineContext
import java.io.File

/**
 * 桥接判定的**唯一实现**：能力账本 → 宿主 API → [SkillBridge] 兜底。
 *
 * 为什么单独成类：判定逻辑原先只长在 `SkillMediaToolsImpl.bridgeHandle` 里，
 * 而声明式面板的提交（`submit` handler）也需要同一条路 —— 复制一份的后果不是重复代码，
 * 而是**两个入口的授权强度会漂移**（本会话已经把子代理/插件/主会话三处审批收成一份，
 * 桥接这条不能重蹈覆辙）。
 *
 * 两类底线与能力无关，属正确性，所以仍留在 [SkillBridge] 里：
 *  1. `fs_*` 锁定在插件自己的沙盒目录 `workspace/plugins/<pluginId>`，防路径逃逸；
 *  2. `http_*` 走 SSRF 防护（拒内网/本地地址）。
 */
class PluginBridgeRouter(
    context: Context,
    private val writeConfig: suspend (String, String, JsonElement?) -> Unit,
    /** 宿主未接入时 `tool_invoke` 一律拒绝（fail-closed），不静默降级成"当作没这个动作"。 */
    private val toolBridge: PluginToolInvokeBridge? = null,
    /** 会话读取通道；sessionId 只认宿主随调用注入的协程上下文。 */
    private val chatBridge: PluginChatBridge? = null,
    /** 模型调用通道（`model_call`）。 */
    private val modelBridge: PluginModelBridge? = null,
) {

    private val appContext = context.applicationContext

    /**
     * 处理一次桥接请求文本。返回 `NotBridge` 表示这不是桥请求，调用方按普通结果处理。
     *
     * 会话身份只认 [PluginSessionContext]：插件参数里的 `session_id` 一律不读 ——
     * 否则一个插件就能跨会话捞别人的聊天记录。
     */
    suspend fun handle(value: String, pluginId: String, manifest: PluginManifest): SkillBridge.HandleResult {
        val ledger = PluginCapabilityLedgers.shared(appContext)
        val allowedBridgeActions = ledger.allowedActions(pluginId, manifest.capabilities)
        // 宿主侧状态写入（config_set / tool_invoke / chat_read / model_call）先过这一层；
        // 不属于它的动作原样回落 SkillBridge。授权集合是同一份，不存在"走哪条通道更宽松"。
        val viaHostApi = PluginHostApi(
            writeConfig = writeConfig,
            toolBridge = toolBridge,
            chatBridge = chatBridge,
            sessionId = coroutineContext[PluginSessionContext]?.sessionId
                ?: PluginChatBridge.SESSION_PLACEHOLDER,
            modelBridge = modelBridge,
        ).handle(pluginId, manifest, value, allowedBridgeActions)
        val result = viaHostApi ?: run {
            val host = SkillBridge.Host(
                context = appContext,
                fsRoot = File(appContext.filesDir, "workspace/plugins/$pluginId"),
            )
            SkillBridge.tryHandle(
                valueJson = value,
                allowedActions = allowedBridgeActions,
                host = host,
                // 私网访问是**额外**授权：network 只管能不能联网，network.local 才管能不能连内网
                allowLocalNetwork = ledger.isApproved(pluginId, PluginCapabilityGateway.NETWORK_LOCAL),
            )
        }
        // 调试台：记下最近发生了什么（只保留最近 N 条）
        recordCall(pluginId, value, result)
        return result
    }

    /** 把这次调用的结果写进调试日志；`NotBridge` 不算桥接调用，不记。 */
    private fun recordCall(pluginId: String, requestJson: String, result: SkillBridge.HandleResult) {
        val action = PluginBridgeCallLog.actionOf(requestJson)
        val now = System.currentTimeMillis()
        when (result) {
            is SkillBridge.HandleResult.Output ->
                PluginBridgeCallLog.record(pluginId, action, allowed = true, detail = null, atMillis = now)
            is SkillBridge.HandleResult.Failure ->
                PluginBridgeCallLog.record(pluginId, action, allowed = false, detail = result.message, atMillis = now)
            SkillBridge.HandleResult.NotBridge -> Unit
        }
    }
}
