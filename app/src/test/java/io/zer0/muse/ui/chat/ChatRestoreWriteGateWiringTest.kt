package io.zer0.muse.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ChatRestoreWriteGateWiringTest {

    @Test
    fun `streaming assistant snapshots skip database writes during backup restore`() {
        val source =
            listOf(
                Path.of("src/main/java/io/zer0/muse/ui/chat/ChatStreamCoordinator.kt"),
                Path.of("app/src/main/java/io/zer0/muse/ui/chat/ChatStreamCoordinator.kt"),
            )
                .firstOrNull(Files::exists)
                ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
                ?: error("ChatStreamCoordinator.kt not found")

        val currentBody = source.substringAfter("suspend fun persistCurrentAssistant")
            .substringBefore("suspend fun persistInterruptedAssistant")
        val interruptedBody = source.substringAfter("suspend fun persistInterruptedAssistant")
            .substringBefore("// ── 字符串处理工具")
        assertTrue(currentBody.contains("ProcessWriteGate.restoring"))
        assertTrue(interruptedBody.contains("ProcessWriteGate.restoring"))
    }
}
