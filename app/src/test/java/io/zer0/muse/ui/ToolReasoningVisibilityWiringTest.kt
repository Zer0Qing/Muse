package io.zer0.muse.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ToolReasoningVisibilityWiringTest {

    private fun read(vararg paths: String): String = paths
        .map(Path::of)
        .firstOrNull(Files::exists)
        ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
        ?: error("source file not found")

    @Test
    fun `tool messages keep reasoning visible when reasoning display is enabled`() {
        val source =
            read(
                "src/main/java/io/zer0/muse/ui/MessageBubble.kt",
                "app/src/main/java/io/zer0/muse/ui/MessageBubble.kt",
            )
        val reasoningBlock = source.substringAfter("// Phase 8.3: 推理过程折叠卡片")
            .substringBefore("// 与 mood/reasoning 块同构")
        assertTrue(reasoningBlock.contains("if (chatPrefs.showReasoning"))
        assertFalse(reasoningBlock.contains("!isToolRoundMessage"))
    }

    @Test
    fun `expanded grouped reasoning is not silently capped at six lines`() {
        val source =
            read(
                "src/main/java/io/zer0/muse/ui/ToolRunCard.kt",
                "app/src/main/java/io/zer0/muse/ui/ToolRunCard.kt",
            )
        val reasoningBlock = source.substringAfter("组内思考条目")
        assertFalse(reasoningBlock.contains("maxLines = 6"))
        assertFalse(reasoningBlock.contains("TextOverflow.Ellipsis"))
    }

    @Test
    fun `reasoning-only messages are not counted as tool operations`() {
        val source =
            read(
                "src/main/java/io/zer0/muse/ui/ChatScreen.kt",
                "app/src/main/java/io/zer0/muse/ui/ChatScreen.kt",
            )
        val groupingBlock = source.substringAfter("ChatDisplayGrouper.group(visibleMessages)")
            .substringBefore(".filterIsInstance")
        assertTrue(groupingBlock.contains("msg.toolCallInfo != null"))
        assertFalse(groupingBlock.contains("msg.reasoning?.isNotBlank() == true"))
    }
}
