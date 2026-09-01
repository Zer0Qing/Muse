package io.zer0.muse.ui.chat

import android.content.Context
import io.zer0.ai.ProviderRegistry
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.image.ImageGenParams
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.web.WebSearchMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.x: 从 ChatViewModel 抽离的聊天级设置 Controller。
 *
 * 职责:侧栏开合、绘图模式、绘图参数、上游模型拉取、工具模型、一次性 toast 清空。
 * (provider/模型"切换"方法 setActiveProvider/setSelectedModel 依赖会话级 override map,
 * 后续随 override map 一并迁入。)
 */
@Suppress("TooManyFunctions")
class ChatSettingsController(
    private val accessor: ChatStateAccessor,
    private val settings: SettingsRepository,
    private val appContext: Context,
    private val selectionStore: SessionModelSelectionStore,
) {

    /** 切换侧栏开合。 */
    fun toggleDrawer(open: Boolean) {
        accessor.update { it.copy(isDrawerOpen = open) }
    }

    /** P5-G: 切换绘图模式。开启后输入栏 placeholder 变化,send 走 ImageService。 */
    fun toggleDrawMode() {
        if (accessor.snapshot.isStreaming) return
        val newMode = !accessor.snapshot.isDrawMode
        accessor.update {
            it.copy(
                isDrawMode = newMode,
                // 退出绘图模式时清空临时参考图
                imageGenParams = if (!newMode) it.imageGenParams.copy(referenceImageUri = null)
                else it.imageGenParams,
            )
        }
    }

    /** v0.34: 更新当前绘图参数(可临时覆盖设置默认值)。 */
    fun updateImageGenParams(params: ImageGenParams) {
        accessor.update { it.copy(imageGenParams = params) }
    }

    /** 切换激活 Provider;写入会话级 override、清空旧模型、必要时触发 /models 拉取。 */
    fun setActiveProvider(providerId: String) {
        if (accessor.snapshot.isStreaming) return
        val st = accessor.snapshot
        val sessionId = (if (st.isAgentMode) st.agentSessionId else st.currentSessionId) ?: return
        accessor.coroutineScope.launch {
            val defaultModelId = accessor.snapshot.providers.firstOrNull { it.id == providerId }
                ?.models?.firstOrNull()?.id
            selectionStore.setProviderOverride(sessionId, providerId)
            selectionStore.setModelOverride(sessionId, if (defaultModelId.isNullOrBlank()) null else defaultModelId)
            settings.saveSessionProviderOverride(sessionId, providerId)
            settings.saveSessionModelOverride(sessionId, defaultModelId)
            accessor.update { it.copy(activeProviderId = providerId, selectedModelId = defaultModelId) }
            val provider = accessor.snapshot.providers.firstOrNull { it.id == providerId }
            if (provider != null && provider.models.isEmpty() && provider.apiKey.isNotBlank()) {
                refreshModels(providerId)
            }
        }
    }

    /** 选择当前 Provider 的具体模型;传 null 清空,回退 Provider 首模型。 */
    fun setSelectedModel(modelId: String?) {
        if (accessor.snapshot.isStreaming) return
        val st = accessor.snapshot
        val sessionId = (if (st.isAgentMode) st.agentSessionId else st.currentSessionId) ?: return
        accessor.coroutineScope.launch {
            val prevId = accessor.snapshot.selectedModelId
            val resolvedModelId = modelId ?: accessor.snapshot.providers
                .firstOrNull { it.id == accessor.snapshot.activeProviderId }
                ?.models?.firstOrNull()?.id
            val providerId = accessor.snapshot.activeProviderId
            selectionStore.setModelOverride(sessionId, if (resolvedModelId.isNullOrBlank()) null else resolvedModelId)
            selectionStore.setProviderOverride(sessionId, if (providerId.isNullOrBlank()) null else providerId)
            settings.saveSessionModelOverride(sessionId, resolvedModelId)
            settings.saveSessionProviderOverride(sessionId, providerId)
            accessor.update {
                it.copy(
                    selectedModelId = resolvedModelId,
                    toast = if (resolvedModelId != null && resolvedModelId != prevId) {
                        appContext.getString(R.string.err_chat_model_switched_toast)
                    } else {
                        it.toast
                    },
                )
            }
        }
    }

    /**
     * v1.22: 手动/自动拉取指定 Provider 的上游模型列表。
     * 拉取成功后更新 ProviderConfig.models 并持久化,失败写入 fetchModelsError。
     */
    fun refreshModels(providerId: String) {
        if (accessor.snapshot.isFetchingModels) return
        val provider = accessor.snapshot.providers.firstOrNull { it.id == providerId } ?: return
        accessor.coroutineScope.launch {
            accessor.update { it.copy(isFetchingModels = true, fetchModelsError = null) }
            val result = resultOf {
                withContext(Dispatchers.IO) {
                    ProviderRegistry.create(provider).listModels(provider)
                }
            }
            accessor.update { it.copy(isFetchingModels = false) }
            result.onSuccess { models ->
                if (models.isEmpty()) {
                    accessor.update {
                        it.copy(fetchModelsError = appContext.getString(R.string.err_chat_fetch_models_empty))
                    }
                } else {
                    io.zer0.ai.core.ModelListCache.put(provider, models)
                    val updated = provider.copy(models = models)
                    settings.updateProvider(updated)
                    accessor.update { it.copy(fetchModelsError = null) }
                }
            }.onError { _, t ->
                val msg = t?.message ?: appContext.getString(R.string.err_chat_fetch_models_failed)
                accessor.update {
                    it.copy(
                        fetchModelsError = resolveFetchModelsError(msg)
                    )
                }
            }
        }
    }

    /** v1.60-A: 设置工具模型(null 清除,沿用主对话模型)。 */
    fun setToolModel(modelId: String?) {
        accessor.coroutineScope.launch {
            settings.saveToolModel(modelId)
        }
    }

    /** v0.39: 切换深度思考开关(仅运行时状态,不持久化,下次进入会话恢复助手默认)。 */
    fun toggleDeepThinking() {
        accessor.update { it.copy(deepThinkingEnabled = !it.deepThinkingEnabled) }
    }

    /** v1.0.47 P5-6: 循环深度思考级别(LOW → MEDIUM → HIGH → XHIGH → LOW)。 */
    fun cycleDeepThinkingLevel() {
        accessor.update { state ->
            val next = when (state.deepThinkingLevel) {
                ReasoningLevel.LOW -> ReasoningLevel.MEDIUM
                ReasoningLevel.MEDIUM -> ReasoningLevel.HIGH
                ReasoningLevel.HIGH -> ReasoningLevel.XHIGH
                ReasoningLevel.XHIGH -> ReasoningLevel.LOW
                else -> ReasoningLevel.HIGH // 兜底:OFF/AUTO 回到默认 HIGH
            }
            state.copy(deepThinkingLevel = next)
        }
    }

    /** v0.51: 清空一次性 toast(Toast 弹出后由 UI 立即调用,避免重组时重复弹)。 */
    fun clearToast() {
        accessor.update { it.copy(toast = null) }
    }

    /** v1.117: 切换 Web 搜索开关(改 UI + 持久化 settings)。 */
    fun toggleWebSearch() {
        val cfg = accessor.snapshot.webSearchConfig
        val enabling = !cfg.enabled || cfg.mode == WebSearchMode.OFF
        val newCfg = cfg.copy(
            enabled = enabling,
            mode = if (enabling) {
                if (cfg.mode == WebSearchMode.OFF) WebSearchMode.AUTO else cfg.mode
            } else {
                WebSearchMode.OFF
            },
        )
        accessor.update {
            it.copy(webSearchEnabled = newCfg.enabled, webSearchConfig = newCfg)
        }
        accessor.coroutineScope.launch {
            resultOf { settings.saveWebSearchConfig(newCfg) }
        }
    }

    /** 把 /models 拉取的失败消息映射为本地化错误文案。 */
    private fun resolveFetchModelsError(msg: String): String = when {
        msg.contains("401") || msg.contains("403") -> appContext.getString(R.string.err_chat_auth_invalid)
        msg.contains("Unable to resolve") || msg.contains("UnknownHost") ->
            appContext.getString(R.string.err_chat_fetch_models_no_server)
        msg.contains("timeout", ignoreCase = true) -> appContext.getString(R.string.err_chat_fetch_models_timeout)
        msg.contains("404") -> appContext.getString(R.string.err_chat_fetch_models_not_supported)
        else -> appContext.getString(R.string.err_chat_fetch_models_failed_msg, msg.take(120))
    }
}
