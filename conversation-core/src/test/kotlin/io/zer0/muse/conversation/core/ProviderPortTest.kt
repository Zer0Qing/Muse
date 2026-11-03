package io.zer0.muse.conversation.core

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderPortTest {
    @Test
    fun `provider port receives frozen run identity`() = runTest {
        var received: ProviderRequest? = null
        val port = ProviderPort { request ->
            received = request
            flowOf(ProviderEvent.Completed(1, "stop"))
        }
        val request = ProviderRequest(
            identity = identity(),
            selectionSetId = "selection-1",
            messages = emptyList(),
            tools = emptyList(),
        )

        port.stream(request).collect {}

        assertEquals(identity(), received?.identity)
        assertEquals("selection-1", received?.selectionSetId)
    }

    private fun identity() = RunIdentity(
        sessionId = "session-1",
        incarnation = 1,
        turnId = "turn-1",
        generationId = "generation-1",
        streamId = "stream-1",
        selectionSetId = "selection-1",
    )
}
