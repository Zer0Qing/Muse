package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.ChatService
import io.zer0.muse.data.MultiAgentConfig
import io.zer0.muse.data.assistant.AssistantRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SkillDelegateAgentImplTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun cancelledRequest_returnsErrorWithoutRunningAssistant() = runBlocking {
        val pauseManager = mockk<DelegationPauseManager>()
        every { pauseManager.isCancelled("req-cancelled") } returns true

        val impl = SkillDelegateAgentImpl(
            context = context,
            chatService = mockk<ChatService>(relaxed = true),
            assistantRepository = mockk<AssistantRepository>(relaxed = true),
            multiAgentConfigProvider = { MultiAgentConfig() },
            llmAggregator = null,
            pauseManager = pauseManager,
            delegationChainTracker = null,
            agentDmRepository = null,
            deferredResultStore = null,
            subagentThreadStore = null,
            agentConcurrencyLimiter = AgentConcurrencyLimiter(),
            journal = null,
        )

        val result = impl.delegateAgent(
            DelegationContract.DelegationRequest(
                requestId = "req-cancelled",
                task = "do something",
                targetType = DelegationContract.DelegationRequest.TargetType.ASSISTANT,
                targetId = "assistant-a",
            ),
        )

        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("取消"))
        assertEquals("req-cancelled", result.requestId)
    }
}
