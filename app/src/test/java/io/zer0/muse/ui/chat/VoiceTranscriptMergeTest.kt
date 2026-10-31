package io.zer0.muse.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [mergeTranscript] 的纯函数契约:本轮累积文本如何吸收一次 ASR 回调。
 *
 * ASR 回调既可能是「本会话到目前为止的完整文本」(会被整段重发),也可能只是新增的一段;
 * 语音对话还允许会话中途结束再重开,所以合并必须幂等且不丢字。
 */
class VoiceTranscriptMergeTest {

    @Test
    fun `empty incoming keeps accumulated`() {
        assertEquals("今天天气", mergeTranscript("今天天气", ""))
        assertEquals("今天天气", mergeTranscript("今天天气", "   "))
        assertEquals("", mergeTranscript("", ""))
    }

    @Test
    fun `empty accumulated takes incoming`() {
        assertEquals("今天天气", mergeTranscript("", "今天天气"))
        assertEquals("今天天气", mergeTranscript("  ", " 今天天气 "))
    }

    @Test
    fun `incoming starting with accumulated replaces it`() {
        // 服务端给了含旧内容的完整文本:整段替换,不重复拼接
        assertEquals("今天天气不错", mergeTranscript("今天天气", "今天天气不错"))
        assertEquals("今天天气", mergeTranscript("今天天气", "今天天气"))
    }

    @Test
    fun `accumulated starting with incoming keeps accumulated`() {
        // 服务端重发了旧文本(更短):不能把已经更长的内容缩短
        assertEquals("今天天气不错", mergeTranscript("今天天气不错", "今天天气"))
    }

    @Test
    fun `unrelated texts are appended without separator`() {
        // 中文不补分隔符:两段都换成了全新内容时直接接上
        assertEquals("今天天气不错明天呢", mergeTranscript("今天天气不错", "明天呢"))
        assertEquals("帮我查一下明天北京天气", mergeTranscript("帮我查一下", "明天北京天气"))
    }

    @Test
    fun `trailing overlap is collapsed once`() {
        // 会话被重开后 ASR 从头开始报:新会话首段与已累积内容首尾重叠时只保留一份
        assertEquals("今天天气不错", mergeTranscript("今天天气", "天气不错"))
        // 同一会话里中间结果在增长
        assertEquals("今天天气不错", mergeTranscript("今天天气不错", "不错"))
        // 英文按单词片段重合折叠
        assertEquals("hello world peace", mergeTranscript("hello world", "world peace"))
    }

    @Test
    fun `ascii word boundary gets a single space`() {
        assertEquals("hello world", mergeTranscript("hello", "world"))
        // 中文与英文相邻时不补空格
        assertEquals("好的ok", mergeTranscript("好的", "ok"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("今天天气不错", mergeTranscript("  今天天气 ", " 不错  "))
        assertEquals("今天天气", mergeTranscript(" 今天天气 ", "   "))
    }

    @Test
    fun `merging the same session text twice is idempotent`() {
        // 回调与 ASR 收尾状态是同一次识别的两个通道,先后到达不得重复拼接
        val once = mergeTranscript("今天天气", "不错呀")
        assertEquals("今天天气不错呀", once)
        assertEquals(once, mergeTranscript(once, "不错呀"))
        assertEquals(once, mergeTranscript(once, "今天天气不错呀"))
    }
}
