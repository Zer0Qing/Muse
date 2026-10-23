package io.zer0.muse.channel

import io.zer0.common.ProcessWriteGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ChannelInboxTest {
    private lateinit var directory: File
    private lateinit var inboxFile: File
    private lateinit var ledgerFile: File
    private lateinit var journal: ChannelInboxJournal
    private val nowMillis = AtomicLong(1_800_000_000_000L)

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("channel-inbox-test").toFile()
        inboxFile = File(directory, "inbox.json")
        ledgerFile = File(directory, "events.json")
        journal = newJournal()
        journal.attach(inboxFile, ledgerFile)
    }

    @After
    fun tearDown() {
        ProcessWriteGate.end()
        directory.deleteRecursively()
    }

    @Test
    fun inbound_record_fails_closed_while_backup_restore_gate_is_active() {
        assertTrue(ProcessWriteGate.begin())
        try {
            val source = ChannelInbox.Source("QQ", "user", "qq-a", "restore-event")
            assertThrows(IllegalStateException::class.java) {
                journal.record(source, inbound(source, "must retry"))
            }
            assertTrue(journal.messagesForTest().isEmpty())
        } finally {
            ProcessWriteGate.end()
        }
    }

    @Test
    fun concurrentRecordsStopAtThePendingLimitWithoutDroppingAcceptedEvents() {
        val writers = 200
        val start = CountDownLatch(1)
        val done = CountDownLatch(writers)
        val accepted = AtomicInteger()
        val rejected = AtomicInteger()

        repeat(writers) { index ->
            Thread {
                try {
                    start.await()
                    try {
                        val source = ChannelInbox.Source("platform-$index", "user-$index")
                        journal.record(
                            source = source,
                            item = inbound(source, "message-$index"),
                        )
                        accepted.incrementAndGet()
                    } catch (_: ChannelInboxCapacityException) {
                        rejected.incrementAndGet()
                    }
                } finally {
                    done.countDown()
                }
            }.start()
        }

        start.countDown()
        assertTrue("all writers should finish", done.await(30, TimeUnit.SECONDS))

        val memory = journal.messagesForTest()
        assertEquals(100, memory.size)
        assertEquals(100, accepted.get())
        assertEquals(100, rejected.get())
        assertEquals(100, memory.count { it.dispatchState == ChannelInbox.DispatchState.PENDING })
        assertEquals(100, memory.map { it.platform }.toSet().size)
    }

    @Test
    fun duplicatePendingEventWakesDispatcherEvenWhenLedgerAlreadyClaimed() {
        var deliveries = 0
        var wakes = 0
        journal.onInbound = { deliveries++ }
        journal.setPendingListener { wakes++ }
        val sourceA = ChannelInbox.Source("QQ", "user", "qq-a", "event-1")
        val sourceB = ChannelInbox.Source("QQ", "user", "qq-b", "event-1")

        assertTrue(
            journal.record(
                sourceA,
                inbound(sourceA, "hello"),
            ),
        )
        assertTrue(
            journal.record(
                sourceB,
                inbound(sourceB, "hello"),
            ),
        )
        assertFalse(
            journal.record(
                sourceA,
                inbound(sourceA, "hello"),
            ),
        )

        assertEquals(2, journal.messagesForTest().size)
        assertEquals(2, deliveries)
        assertEquals(3, wakes)
    }

    @Test
    fun processingEventIsRequeuedAfterRestartAndKeepsPreparedReply() {
        val plaintextToken = "private-reply-context"
        val encryptedToken = "enc_v1:AAECAwQFBgcICQoL"
        val source = ChannelInbox.Source("WECLAW", "user", "weclaw-a", "event-restart", encryptedToken)
        assertTrue(
            journal.record(
                source,
                inbound(source, "hello"),
            ),
        )
        assertFalse(inboxFile.readText().contains(plaintextToken))
        val claimed = journal.claimNextPending()!!
        assertTrue(journal.savePreparedReply(claimed.dispatchId, "same reply"))
        assertTrue(journal.saveEncryptedAgentCheckpoint(claimed.dispatchId, "enc_v1:agent-checkpoint"))

        val restarted = newJournal().also { it.attach(inboxFile, ledgerFile) }
        restarted.recoverInterruptedDispatches()
        val replay = restarted.claimNextPending()!!

        assertEquals(claimed.dispatchId, replay.dispatchId)
        assertEquals(ChannelInbox.DispatchState.PROCESSING, replay.dispatchState)
        assertEquals("same reply", replay.preparedReply)
        assertEquals(encryptedToken, replay.encryptedReplyContextToken)
        assertEquals("enc_v1:agent-checkpoint", replay.encryptedAgentCheckpoint)
        assertFalse(
            restarted.record(
                source,
                inbound(source, "hello"),
            ),
        )
        assertEquals(1, restarted.pendingCount())
        restarted.completeDispatch(replay.dispatchId, ignored = false)
        assertEquals("", restarted.snapshot().single().encryptedReplyContextToken)
        assertEquals("", restarted.snapshot().single().encryptedAgentCheckpoint)
    }

    @Test
    fun blockedDispatchPreservesCheckpointAndIsNotReplayedAfterRestart() {
        val encryptedToken = "enc_v1:reply-context"
        val source = ChannelInbox.Source("WECLAW", "user", "weclaw-a", "event-blocked", encryptedToken)
        assertTrue(journal.record(source, inbound(source, "hello")))
        val processing = journal.claimNextPending()!!
        assertTrue(journal.saveEncryptedAgentCheckpoint(processing.dispatchId, "enc_v1:agent-checkpoint"))

        journal.blockDispatch(processing.dispatchId, CHANNEL_AGENT_CHECKPOINT_UNREADABLE)

        val blocked = journal.snapshot().single()
        assertEquals(ChannelInbox.DispatchState.BLOCKED, blocked.dispatchState)
        assertEquals(CHANNEL_AGENT_CHECKPOINT_UNREADABLE, blocked.dispatchErrorCode)
        assertEquals("enc_v1:agent-checkpoint", blocked.encryptedAgentCheckpoint)
        assertEquals("", blocked.encryptedReplyContextToken)
        assertEquals(null, journal.claimNextPending())

        val restarted = newJournal().also { it.attach(inboxFile, ledgerFile) }
        assertEquals(ChannelInbox.DispatchState.BLOCKED, restarted.snapshot().single().dispatchState)
        assertEquals(CHANNEL_AGENT_CHECKPOINT_UNREADABLE, restarted.snapshot().single().dispatchErrorCode)
        assertEquals("enc_v1:agent-checkpoint", restarted.snapshot().single().encryptedAgentCheckpoint)
        assertEquals(null, restarted.claimNextPending())
    }

    @Test
    fun legacyInboxJsonDefaultsToCompletedAndIsNotReplayed() {
        inboxFile.writeText(
            """[{"platform":"QQ","from":"legacy-user","summary":"old message","raw":"","timestamp":123}]""",
        )
        val restored = newJournal().also { it.attach(inboxFile, ledgerFile) }

        assertEquals(ChannelInbox.DispatchState.COMPLETED, restored.messagesForTest().single().dispatchState)
        assertEquals(null, restored.claimNextPending())
    }

    @Test
    fun pendingEventsSurviveHistoryRetentionAndCapacityRejectionDoesNotClaimLedger() {
        repeat(100) { index ->
            val eventId = "event-$index"
            val source = ChannelInbox.Source("QQ", "user-$index", "qq-a", eventId)
            assertTrue(
                journal.record(
                    source,
                    inbound(source, "message-$index"),
                ),
            )
        }
        val overflow = ChannelInbox.Source("QQ", "overflow-user", "qq-overflow", "event-overflow")

        try {
            journal.record(overflow, inbound(overflow, "overflow"))
            throw AssertionError("a full pending queue must reject the event")
        } catch (_: ChannelInboxCapacityException) {
            // The event remains retryable and is not claimed in the deduplication ledger.
        }

        assertEquals(100, journal.messagesForTest().size)
        assertFalse(
            WebhookEventDeduplicator(ledgerFile) { nowMillis.get() }
                .contains("QQ", "qq-overflow", "event-overflow"),
        )
    }

    @Test
    fun clearingInboxHistoryPreservesPendingButRemovesCompletedEntries() {
        val completedSource = ChannelInbox.Source("QQ", "done", "qq-a", "event-done")
        assertTrue(journal.record(completedSource, inbound(completedSource, "done")))
        val completed = journal.claimNextPending()!!
        journal.completeDispatch(completed.dispatchId, ignored = false)
        val pendingSource = ChannelInbox.Source("QQ", "pending", "qq-a", "event-pending")
        assertTrue(journal.record(pendingSource, inbound(pendingSource, "pending")))

        journal.clear()

        assertEquals(listOf("event-pending"), journal.messagesForTest().map { it.sourceEventId })
        assertEquals(1, journal.pendingCount())
    }

    @Test
    fun retryUsesExponentialDelayAndBecomesClaimableWhenDue() {
        val source = ChannelInbox.Source("QQ", "user", "qq-a", "retry-event")
        journal.record(source, inbound(source, "hello"))
        val processing = journal.claimNextPending()!!
        val retryAt = journal.retryDispatch(processing.dispatchId)

        assertEquals(nowMillis.get() + 1_000L, retryAt)
        assertEquals(null, journal.claimNextPending())
        nowMillis.addAndGet(1_000L)
        assertEquals(processing.dispatchId, journal.claimNextPending()?.dispatchId)
    }

    private fun newJournal() = ChannelInboxJournal(nowMillis = { nowMillis.get() })

    private fun inbound(source: ChannelInbox.Source, text: String) = ChannelInbox.Inbound(
        platform = source.platform,
        from = source.from,
        summary = text,
        sourceChannelId = source.channelId,
        sourceEventId = source.eventId,
        dispatchId = java.util.UUID.randomUUID().toString(),
        dispatchState = ChannelInbox.DispatchState.PENDING,
        encryptedReplyContextToken = source.encryptedReplyContextToken,
    )

    private fun ChannelInboxJournal.messagesForTest(): List<ChannelInbox.Inbound> = snapshot()
}
