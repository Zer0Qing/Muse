package io.zer0.muse.data.agentbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTeachRecorderTest {

    @Test
    fun `resume restores structured steps and original recording window`() {
        val recorder = AgentTeachRecorder()
        val snapshot = AgentTeachRecorder.State(
            recording = true,
            titleDraft = "流程",
            botId = "bot",
            startedAt = 123L,
            steps = listOf(
                AgentTeachRecorder.RecordedStep(
                    index = 1,
                    action = "点击搜索",
                    targetApp = "示例",
                    targetPackage = "com.example.app",
                    contentDesc = "搜索",
                    resourceIdHint = "id/search",
                    actionKind = "click",
                ),
            ),
        )

        recorder.resume(snapshot)

        assertEquals(snapshot, recorder.state.value)
        assertTrue(recorder.state.value.recording)
    }
}
