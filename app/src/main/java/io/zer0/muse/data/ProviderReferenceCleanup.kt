package io.zer0.muse.data

import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.data.routing.UtilityModelBinding
import io.zer0.muse.rag.RagConfig

/** Provider 删除时需要同步清理的全局和会话级引用。 */
internal data class ProviderReferenceCleanup(
    val providers: List<ProviderConfig>,
    val activeProviderId: String?,
    val selectedModelId: String?,
    val toolModelId: String?,
    val subagentModelId: String?,
    val compressModelId: String?,
    val visionModelId: String?,
    val visionProviderId: String?,
    val utilityModelBinding: UtilityModelBinding?,
    val utilityLargeModelBinding: UtilityModelBinding?,
    val imageGenConfig: ImageGenConfig,
    val videoGenConfig: VideoGenConfig,
    val taskRoutingConfig: SettingsRepository.TaskRoutingConfig,
    val ragConfig: RagConfig,
    val sessionModelOverrides: Map<String, String>,
    val sessionProviderOverrides: Map<String, String>,
)

/**
 * 计算删除 Provider 后的引用状态。
 *
 * 只清理确定属于被删 Provider 的引用；当同名模型仍存在于其他 Provider 时，
 * 保留模型 ID，避免把一个本来仍可用的全局选择误删。会话 Provider 覆盖被删时，
 * 对应的会话模型覆盖也一并移除，防止形成半套覆盖配置。
 */
@Suppress("LongParameterList")
internal fun cleanupProviderReferences(
    providers: List<ProviderConfig>,
    deletedProviderId: String,
    activeProviderId: String?,
    selectedModelId: String?,
    toolModelId: String?,
    subagentModelId: String?,
    compressModelId: String?,
    visionModelId: String?,
    visionProviderId: String?,
    utilityModelBinding: UtilityModelBinding? = null,
    utilityLargeModelBinding: UtilityModelBinding? = null,
    imageGenConfig: ImageGenConfig = ImageGenConfig(),
    videoGenConfig: VideoGenConfig = VideoGenConfig(),
    taskRoutingConfig: SettingsRepository.TaskRoutingConfig = SettingsRepository.TaskRoutingConfig(),
    ragConfig: RagConfig = RagConfig(),
    sessionModelOverrides: Map<String, String>,
    sessionProviderOverrides: Map<String, String>,
): ProviderReferenceCleanup {
    val deleted = providers.firstOrNull { it.id == deletedProviderId }
    val remaining = providers.filterNot { it.id == deletedProviderId }
    val deletedModelIds = deleted?.models?.map { it.id }?.toSet().orEmpty()
    val remainingModelIds = remaining.flatMap { provider -> provider.models.map { it.id } }.toSet()

    fun cleanModel(modelId: String?): String? =
        if (modelId != null && modelId in deletedModelIds && modelId !in remainingModelIds) null else modelId

    val deletedSessions = sessionProviderOverrides
        .filterValues { it == deletedProviderId }
        .keys
    val cleanedSessionModels = sessionModelOverrides.filterKeys { it !in deletedSessions }
    val cleanedSessionProviders = sessionProviderOverrides.filterKeys { it !in deletedSessions }
    val active = if (activeProviderId == deletedProviderId) remaining.firstOrNull()?.id else activeProviderId
    val visionProvider = if (visionProviderId == deletedProviderId) null else visionProviderId
    val visionModel = if (visionProviderId == deletedProviderId) null else cleanModel(visionModelId)

    // v2.x: 辅助模型绑定带 providerId — 被删 Provider 的绑定直接置空(不做跨渠道保留:
    // 绑定的语义就是"精确命中该渠道的该模型",渠道没了即失效)。
    fun cleanBinding(binding: UtilityModelBinding?): UtilityModelBinding? =
        if (binding != null && binding.providerId == deletedProviderId) null else binding

    fun cleanProviderModel(providerId: String, modelId: String): Pair<String, String> {
        if (providerId == deletedProviderId) return "" to ""
        return providerId to cleanModel(modelId).orEmpty()
    }

    val (imageProvider, imageModel) = cleanProviderModel(imageGenConfig.providerId, imageGenConfig.modelId)
    val (videoProvider, videoModel) = cleanProviderModel(videoGenConfig.providerId, videoGenConfig.modelId)
    val (chatProvider, chatModel) = cleanProviderModel(
        taskRoutingConfig.chatProviderId.orEmpty(),
        taskRoutingConfig.chatModelId.orEmpty(),
    )
    val (reasoningProvider, reasoningModel) = cleanProviderModel(
        taskRoutingConfig.reasoningProviderId.orEmpty(),
        taskRoutingConfig.reasoningModelId.orEmpty(),
    )
    val (codeProvider, codeModel) = cleanProviderModel(
        taskRoutingConfig.codeProviderId.orEmpty(),
        taskRoutingConfig.codeModelId.orEmpty(),
    )
    val (creativeProvider, creativeModel) = cleanProviderModel(
        taskRoutingConfig.creativeProviderId.orEmpty(),
        taskRoutingConfig.creativeModelId.orEmpty(),
    )
    val (analysisProvider, analysisModel) = cleanProviderModel(
        taskRoutingConfig.analysisProviderId.orEmpty(),
        taskRoutingConfig.analysisModelId.orEmpty(),
    )
    // RAG 云端 embedding:Provider 没了就清空,否则 cloudProviderId 会永久悬空
    val (ragProvider, ragModel) = cleanProviderModel(ragConfig.cloudProviderId, ragConfig.cloudModel)

    return ProviderReferenceCleanup(
        providers = remaining,
        activeProviderId = active,
        selectedModelId = cleanModel(selectedModelId),
        toolModelId = cleanModel(toolModelId),
        subagentModelId = cleanModel(subagentModelId),
        compressModelId = cleanModel(compressModelId),
        visionModelId = visionModel,
        visionProviderId = visionProvider,
        utilityModelBinding = cleanBinding(utilityModelBinding),
        utilityLargeModelBinding = cleanBinding(utilityLargeModelBinding),
        imageGenConfig = imageGenConfig.copy(providerId = imageProvider, modelId = imageModel),
        videoGenConfig = videoGenConfig.copy(providerId = videoProvider, modelId = videoModel),
        taskRoutingConfig = taskRoutingConfig.copy(
            chatProviderId = chatProvider.ifBlank { null },
            chatModelId = chatModel.ifBlank { null },
            reasoningProviderId = reasoningProvider.ifBlank { null },
            reasoningModelId = reasoningModel.ifBlank { null },
            codeProviderId = codeProvider.ifBlank { null },
            codeModelId = codeModel.ifBlank { null },
            creativeProviderId = creativeProvider.ifBlank { null },
            creativeModelId = creativeModel.ifBlank { null },
            analysisProviderId = analysisProvider.ifBlank { null },
            analysisModelId = analysisModel.ifBlank { null },
        ),
        ragConfig = ragConfig.copy(cloudProviderId = ragProvider, cloudModel = ragModel),
        sessionModelOverrides = cleanedSessionModels,
        sessionProviderOverrides = cleanedSessionProviders,
    )
}

