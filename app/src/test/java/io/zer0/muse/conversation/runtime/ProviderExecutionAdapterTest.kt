package io.zer0.muse.conversation.runtime

import io.zer0.ai.core.ChatStreamEvent
import io.zer0.muse.conversation.core.ProviderEvent
import io.zer0.muse.conversation.core.ProviderEventType
import io.zer0.muse.conversation.runtime.ProviderStreamMapper
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderExecutionAdapterTest {
    @Test
    fun `content deltas are mapped without losing order`() = runTest {
        val mapper = ProviderStreamMapper()
        val events = mapper.map(
            flowOf(
                ChatStreamEvent.ContentDelta("one"),
                ChatStreamEvent.ContentDelta("two"),
                ChatStreamEvent.Done("stop"),
            ),
        )

        val result = mutableListOf<ProviderEvent>()
        events.collect { result += it }

        assertEquals(listOf(ProviderEventType.CONTENT, ProviderEventType.CONTENT, ProviderEventType.COMPLETED),
            result.map { it.type })
        assertEquals(listOf("one", "two"), result.take(2).map { (it as ProviderEvent.Content).text })
    }

    @Test
    fun `interrupted stream remains interrupted`() = runTest {
        val mapper = ProviderStreamMapper()
        val events = mapper.map(flowOf(ChatStreamEvent.StreamInterrupted("network")))
        val result = mutableListOf<ProviderEvent>()

        events.collect { result += it }

        assertTrue(result.single() is ProviderEvent.Interrupted)
    }
}
