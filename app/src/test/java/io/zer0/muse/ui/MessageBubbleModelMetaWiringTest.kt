package io.zer0.muse.ui

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageBubbleModelMetaWiringTest {

    @Test
    fun `model metadata is aligned to the trailing edge`() {
        val source = locateMessageBubbleSource()
        val marker = "if (!isUser && chatPrefs.showModelName && !modelName.isNullOrBlank())"
        val start = source.indexOf(marker)
        require(start >= 0) { "model metadata block not found" }
        val block = source.substring(start, (start + 700).coerceAtMost(source.length))

        assertTrue(block.contains("textAlign = TextAlign.End"))
    }

    @Test
    fun `assistant actions remain available for reasoning-only or empty replies`() {
        val source = locateMessageBubbleSource()
        assertTrue(source.contains("val hasReasoning = !msg.reasoning.isNullOrBlank()"))
        // v2.6.0: 思考复制改由 QuickAction.REASONING 可见性判定驱动，旧 showReasoningCopy 变量已移除
        assertTrue(source.contains("QuickAction.REASONING -> hasReasoning"))
        assertTrue(source.contains("QuickAction.REASONING"))
        assertTrue(source.contains("msg.reasoning.orEmpty()"))
        assertTrue(source.contains("hasAnyAssistantQuickAction"))
    }

    private fun locateMessageBubbleSource(): String {
        val candidates =
            listOf(
                Path.of("src/main/java/io/zer0/muse/ui/MessageBubble.kt"),
                Path.of("app/src/main/java/io/zer0/muse/ui/MessageBubble.kt"),
            )
        val path = candidates.firstOrNull(Files::exists)
            ?: error("Unable to locate MessageBubble.kt from ${System.getProperty("user.dir")}")
        return Files.newBufferedReader(path).use { it.readText() }
    }
}