/**
 * 会引用具体模型 ID 的配置集合 —— 删除单个模型时需要清理的部分。
 *
 * 与 [ProviderReferenceCleanup] 互补:那个管「整个 Provider 没了」,这个管「Provider 还在但某个模型没了」。
 */
internal data class ModelReferences(
    val imageGenConfig: ImageGenConfig = ImageGenConfig(),
    val videoGenConfig: VideoGenConfig = VideoGenConfig(),
    val taskRoutingConfig: SettingsRepository.TaskRoutingConfig = SettingsRepository.TaskRoutingConfig(),
    val utilityModelBinding: UtilityModelBinding? = null,
    val utilityLargeModelBinding: UtilityModelBinding? = null,
    val ragConfig: RagConfig = RagConfig(),
    val visionModelId: String? = null,
)

/**
 * 计算从某个 Provider 删除模型后的引用状态。
 *
 * 处理原则:
 *  - 模型 ID 类字段(图片/视频/任务路由/RAG cloudModel/视觉)指向被删模型时**置空**,
 *    交给各运行时自带的兜底选默认模型 —— 不自动改指同 Provider 的其他模型,
 *    避免把用户没选过的模型静默绑上去。
 *  - 辅助模型绑定带 providerId,语义是「精确命中该渠道的该模型」,命中被删模型时整体置空。
 *  - 同名模型仍存在于其他 Provider 时保留原值(中转站与官方共用模型 ID 很常见)。
 */
internal fun cleanupRemovedModelReferences(
    providerId: String,
    removedModelIds: Set<String>,
    retainedModelIds: Set<String>,
    references: ModelReferences,
): ModelReferences {
    fun clean(modelId: String?): String? =
        if (modelId != null && modelId in removedModelIds && modelId !in retainedModelIds) null else modelId

    fun cleanBinding(binding: UtilityModelBinding?): UtilityModelBinding? {
        if (binding == null || binding.providerId != providerId) return binding
        return if (clean(binding.modelId) == null) null else binding
    }

    val routing = references.taskRoutingConfig
    val rag = references.ragConfig
    return references.copy(
        imageGenConfig = references.imageGenConfig.copy(
            modelId = clean(references.imageGenConfig.modelId).orEmpty(),
        ),
        videoGenConfig = references.videoGenConfig.copy(
            modelId = clean(references.videoGenConfig.modelId).orEmpty(),
        ),
        taskRoutingConfig = routing.copy(
            chatModelId = clean(routing.chatModelId),
            reasoningModelId = clean(routing.reasoningModelId),
            codeModelId = clean(routing.codeModelId),
            creativeModelId = clean(routing.creativeModelId),
            analysisModelId = clean(routing.analysisModelId),
        ),
        utilityModelBinding = cleanBinding(references.utilityModelBinding),
        utilityLargeModelBinding = cleanBinding(references.utilityLargeModelBinding),
        ragConfig = rag.copy(cloudModel = clean(rag.cloudModel).orEmpty()),
        visionModelId = clean(references.visionModelId),
    )
}
