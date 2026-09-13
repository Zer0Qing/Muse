package io.zer0.muse.data.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderAssistantCleanupTest {

    @Test
    fun clearsModelAndProviderTogetherOnlyForDeletedProvider() {
        val deleted = AssistantEntity(
            id = "a-deleted",
            name = "Deleted provider assistant",
            providerId = "provider-deleted",
            modelId = "model-a",
        )
        val retained = AssistantEntity(
            id = "a-retained",
            name = "Retained assistant",
            providerId = "provider-other",
            modelId = "model-a",
        )

        val result = clearProviderBindings(listOf(deleted, retained), "provider-deleted")

        val cleaned = result.first { it.id == deleted.id }
        assertNull(cleaned.providerId)
        assertNull(cleaned.modelId)
        val untouched = result.first { it.id == retained.id }
        assertEquals("provider-other", untouched.providerId)
        assertEquals("model-a", untouched.modelId)
        assertNotNull(untouched)
    }
}
