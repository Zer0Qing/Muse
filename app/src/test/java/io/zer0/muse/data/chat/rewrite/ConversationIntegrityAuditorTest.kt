package io.zer0.muse.data.chat.rewrite

import io.zer0.muse.data.session.MessageEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationIntegrityAuditorTest {
    @Test
    fun reportsDuplicateZeroAndDanglingReferencesWithoutMutatingInput() {
        val messages = listOf(
            MessageEntity("m1", "s1", "USER", "one", seq = 0, variantGroupId = "g1", parentMessageId = "missing", createdAt = 1),
            MessageEntity("m2", "s1", "ASSISTANT", "two", seq = 1, variantGroupId = "g1", createdAt = 2),
            MessageEntity("m3", "s1", "ASSISTANT", "three", seq = 1, variantGroupId = "g2", createdAt = 3),
        )
        val report = ConversationIntegrityAuditor.audit("s1", messages, emptyList(), emptyList(), sessionDeleted = false)
        assertFalse(report.ok)
        assertTrue(report.zeroSeqCount == 1)
        assertTrue(report.duplicateSeqCount == 1)
        assertTrue(report.danglingParentMessageCount == 1)
        assertTrue(report.danglingVariantGroupCount == 0)
    }
}
