package io.zer0.muse.data

import io.zer0.ai.core.ProviderConfig

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
    val imageGenConfig: ImageGenConfig,
    val videoGenConfig: VideoGenConfig,
    val taskRoutingConfig: SettingsRepository.TaskRoutingConfig,
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
    imageGenConfig: ImageGenConfig = ImageGenConfig(),
    videoGenConfig: VideoGenConfig = VideoGenConfig(),
    taskRoutingConfig: SettingsRepository.TaskRoutingConfig = SettingsRepository.TaskRoutingConfig(),
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

    return ProviderReferenceCleanup(
        providers = remaining,
        activeProviderId = active,
        selectedModelId = cleanModel(selectedModelId),
        toolModelId = cleanModel(toolModelId),
        subagentModelId = cleanModel(subagentModelId),
        compressModelId = cleanModel(compressModelId),
        visionModelId = visionModel,
        visionProviderId = visionProvider,
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
        sessionModelOverrides = cleanedSessionModels,
        sessionProviderOverrides = cleanedSessionProviders,
    )
}
