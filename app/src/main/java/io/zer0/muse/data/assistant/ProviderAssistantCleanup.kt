package io.zer0.muse.data.assistant

/**
 * 删除 Provider 后清理 Assistant 的显式模型绑定。
 *
 * Assistant 的 providerId/modelId 是成对的事实引用；Provider 被删除后保留其中
 * 任一字段都会让后续解析命中不存在的配置。这里清掉整对绑定，允许调用方回退
 * 到全局模型或重新选择可用 Provider。
 */
internal fun clearProviderBindings(
    assistants: List<AssistantEntity>,
    deletedProviderId: String,
): List<AssistantEntity> = assistants.map { assistant ->
    if (assistant.providerId == deletedProviderId) {
        assistant.copy(providerId = null, modelId = null)
    } else {
        assistant
    }
}
