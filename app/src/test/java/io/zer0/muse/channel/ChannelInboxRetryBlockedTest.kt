package io.zer0.muse.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ChannelInboxRetryBlockedTest {

    @Test
    fun `blocked dispatch can be re-queued to PENDING or dismissed to IGNORED`() {
        val dir = Files.createTempDirectory("channel-inbox-retry-test").toFile()
        val messages = kotlinx.coroutines.flow.MutableStateFlow<List<ChannelInbox.Inbound>>(emptyList())
        val journal = ChannelInboxJournal(messages)
        journal.attach(java.io.File(dir, "inbox.json"), java.io.File(dir, "ledger.json"))

        val firstSource = ChannelInbox.Source(platform = "FEISHU", from = "u1", channelId = "c1", eventId = "e1")
        val secondSource = ChannelInbox.Source(platform = "FEISHU", from = "u2", channelId = "c1", eventId = "e2")
        assertTrue(
            journal.record(
                firstSource,
                ChannelInbox.Inbound(
                    platform = "FEISHU",
                    from = "u1",
                    summary = "hi",
                    sourceChannelId = "c1",
                    sourceEventId = "e1",
                    dispatchId = "d1",
                    dispatchState = ChannelInbox.DispatchState.PENDING,
                ),
            ),
        )
        assertTrue(
            journal.record(
                secondSource,
                ChannelInbox.Inbound(
                    platform = "FEISHU",
                    from = "u2",
                    summary = "yo",
                    sourceChannelId = "c1",
                    sourceEventId = "e2",
                    dispatchId = "d2",
                    dispatchState = ChannelInbox.DispatchState.PENDING,
                ),
            ),
        )

        val claimed1 = journal.claimNextPending()!!
        val claimed2 = journal.claimNextPending()!!
        journal.blockDispatch(claimed1.dispatchId, "ERR_TEST")
        journal.blockDispatch(claimed2.dispatchId, "ERR_TEST")

        assertTrue(journal.retryBlocked("d1"))
        val afterRetry = messages.value.first { it.dispatchId == "d1" }
        assertEquals(ChannelInbox.DispatchState.PENDING, afterRetry.dispatchState)
        assertEquals("", afterRetry.dispatchErrorCode)

        assertTrue(journal.dismissBlocked("d2"))
        val afterDismiss = messages.value.first { it.dispatchId == "d2" }
        assertEquals(ChannelInbox.DispatchState.IGNORED, afterDismiss.dispatchState)
        assertFalse(journal.retryBlocked("d2"))
    }
}
