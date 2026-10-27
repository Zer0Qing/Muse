package io.zer0.muse.proactive

import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.common.Logger
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.routing.UtilityModelBinding
import io.zer0.muse.data.routing.UtilityModelRouter
import io.zer0.muse.data.routing.UtilityTier
import kotlinx.coroutines.flow.first

/**
 * 主动消息这一轮用哪个模型。
 *
 * 默认跟这个助手平时聊天用的模型一致 —— 主动消息是它自己在说话，
 * 换一个模型等于换一个人。人格断裂比多发几条消息糟糕得多，
 * 省下的那点 token 钱在这里完全不划算。
 *
 * 回退顺序：助手指定的模型 → 全局选中的模型 → 工具档模型。
 * 最后一档只为了保证「还能开口」，语气差一点也比发不出去强。
 */
internal object ProactiveModelPicker {

    private const val TAG = "ProactiveModel"

    suspend fun pick(
        settings: SettingsRepository,
        assistant: AssistantEntity,
    ): Pair<ProviderConfig, Model>? {
        val router = UtilityModelRouter(settings)
        val providerId = assistant.providerId?.takeIf { it.isNotBlank() }
            ?: readOrNull { settings.activeProviderIdFlow.first() }
        val modelId = assistant.modelId?.takeIf { it.isNotBlank() }
            ?: readOrNull { settings.selectedModelIdFlow.first() }

        if (modelId.isNullOrBlank()) {
            Logger.w(TAG, "助手与全局都没有绑定模型，退回工具档")
            return utilityModel(router)
        }
        val resolved = runCatching {
            router.resolveBinding(UtilityModelBinding(providerId = providerId.orEmpty(), modelId = modelId))
        }.onFailure { e -> Logger.w(TAG, "按聊天模型解析失败，退回工具档: ${e.message}") }
            .getOrNull()
        return resolved ?: utilityModel(router)
    }

    private suspend fun utilityModel(router: UtilityModelRouter): Pair<ProviderConfig, Model>? =
        runCatching { router.resolve(UtilityTier.LARGE) }.getOrNull()

    private suspend fun <T> readOrNull(read: suspend () -> T): T? = runCatching { read() }.getOrNull()
}
