package io.zer0.muse.miniphone.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 语音转写往输入框里追加的规则（小手机 P2b 第四批）。
 *
 * 规则本身一句话，但错法很伤："框里已有内容就隔一个空格，不覆盖用户打了一半的字" ——
 * 一旦写成覆盖，用户一边打字一边补一句语音，打好的字会整段消失。
 */
class MiniPhoneInputBarRulesTest {

    @Test
    fun `框是空的 转写结果直接放进去不带前导空格`() {
        assertEquals("今天开会", appendTranscript("", "今天开会"))
        assertEquals("今天开会", appendTranscript("   ", "  今天开会 \n"))
    }

    @Test
    fun `已有内容时追加而不是覆盖`() {
        assertEquals("下午三点 今天开会", appendTranscript("下午三点", "今天开会"))
    }

    @Test
    fun `拼接处永远只有一个空格`() {
        // 用户手打的尾部空白 + 转写自带的空白，叠起来最容易变成"词中间两个空格"
        val once = appendTranscript("下午三点   ", "  今天开会")
        assertEquals("下午三点 今天开会", once)
        val twice = appendTranscript(once, "  记得带电脑 ")
        assertEquals("下午三点 今天开会 记得带电脑", twice)
        assertEquals(false, twice.contains("  "))
    }

    @Test
    fun `空白识别结果不动输入框`() {
        // 没听清时不该把用户的话改一个字（连尾部空白都不动，避免光标跳动）
        assertEquals("下午三点", appendTranscript("下午三点", ""))
        assertEquals("下午三点", appendTranscript("下午三点", "   \n "))
        assertEquals("", appendTranscript("", "   "))
    }
}
