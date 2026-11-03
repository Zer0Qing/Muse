package io.zer0.muse.conversation.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftEventTest {
    @Test
    fun `finished draft rejects late content`() {
        val store = DraftStore()
        assertTrue(store.apply(start(sequence = 1)) is DraftApplyResult.Applied)
        assertTrue(store.apply(append(sequence = 2, delta = "hello")) is DraftApplyResult.Applied)
        assertTrue(store.apply(finish(sequence = 3)) is DraftApplyResult.Applied)

        assertTrue(store.apply(append(sequence = 4, delta = " late")) is DraftApplyResult.Rejected)
    }

    @Test
    fun `replace keeps stable part identity`() {
        val store = DraftStore()
        store.apply(start(sequence = 1))
        val first = store.apply(append(sequence = 2, delta = "draft"))
        val partId = (first as DraftApplyResult.Applied).snapshot.parts.single().partId

        val replaced = store.apply(replace(sequence = 3, partId = partId, text = "final"))
        val snapshot = (replaced as DraftApplyResult.Applied).snapshot

        assertEquals(partId, snapshot.parts.single().partId)
        assertEquals("final", snapshot.parts.single().text)
    }

    @Test
    fun `duplicate and older sequences cannot append twice`() {
        val store = DraftStore()
        store.apply(start(sequence = 1))
        store.apply(append(sequence = 2, delta = "once"))

        assertTrue(store.apply(append(sequence = 2, delta = "once")) is DraftApplyResult.Duplicate)
        assertTrue(store.apply(append(sequence = 1, delta = "stale")) is DraftApplyResult.IgnoredStale)
        assertEquals("once", store.snapshot().parts.single().text)
    }

    @Test
    fun `events from another generation are rejected`() {
        val store = DraftStore()
        store.apply(start(sequence = 1))

        val wrong =
            store.apply(
                append(
                    sequence = 2,
                    delta = "wrong",
                    identity = identity.copy(generationId = "generation-other"),
                ),
            )

        assertTrue(wrong is DraftApplyResult.Rejected)
    }

    @Test
    fun `tool card and media parts remain structured`() {
        val store = DraftStore()
        store.apply(start(sequence = 1))
        val result = store.apply(
            DraftEvent.SetMetadataPart(
                sequence = 2,
                identity = identity,
                partId = "card-1",
                kind = PartKind.Card,
                text = "",
                metadata = mapOf("cardId" to "card-1", "language" to "html"),
            ),
        )

        val part = (result as DraftApplyResult.Applied).snapshot.parts.single()
        assertEquals(PartKind.Card, part.kind)
        assertEquals("html", part.metadata["language"])
    }

    private fun start(sequence: Long) = DraftEvent.Start(
        sequence = sequence,
        identity = identity,
        startedAt = 1L,
    )

    private fun append(sequence: Long, delta: String, identity: RunIdentity = this.identity) = DraftEvent.AppendText(
        sequence = sequence,
        identity = identity,
        partId = "part-1",
        delta = delta,
    )

    private fun replace(sequence: Long, partId: String, text: String) = DraftEvent.ReplaceText(
        sequence = sequence,
        identity = identity,
        partId = partId,
        text = text,
    )

    private fun finish(sequence: Long) = DraftEvent.Finish(
        sequence = sequence,
        identity = identity,
        state = TerminalState.Completed,
    )

    private val identity = RunIdentity(
        sessionId = "session-1",
        incarnation = 1,
        turnId = "turn-1",
        generationId = "generation-1",
        streamId = "stream-1",
        selectionSetId = "selection-1",
    )
}
