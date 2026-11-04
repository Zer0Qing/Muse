package io.zer0.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文本截断（`:common` 体检第一处）。
 *
 * 这个工具守着上下文压缩与工具输出预览的预算（`MAX_MSG_CHARS` 1500、
 * `TOOL_RESULT_PREVIEW_CHARS` 4096、`preview(text, limit)` 的 limit 由调用方任意给），
 * 而它此前**一行测试都没有**。本轮修的两个问题都只有测试能钉住：
 *
 *  1. `tail` 保留 `maxLength` 个字符后又拼上省略标记 → **每次必然超上限**；
 *     `headTail` 在小预算下也超。而"截断后长度 ≤ maxLength"是调用方唯一能依赖的契约。
 *  2. 类注释写"按**字节**切分"，实现与参数文档都是按 UTF-16 char —— 过期注释会让人
 *     把 4096 当 4KB 去卡网络帧/DB 列/通知长度。
 */
class TextTruncationTest {

    /**
     * 一段既长又含 emoji 的样本。
     *
     * 只能整份整份地拼：`setLength(n)` 那种硬截会把 emoji 切成半个，
     * 于是**输入本身就是坏字符串**，测出来的"实现留下孤立代理"是夹具的错，不是实现的错
     * （我第一次就这么假红了）。
     */
    private fun longText(minChars: Int): String = buildString {
        val pool = "结果摘要😀结论🎉日志行末尾报错"
        while (length < minChars) append(pool)
    }

    @Test
    fun `截断后的长度永不超过上限（headTail）`() {
        // 小预算正是以前会破的区间：标记自己就占五六十个字
        (20..600 step 7).forEach { limit ->
            val r = TextTruncation.headTail(longText(limit * 3), limit)
            assertTrue("上限 $limit 时结果 ${r.text.length} 超了", r.text.length <= limit)
        }
    }

    @Test
    fun `截断后的长度永不超过上限（tail）`() {
        (20..600 step 7).forEach { limit ->
            val r = TextTruncation.tail(longText(limit * 3), limit)
            assertTrue("tail 上限 $limit 时结果 ${r.text.length} 超了", r.text.length <= limit)
        }
    }

    @Test
    fun `tail 以前每次都超上限——这条是回归钉子`() {
        // 旧实现：takeLast(maxLength) 之后再拼约 55 字的标记 → 120 变 175
        val r = TextTruncation.tail(longText(900), 120)
        assertTrue("必须仍然截了", r.truncated)
        assertTrue("结果 ${r.text.length} 应 ≤ 120", r.text.length <= 120)
        val original = longText(900)
        assertTrue("尾部内容要保住", r.text.endsWith(original.takeLast(4)))
    }

    @Test
    fun `标记放不下时退化为纯截断而不是硬塞超限`() {
        val r = TextTruncation.tail(longText(500), 8)
        assertTrue(r.truncated)
        // 不写死成 8：对齐代理对时可能少留一个 char，契约是"不超过上限"
        assertTrue("实际 ${r.text.length} 应 ≤8", r.text.length <= 8)
        assertTrue("确实丢了内容", r.omittedLength > 0)
        assertFalse("这么小的预算里不该出现说明性标记", r.text.contains("已省略"))
    }

    @Test
    fun `切分不切断代理对`() {
                listOf(3, 5, 7, 9, 11, 13).forEach { limit ->
            val head = TextTruncation.headTail(longText(limit * 8), limit)
            assertTrue("头尾截断都不该留下孤立代理：$limit", head.text.hasNoDanglingSurrogate())
            val t = TextTruncation.tail(longText(limit * 8), limit)
            assertTrue("尾部截断不该留下孤立代理：$limit", t.text.hasNoDanglingSurrogate())
        }
    }

    @Test
    fun `未超限与非法上限都原样返回`() {
        val short = TextTruncation.headTail("很短", 100)
        assertFalse(short.truncated)
        assertEquals("很短", short.text)

        val zero = TextTruncation.headTail("任意内容", 0)
        assertFalse("上限 ≤0 表示不截，不该返回空串", zero.truncated)
        assertEquals("任意内容", zero.text)

        val tailZero = TextTruncation.tail("任意内容", -1)
        assertEquals("任意内容", tailZero.text)
    }

    @Test
    fun `省略量口径诚实且头尾不重复同一段`() {
        val original = longText(2000)
        val r = TextTruncation.headTail(original, 400)
        assertTrue(r.truncated)
        assertEquals("原文长度按 String.length 计", original.length, r.originalLength)
        assertTrue("必须真省略了内容", r.omittedLength > 0)

        // 标记里写的数字必须等于实际省略量（模型/用户靠这个数字判断信息量）
        val stated = Regex("已省略 (\\d+) 字").find(r.text)!!.groupValues[1].toInt()
        assertEquals("标记数字与 Result.omittedLength 必须同源", r.omittedLength, stated)

        // 头是头的开头、尾是尾的结尾，且中间被替换掉 → 不会是同一段贴两遍
        assertTrue(r.text.startsWith(original.take(20)))
        assertTrue(r.text.endsWith(original.takeLast(20)))
    }

    @Test
    fun `长度是 UTF-16 char 口径（emoji 算 2 个）`() {
        // 把口径写死成测试：注释以前谎称"按字节"，现在必须能一眼看出真实单位
        val r = TextTruncation.headTail("😀😀😀", 100)
        assertEquals(6, r.originalLength)

        val over = TextTruncation.headTail("😀😀😀", 4)
        assertTrue("emoji 边界不能切出孤立代理", over.text.hasNoDanglingSurrogate())
        assertTrue(over.text.length <= 4)
    }

    private fun String.hasNoDanglingSurrogate(): Boolean {
        forEachIndexed { i, c ->
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= length) return false
                if (!Character.isLowSurrogate(this[i + 1])) return false
            }
            if (Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(this[i - 1]))) return false
        }
        return true
    }
}
