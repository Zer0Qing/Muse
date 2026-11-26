package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentSquadAssetStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val store: AgentSquadIdentityStore get() = AgentSquadIdentityStore(context)

    @Test
    fun `specialty tags and compute target survive reopen`() {
        val botId = "bot-" + System.nanoTime()
        val first = AgentSquadIdentityStore(context)
        first.setSpecialtyTags(botId, listOf("写作", "校对", "写作", " "))
        first.setDefaultComputeNode(botId, "node-42")
        first.toggleChannelForBot(botId, "channel-1")

        val binding = AgentSquadIdentityStore(context).bindings.value[botId]

        assertEquals(setOf("写作", "校对"), binding?.specialtyTags)
        assertEquals("node-42", binding?.defaultComputeNodeId)
        assertEquals(setOf("channel-1"), binding?.channelIds)
    }

    @Test
    fun `specialty tags are capped and blank entries removed`() {
        val botId = "bot-" + System.nanoTime()
        val tags = (0..12).map { "tag-$it" } + listOf(" ", "tag-0")
        store.setSpecialtyTags(botId, tags)

        val actual = store.bindings.value[botId]?.specialtyTags.orEmpty()
        assertEquals(8, actual.size)
        assertTrue(actual.none { it.isBlank() })
        assertTrue(actual.containsAll((0..7).map { "tag-$it" }))
        assertFalse(actual.contains("tag-8"))
    }

    @Test
    fun `blank bot id is ignored`() {
        store.setSpecialtyTags("", listOf("ignored"))
        store.setDefaultComputeNode("", "node")

        assertTrue(store.bindings.value.none { it.key.isBlank() })
    }
}
