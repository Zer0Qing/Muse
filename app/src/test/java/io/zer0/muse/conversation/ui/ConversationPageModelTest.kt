package io.zer0.muse.conversation.ui

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationPageModelTest {
    @Test
    fun `typing does not change timeline revision`() = runTest {
        val model = ConversationPageModel()
        val before = model.state.first().timelineRevision

        model.updateInput("hello")

        val after = model.state.first()
        assertEquals(before, after.timelineRevision)
        assertEquals("hello", after.input)
    }

    @Test
    fun `content delta does not change timeline structure`() = runTest {
        val model = ConversationPageModel()
        val before = model.state.first().timelineRevision

        model.updateStreamingPart("assistant-1", "part-1", "new content")

        val after = model.state.first()
        assertEquals(before, after.timelineRevision)
        assertEquals("new content", after.streamingParts["assistant-1:part-1"])
    }

    @Test
    fun `timeline replacement advances structure revision`() = runTest {
        val model = ConversationPageModel()
        val before = model.state.first().timelineRevision

        model.replaceTimeline(listOf(TimelineRow.User("slot-1", "user-1")))

        val after = model.state.first()
        assertEquals(before + 1, after.timelineRevision)
        assertEquals(1, after.timeline.size)
    }
}
