package io.zer0.muse.backup

import io.zer0.common.AppJson
import io.zer0.muse.data.conversation.ConversationCommandReceiptEntity
import io.zer0.muse.data.conversation.ConversationDisplaySlotEntity
import io.zer0.muse.data.conversation.ConversationSelectionEntity
import io.zer0.muse.proactive.SessionProactivePreferenceEntity
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupProactiveSessionPreferencesTest {
    @Test
    fun `session proactive mute survives backup serialization`() {
        val rows = listOf(
            SessionProactivePreferenceEntity(sessionId = "s1", muted = true),
            SessionProactivePreferenceEntity(sessionId = "s2", muted = true),
        )
        val json = AppJson.encodeToString(ListSerializer(SessionProactivePreferenceEntity.serializer()), rows)
        val decoded = AppJson.decodeFromString(ListSerializer(SessionProactivePreferenceEntity.serializer()), json)

        assertEquals(rows, decoded)
    }

    @Test
    fun `conversation state rows survive backup serialization`() {
        val receipt = ConversationCommandReceiptEntity(
            commandId = "c1",
            fingerprint = "f1",
            payloadJson = "{}",
            sessionId = "s1",
            messageId = "m1",
            turnId = "t1",
            acceptedAt = 10L,
            resultJson = "{\"ok\":true}",
        )
        val selection = ConversationSelectionEntity(
            selectionSetId = "sel1",
            sessionId = "s1",
            revision = 2L,
            userSelectionJson = "{\"u\":\"1\"}",
            assistantSelectionJson = "{\"a\":\"1\"}",
        )
        val slot = ConversationDisplaySlotEntity(
            slotId = "slot1",
            sessionId = "s1",
            kind = "BODY",
            parentSlotId = null,
            logicalPosition = 1L,
            messageId = "m1",
        )

        assertEquals(
            receipt,
            AppJson.decodeFromString(
                ConversationCommandReceiptEntity.serializer(),
                AppJson.encodeToString(ConversationCommandReceiptEntity.serializer(), receipt),
            ),
        )
        assertEquals(
            selection,
            AppJson.decodeFromString(
                ConversationSelectionEntity.serializer(),
                AppJson.encodeToString(ConversationSelectionEntity.serializer(), selection),
            ),
        )
        assertEquals(
            slot,
            AppJson.decodeFromString(
                ConversationDisplaySlotEntity.serializer(),
                AppJson.encodeToString(ConversationDisplaySlotEntity.serializer(), slot),
            ),
        )
    }
}
