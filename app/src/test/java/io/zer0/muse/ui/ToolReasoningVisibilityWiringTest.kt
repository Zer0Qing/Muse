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
        // v2.6.7: 展开区已从 ToolRunCard 抽到 ToolRunExpandedRows（LongMethod 整改），
        // 旧写法「注释之后的全文」会跨到下一个函数里导致误判。
        // 改为把作用域限定在抽出的那个函数内，断言强度不变。
        // 展开区现在会调用 ProcessStepItem / ProcessStepState（定义在它下面），
        // 所以边界改为「到下一个顶层函数前」，不再把边界钉在 ToolRunHeaderRow
        // ——后者已不再是紧随其后的那个函数。
        val reasoningBlock =
            source.substringAfter("private fun ToolRunExpandedRows")
                .substringBefore("\ninternal data class ProcessTally")
        // 思考条目现在走 ProcessStepItem 的可折叠壳，锚点跟着新实现走；
        // 真正要守住的仍然是「不截断」：思考正文不得出现行数上限或省略号。
        assertTrue(reasoningBlock.contains("chat_process_thinking_done"))
        assertFalse(reasoningBlock.contains("maxLines = 6"))
        assertFalse(
            reasoningBlock.substringAfter("text = reasoningText").contains("TextOverflow.Ellipsis"),
        )
    }

    @Test
    fun `grouping predicate accepts reasoning-only messages as process steps`() {
        val source =
            read(
                "src/main/java/io/zer0/muse/ui/ChatScreen.kt",
                "app/src/main/java/io/zer0/muse/ui/ChatScreen.kt",
            )
        val groupingBlock = source.substringAfter("ChatDisplayGrouper.group(visibleMessages)")
            .substringBefore(".filterIsInstance")
        // v2.6.10: 判据放宽为「没有最终正文就算过程」——
        // 纯思考消息也要并入过程流水（与平台侧的 thinking/tool_group 同级）。
        // 这里守住两点：① 仍要求「无正文」；② 过程来源要么是工具、要么是思考。
        assertTrue(groupingBlock.contains("displayContent"))
        assertTrue(groupingBlock.contains("isBlank()"))
        assertTrue(groupingBlock.contains("msg.toolCallInfo != null"))
        assertTrue(groupingBlock.contains("reasoning.isNullOrBlank()"))
    }
}
