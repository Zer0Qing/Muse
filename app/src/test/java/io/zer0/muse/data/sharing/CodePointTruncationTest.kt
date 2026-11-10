package io.zer0.muse.data.sharing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 截断不能把一个字符切成两半。
 *
 * 名字与简介会画到分享卡上、也可能写进文件。`take(n)` 数的是 UTF-16 单元，
 * emoji 占两个单元，恰好切在中间就留下半个代理对 —— 画出来是乱码方框，写进文件是非法字节。
 */
class CodePointTruncationTest {

    @Test
    fun `短文本原样返回`() {
        assertEquals("阿离", "阿离".takeCodePoints(24))
        assertEquals("", "".takeCodePoints(24))
    }

    @Test
    fun `超长按字截断`() {
        assertEquals("三个字", "三个字够长".takeCodePoints(3))
    }

    @Test
    fun `不会把 emoji 切成半个`() {
        // 😀 占两个 UTF-16 单元；按单元 take(1) 会得到半个代理对
        val name = "😀😀😀"
        val cut = name.takeCodePoints(1)
        assertEquals("应保留一个完整 emoji", "😀", cut)
        assertEquals("应是一个码点", 1, cut.codePointCount(0, cut.length))
        assertFalse("不能留下孤立的代理", Character.isHighSurrogate(cut.last()))
        assertTrue("结果必须是合法字符串", cut.toByteArray(Charsets.UTF_8).decodeToString() == cut)
    }

    @Test
    fun `混合文本按可见字符数截断`() {
        val mixed = "阿😀离😀远"
        assertEquals("阿😀离", mixed.takeCodePoints(3))
    }

    @Test
    fun `零或负数返回空串`() {
        assertEquals("", "任意内容".takeCodePoints(0))
        assertEquals("", "任意内容".takeCodePoints(-1))
    }

    @Test
    fun `批注这类要落库的正文 超长时也不切坏 emoji`() {
        // 批注正文会被持久化：切出半个代理对之后写进 JSON，读回来就是乱码，且无法修复。
        val long = "开头" + "😀".repeat(1200)
        val cut = long.takeCodePoints(2000)
        assertTrue("长度不该超过上限", cut.codePointCount(0, cut.length) <= 2000)
        assertEquals("必须是合法字符串", cut, cut.toByteArray(Charsets.UTF_8).decodeToString())
        assertFalse("不能以孤立高位代理结尾", Character.isHighSurrogate(cut.last()))
    }

    @Test
    fun `刚好等于上限时不截断`() {
        val exact = "😀😀"
        assertEquals(exact, exact.takeCodePoints(2))
    }
}
