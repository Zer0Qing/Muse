package io.zer0.muse.ui

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolCallInfo
import io.zer0.ai.core.UIMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 图片作品条提示词的预计算。
 *
 * 改前是「每个可见 item 各自向前扫一遍」，合计 O(n²) 且同一份参数被反复解析。
 * 改后列表外一次扫完，所以这里要钉住两件事：
 *  - 每条消息取到的提示词与逐条向前扫的结果完全一致（含「最近一次为空就不再往前找」）；
 *  - 每个生成调用只解析一次 JSON。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImageGenPromptIndexTest {

    // ── 用例数据 ────────────────────────────────────────────────────────

    private fun user(text: String) = UIMessage(role = MessageRole.USER, content = text)

    private fun assistant(text: String) = UIMessage(role = MessageRole.ASSISTANT, content = text)

    /** 工具调用仍在内存里（toolCalls 路径）。 */
    private fun genCallMessage(prompt: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        content = "",
        toolCalls = listOf(ToolCall(id = "call-1", name = "generate_image", arguments = """{"prompt":"$prompt"}""")),
    )

    /** 重启后回放的持久化路径（toolCallInfo 路径）。 */
    private fun genInfoMessage(arguments: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        content = "",
        toolCallInfo = ToolCallInfo(
            toolName = "generate_image",
            arguments = arguments,
            result = "ok",
            isSuccess = true,
        ),
    )

    /** 改前的实现：第 index 条消息向上找最近一次 generate_image，逐条独立扫描。 */
    private fun legacyPrompts(messages: List<UIMessage>): Map<String, String?> = messages.indices.associate { index ->
        val genCall =
            messages.take(index + 1).asReversed().firstNotNullOfOrNull { m ->
                val fromCalls = m.toolCalls?.firstOrNull { it.name == "generate_image" }
                val fromInfo = m.toolCallInfo?.takeIf { it.toolName == "generate_image" }
                when {
                    fromCalls != null -> fromCalls.arguments
                    fromInfo != null -> fromInfo.arguments
                    else -> null
                }
            }
        val prompt =
            genCall
                ?.let { args -> runCatching { JSONObject(args).optString("prompt") }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
                ?.let { if (it.length > 60) it.take(60) + "…" else it }
        messages[index].id.toString() to prompt
    }

    private fun promptOf(args: String): String? =
        runCatching { JSONObject(args).optString("prompt") }.getOrNull()

    // ── 用例 ────────────────────────────────────────────────────────────

    @Test
    fun `precomputed prompts equal the per message forward scan`() {
        val messages = listOf(
            user("画一只猫"),
            genCallMessage("第一张猫"),
            assistant("好的，马上来"),
            genInfoMessage("""{"prompt":"第二张狗"}"""),
            assistant("完成"),
        )

        var parseCount = 0
        val actual = imageGenPromptsByMessage(messages) { args ->
            parseCount++
            promptOf(args)
        }

        assertEquals("结果必须与逐条向前扫一致", legacyPrompts(messages), actual)
        assertEquals("两次生成调用只该解析两次 JSON（改前是每条消息各解析一次）", 2, parseCount)

        val ids = messages.map { it.id.toString() }
        assertNull("第一条消息之前没有任何生成调用", actual[ids[0]])
        assertEquals("第一张猫", actual[ids[1]])
        assertEquals("向后沿用最近一次的提示词", "第一张猫", actual[ids[2]])
        assertEquals("第二张狗", actual[ids[3]])
        assertEquals("第二张狗", actual[ids[4]])
    }

    @Test
    fun `blank prompt yields null without scanning further back`() {
        val messages = listOf(
            genCallMessage("第一个"),
            genInfoMessage("""{"prompt":"   "}"""),
            assistant("结束"),
        )

        var parseCount = 0
        val actual = imageGenPromptsByMessage(messages) { args ->
            parseCount++
            promptOf(args)
        }

        assertEquals("最近一次提示词为空时整体为 null，不回退到更早那次", legacyPrompts(messages), actual)
        assertEquals(2, parseCount)
        val ids = messages.map { it.id.toString() }
        assertEquals("第一个", actual[ids[0]])
        assertNull(actual[ids[1]])
        assertNull(actual[ids[2]])
    }

    @Test
    fun `long prompt is truncated exactly like before`() {
        val long = "长".repeat(80)
        val messages = listOf(genCallMessage(long), assistant("好"))

        val actual = imageGenPromptsByMessage(messages)
        val ids = messages.map { it.id.toString() }

        assertEquals("超长提示词截到 60 字并加省略号", "长".repeat(60) + "…", actual[ids[0]])
        assertEquals(legacyPrompts(messages), actual)
    }

    @Test
    fun `messages without any generation keep a null prompt`() {
        val messages = listOf(user("你好"), assistant("在的"))

        var parseCount = 0
        val actual = imageGenPromptsByMessage(messages) { args ->
            parseCount++
            promptOf(args)
        }

        assertEquals("没有生成调用就不该解析任何参数", 0, parseCount)
        assertEquals(
            messages.map { it.id.toString() }.associateWith { null },
            actual,
        )
    }
}
