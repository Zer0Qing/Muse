package io.zer0.muse.data.routing

import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

/**
 * 辅助任务档位 — 三档模型(小工具 / 大工具 / 视觉)。
 *
 * 与 [SettingsRepository.TaskRoutingConfig](按对话内容为主模型分流)是两个维度:
 * 本枚举只管后台辅助任务(标题、压缩、记忆、路由判断等)的模型选择。
 */
enum class UtilityTier {
    /** 小工具:标题生成、轻量分类、意图路由、封面 prompt、渠道摘要等短任务。 */
    SMALL,

    /** 大工具:上下文压缩、记忆提取、活动摘要、任务拆解、子代理等需要理解力的场景。 */
    LARGE,

    /** 视觉:图片理解 / OCR 辅助(沿用现有视觉模型配置)。 */
    VISION,
}

/**
 * 辅助模型绑定 — 同时携带 Provider。
 *
 * v1.0.62 的教训:压缩模型曾只存 model id,跨 Provider 按 id 匹配会命中无关渠道的
 * 同名小模型,导致质量忽高忽低。绑定带上 providerId 后,路由始终精确命中用户选择。
 */
@Serializable
data class UtilityModelBinding(
    val providerId: String,
    val modelId: String,
)

/**
 * 辅助模型路由 — 统一收敛所有后台辅助任务的模型选择。
 *
 * 级联规则:
 *  - [UtilityTier.LARGE] 留空 → 复用 [UtilityTier.SMALL]
 *  - [UtilityTier.SMALL] 留空 → 返回 null,调用方沿用主对话模型
 *  - [UtilityTier.VISION] 沿用现有视觉模型配置,未启用/未选择 → null
 *
 * 解析失败(Provider 已删 / 模型已删)同样返回 null,调用方回退主模型,不阻断任务。
 */
class UtilityModelRouter(private val settings: SettingsRepository) {

    /** 解析档位 → (providerConfig, model);null 表示沿用主对话模型。 */
    suspend fun resolve(tier: UtilityTier): Pair<ProviderConfig, Model>? {
        val binding = when (tier) {
            UtilityTier.SMALL -> settings.utilityModelBindingFlow.first()
            UtilityTier.LARGE -> settings.utilityLargeModelBindingFlow.first()
                ?: settings.utilityModelBindingFlow.first()
            UtilityTier.VISION -> {
                val providerId = settings.visionProviderIdFlow.first()
                val modelId = settings.visionModelIdFlow.first()
                if (providerId.isNullOrBlank() || modelId.isNullOrBlank()) {
                    null
                } else {
                    UtilityModelBinding(providerId = providerId, modelId = modelId)
                }
            }
        } ?: return null
        return resolveBinding(binding)
    }

    /** 直接解析一个绑定(供设置页"检测"按钮等场景复用)。 */
    suspend fun resolveBinding(binding: UtilityModelBinding): Pair<ProviderConfig, Model>? {
        val provider = settings.getProviderById(binding.providerId) ?: return null
        val model = provider.models.firstOrNull { it.id == binding.modelId } ?: return null
        return provider to model
    }
}
