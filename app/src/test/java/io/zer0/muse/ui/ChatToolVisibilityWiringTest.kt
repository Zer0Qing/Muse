package io.zer0.muse.ui

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.4.5 fix 回归: 「显示工具调用过程」开关必须同时管住两条渲染路径。
 *
 * 背景:开关关闭时 ChatScreen 把 taskCard 置 null,若 MessageBubble 内部那条
 * `toolInfo != null` 分支不受控,渲染就会落到它上面,工具卡片照旧弹出
 * (用户实机反馈:关掉开关仍看到「调用了工具」卡片)。
 */
class ChatToolVisibilityWiringTest {

    @Test
    fun `tool detail preference is consumed by chat rendering`() {
        val source = readChatSource("ChatScreen.kt")
        assertTrue(source.contains("state.chatPreferences.showToolCallDetails"))
        assertTrue(source.contains("if (showToolCallDetails)"))
        // ChatScreen 必须把开关透传给 MessageBubble,否则气泡内路径仍然不受控。
        assertTrue(source.contains("showToolCallDetails = showToolCallDetails"))
    }

    @Test
    fun `message bubble gates its own tool call card behind the preference`() {
        val bubble = readChatSource("MessageBubble.kt")
        assertTrue(
            "MessageBubble 需要接收 showToolCallDetails 参数",
            bubble.contains("showToolCallDetails: Boolean"),
        )
        assertTrue(
            "气泡内孤立工具卡必须受开关门控",
            bubble.contains("toolPayloadShowing(toolInfo.toolName, showToolCallDetails)"),
        )
        // v2.6.0: show_card 是内容卡,豁免开关;门控函数自身仍需保留开关判断。
        assertTrue(
            "工具卡门控函数必须含开关判断",
            bubble.contains("showToolCallDetails || toolName == \"show_card\""),
        )
    }

    @Test
    fun `plan card is hidden when an execution task card already owns the same turn`() {
        val source = readChatSource("MessageBubble.kt")
        val planBlock = source.substringAfter("if (agentPlan != null)")
            .substringBefore("} // closes AI bubble Surface Column")
        assertTrue(planBlock.contains("taskCard == null"))
        assertTrue(planBlock.contains("msg.toolCallInfo == null"))
        assertTrue(planBlock.contains("msg.toolCalls.isNullOrEmpty()"))
    }

    private fun readChatSource(fileName: String): String {
        val candidates =
            listOf(
                Path.of("src/main/java/io/zer0/muse/ui/$fileName"),
                Path.of("app/src/main/java/io/zer0/muse/ui/$fileName"),
            )
        val path = candidates.firstOrNull(Files::exists)
            ?: error("Unable to locate $fileName from ${System.getProperty("user.dir")}")
        return Files.newBufferedReader(path).use { it.readText() }
    }
}
