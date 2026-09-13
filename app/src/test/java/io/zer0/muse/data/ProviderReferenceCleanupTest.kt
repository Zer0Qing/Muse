package io.zer0.muse.data

import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
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
            compressModelId = "private-model",
            visionModelId = "private-model",
            visionProviderId = "deleted",
            imageGenConfig = ImageGenConfig(providerId = "deleted", modelId = "private-model"),
            videoGenConfig = VideoGenConfig(providerId = "remaining", modelId = "shared-model"),
            taskRoutingConfig = SettingsRepository.TaskRoutingConfig(
                enabled = true,
                chatModelId = "private-model",
                chatProviderId = "deleted",
                codeModelId = "shared-model",
                codeProviderId = "remaining",
            ),
            sessionModelOverrides = mapOf("s1" to "private-model", "s2" to "shared-model"),
            sessionProviderOverrides = mapOf("s1" to "deleted", "s2" to "remaining"),
        )

        assertEquals(listOf(remaining), result.providers)
        assertEquals("remaining", result.activeProviderId)
        assertNull(result.selectedModelId)
        assertEquals("shared-model", result.toolModelId)
        assertNull(result.compressModelId)
        assertNull(result.visionModelId)
        assertNull(result.visionProviderId)
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
    }
}
