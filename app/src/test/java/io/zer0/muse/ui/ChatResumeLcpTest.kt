package io.zer0.muse.ui

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-11 / C-12: 断点续传去重与工具模型路由的纯函数单元测试。
 *
 * 覆盖的纯函数:
 *  - [longestCommonPrefix]: 续传去重(跳过与已显示内容重叠的最长公共前缀)。
 *  - [canUseToolModelForRound]: 工具轮是否可用 toolModel(历史含图而 toolModel 无视觉时回退主模型)。
 *
 * 均为无 Android runtime 依赖的纯 Kotlin 逻辑(JUnit4)。
 */
class ChatResumeLcpTest {

    // ── longestCommonPrefix ────────────────────────────────────────────────

    @Test
    fun `identical strings share full length`() {
        assertEquals(11, longestCommonPrefix("hello world", "hello world"))
    }

    @Test
    fun `prefix differs returns common prefix length`() {
        // "hello " 共 6 字符(h/e/l/l/o/空格)重合,之后 w vs t 分叉
        assertEquals(6, longestCommonPrefix("hello world", "hello there"))
    }

    @Test
    fun `no overlap returns zero`() {
        assertEquals(0, longestCommonPrefix("abc", "xyz"))
    }

    @Test
    fun `empty strings return zero`() {
        assertEquals(0, longestCommonPrefix("", "hello"))
        assertEquals(0, longestCommonPrefix("hello", ""))
        assertEquals(0, longestCommonPrefix("", ""))
    }

    @Test
    fun `one is prefix of other returns shorter length`() {
        assertEquals(5, longestCommonPrefix("hello", "hello world"))
        assertEquals(5, longestCommonPrefix("hello world", "hello"))
    }

    @Test
    fun `chinese text overlapped prefix measured in code units`() {
        // "今天天" = 3 个字符,后续 "气" 与 "雨" 不同 → 公共前缀取到索引 3
        assertEquals(3, longestCommonPrefix("今天天气很好", "今天天下雨"))
    }

    @Test
    fun `chinese identical shares full length`() {
        // "你好世界你好世界哈" = 9 个汉字,相同字符串公共前缀为全长
        assertEquals(9, longestCommonPrefix("你好世界你好世界哈", "你好世界你好世界哈"))
    }

    @Test
    fun `rewritten prefix after overlap returns divergence point`() {
        // "abcdefgh" 被改写为 "abcdXefgh":前 4 个字符仍重合,第 5 个 (e vs X) 开始分叉
        assertEquals(4, longestCommonPrefix("abcdefgh", "abcdXefgh"))
    }

    // ── canUseToolModelForRound ────────────────────────────────────────────

    private fun model(supportsVision: Boolean): Model =
        Model(id = "m-" + (if (supportsVision) "vision" else "text"), providerId = "p1", supportsVision = supportsVision)

    private fun userMsgWithImage(content: String = "看图"): UIMessage =
        UIMessage(role = MessageRole.USER, content = content, imageBase64List = listOf("aGVsbG8="))

    @Test
    fun `vision-capable tool model is usable regardless of history images`() {
        val history = listOf(userMsgWithImage())
        assertTrue(canUseToolModelForRound(history, model(supportsVision = true)))
    }

    @Test
    fun `text-only tool model is usable when history has no images`() {
        val history = listOf(
            UIMessage(role = MessageRole.USER, content = "帮我计算下"),
        )
        assertTrue(canUseToolModelForRound(history, model(supportsVision = false)))
    }

    @Test
    fun `text-only tool model must fall back when history carries an image`() {
        val history = listOf(userMsgWithImage())
        assertFalse(canUseToolModelForRound(history, model(supportsVision = false)))
    }

    @Test
    fun `empty history is always usable`() {
        assertTrue(canUseToolModelForRound(emptyList(), model(supportsVision = false)))
        assertTrue(canUseToolModelForRound(emptyList(), model(supportsVision = true)))
    }
}
