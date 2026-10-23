package io.zer0.muse.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ChatGenerationRestoreWriteGateWiringTest {

    private fun source(): String = listOf(
        Path.of("src/main/java/io/zer0/muse/ui/chat/ChatGenerationController.kt"),
        Path.of("app/src/main/java/io/zer0/muse/ui/chat/ChatGenerationController.kt"),
    )
        .firstOrNull(Files::exists)
        ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
        ?: error("ChatGenerationController.kt not found")

    @Test
    fun `queued sends and outbox recovery yield while backup restore is active`() {
        val text = source()
        val enqueueBody = text.substringAfter("fun enqueueSend(")
            .substringBefore("/** 队列消费失败")
        val consumeBody = text.substringAfter("suspend fun consumeSendRequest")
            .substringBefore("/**")
        val requeueBody = text.substringAfter("suspend fun requeueOutboxForSession")
            .substringBefore("/**")

        assertTrue(text.contains("private fun restoreBlocksChatWrites"))
        assertTrue(text.contains("ProcessWriteGate.restoring"))
        assertTrue(enqueueBody.contains("restoreBlocksChatWrites"))
        assertTrue(consumeBody.contains("restoreBlocksChatWrites"))
        assertTrue(requeueBody.contains("restoreBlocksChatWrites"))
    }

    @Test
    fun `final assistant metadata and checkpoint cleanup do not write during restore`() {
        val text = source()
        val regenerateBody = text.substringAfter("fun regenerateLastAssistant")
            .substringBefore("private fun reportRegenerateUnavailable")
        val finalizeBody = text.substringAfter("suspend fun finalizeResponse")
            .substringBefore("companion object")

        assertTrue(regenerateBody.contains("restoreBlocksChatWrites"))
        assertTrue(finalizeBody.contains("restoreBlocksChatWrites"))
        assertTrue(finalizeBody.contains("upsertMessageEntity"))
        assertTrue(finalizeBody.contains("deleteGenerationCheckpoints"))
    }
}
