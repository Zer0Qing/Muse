package io.zer0.muse.data

import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.data.routing.UtilityModelBinding
import io.zer0.muse.rag.RagConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Provider 删除后全局与会话模型引用清理测试。 */
class ProviderReferenceCleanupTest {

    @Test
    fun cleanup_removesDeletedProviderReferencesAndKeepsSharedModelIds() {
        val deleted = ProviderConfig(
            id = "deleted",
            displayName = "Deleted",
            models = listOf(Model(id = "private-model", providerId = "deleted")),
        )
        val remaining = ProviderConfig(
            id = "remaining",
            displayName = "Remaining",
            models = listOf(Model(id = "shared-model", providerId = "remaining")),
        )
        val result = cleanupProviderReferences(
            providers = listOf(deleted, remaining),
            deletedProviderId = "deleted",
            activeProviderId = "deleted",
            selectedModelId = "private-model",
            toolModelId = "shared-model",
            subagentModelId = "shared-model",
            compressModelId = "private-model",
            visionModelId = "private-model",
            visionProviderId = "deleted",
            utilityModelBinding = io.zer0.muse.data.routing.UtilityModelBinding(providerId = "deleted", modelId = "private-model"),
            utilityLargeModelBinding = io.zer0.muse.data.routing.UtilityModelBinding(providerId = "remaining", modelId = "shared-model"),
            imageGenConfig = ImageGenConfig(providerId = "deleted", modelId = "private-model"),
            videoGenConfig = VideoGenConfig(providerId = "remaining", modelId = "shared-model"),
            taskRoutingConfig = SettingsRepository.TaskRoutingConfig(
                enabled = true,
                chatModelId = "private-model",
                chatProviderId = "deleted",
                codeModelId = "shared-model",
                codeProviderId = "remaining",
            ),
            ragConfig = RagConfig(cloudProviderId = "deleted", cloudModel = "private-model"),
            sessionModelOverrides = mapOf("s1" to "private-model", "s2" to "shared-model"),
            sessionProviderOverrides = mapOf("s1" to "deleted", "s2" to "remaining"),
        )

        assertEquals(listOf(remaining), result.providers)
        assertEquals("remaining", result.activeProviderId)
        assertNull(result.selectedModelId)
        assertEquals("shared-model", result.toolModelId)
        assertEquals("shared-model", result.subagentModelId)
        assertNull(result.compressModelId)
        assertNull(result.visionModelId)
        assertNull(result.visionProviderId)
        // v2.x: 被删 Provider 的辅助模型绑定置空；其他 Provider 的绑定保留
        assertNull(result.utilityModelBinding)
        assertEquals(
            io.zer0.muse.data.routing.UtilityModelBinding(providerId = "remaining", modelId = "shared-model"),
            result.utilityLargeModelBinding,
        )
        assertEquals(ImageGenConfig(), result.imageGenConfig)
        assertEquals(
            VideoGenConfig(providerId = "remaining", modelId = "shared-model"),
            result.videoGenConfig,
        )
        assertEquals(null, result.taskRoutingConfig.chatModelId)
        assertEquals(null, result.taskRoutingConfig.chatProviderId)
        assertEquals("shared-model", result.taskRoutingConfig.codeModelId)
        assertEquals("remaining", result.taskRoutingConfig.codeProviderId)
        assertEquals(mapOf("s2" to "shared-model"), result.sessionModelOverrides)
        assertEquals(mapOf("s2" to "remaining"), result.sessionProviderOverrides)
        // 删供应商时必须同时清掉 RAG 的云端 embedding 引用,否则 cloudProviderId 永久悬空
        assertEquals("", result.ragConfig.cloudProviderId)
        assertEquals("", result.ragConfig.cloudModel)
    }

    // ── 删单个模型:只清指向被删模型的引用 ──

    @Test
    fun cleanupRemovedModelReferences_clearsEveryModelReference() {
        val references = ModelReferences(
            imageGenConfig = ImageGenConfig(providerId = "p1", modelId = "removed-image"),
            videoGenConfig = VideoGenConfig(providerId = "p1", modelId = "removed-video"),
            taskRoutingConfig = SettingsRepository.TaskRoutingConfig(
                enabled = true,
                chatModelId = "removed-chat",
                chatProviderId = "p1",
                reasoningModelId = "kept-model",
                codeModelId = "removed-code",
                creativeModelId = "removed-creative",
                analysisModelId = "removed-analysis",
            ),
            utilityModelBinding = UtilityModelBinding(providerId = "p1", modelId = "removed-small"),
            utilityLargeModelBinding = UtilityModelBinding(providerId = "p2", modelId = "removed-large"),
            ragConfig = RagConfig(
                embeddingSource = RagConfig.EmbeddingSource.CLOUD,
                cloudProviderId = "p1",
                cloudModel = "removed-embedding",
            ),
            visionModelId = "removed-vision",
        )
        val removed = setOf(
            "removed-image",
            "removed-video",
            "removed-chat",
            "removed-code",
            "removed-creative",
            "removed-analysis",
            "removed-small",
            "removed-large",
            "removed-embedding",
            "removed-vision",
        )

        val result = cleanupRemovedModelReferences(
            providerId = "p1",
            removedModelIds = removed,
            retainedModelIds = emptySet(),
            references = references,
        )

        assertEquals("", result.imageGenConfig.modelId)
        assertEquals("p1", result.imageGenConfig.providerId)
        assertEquals("", result.videoGenConfig.modelId)
        assertNull(result.taskRoutingConfig.chatModelId)
        assertEquals("kept-model", result.taskRoutingConfig.reasoningModelId)
        assertNull(result.taskRoutingConfig.codeModelId)
        assertNull(result.taskRoutingConfig.creativeModelId)
        assertNull(result.taskRoutingConfig.analysisModelId)
        // 绑定带 providerId:只有属于该 Provider 的绑定被置空,p2 的绑定原样保留
        assertNull(result.utilityModelBinding)
        assertEquals(UtilityModelBinding(providerId = "p2", modelId = "removed-large"), result.utilityLargeModelBinding)
        assertEquals("", result.ragConfig.cloudModel)
        assertEquals("p1", result.ragConfig.cloudProviderId)
        assertNull(result.visionModelId)
    }

    @Test
    fun cleanupRemovedModelReferences_keepsModelIdsStillOfferedByOtherProviders() {
        val references = ModelReferences(
            imageGenConfig = ImageGenConfig(providerId = "p1", modelId = "shared-image"),
            ragConfig = RagConfig(cloudProviderId = "p1", cloudModel = "shared-embedding"),
            visionModelId = "shared-vision",
        )

        val result = cleanupRemovedModelReferences(
            providerId = "p1",
            removedModelIds = setOf("shared-image", "shared-embedding", "shared-vision"),
            retainedModelIds = setOf("shared-image", "shared-embedding", "shared-vision"),
            references = references,
        )

        assertEquals(references, result)
    }
}
