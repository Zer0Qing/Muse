package io.zer0.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Locale

/**
 * 文件大小口径（`:common` 待查清单最后一件）。
 *
 * 类注释已经写了统一口径的来由（以前全仓 8 份实现，同一份数据在不同页面显示不一样），
 * 但那条"四舍五入可能把 1023.96 MB 显示成 1024 MB"没人守 —— 本轮补上并修掉。
 */
class FileSizeFormatterTest {

    @Test
    fun `四舍五入推到一千零二十四要进位到下一级单位`() {
        // 1_073_706_240 B = 1023.96 MB → 一位小数会圆成 "1024.0"
        // 以前直接输出 "1024 MB"，用户看的就是"为什么不是 1 GB"
        assertEquals("1 GB", FileSizeFormatter.format(1_073_706_240L))

        // 同理 KB → MB：临界值是 1023.95 KB，即 1_048_525 B
        // （我第一版拿 1_048_315 B 当例子，那其实是 1023.745 KB → "1023.7 KB"，
        //  不该进位 —— 是测试算错，不是实现错）
        assertEquals("1 MB", FileSizeFormatter.format(1_048_525L))
    }

    @Test
    fun `口径与注释一致`() {
        assertEquals("0 B", FileSizeFormatter.format(0L))
        assertEquals("0 B", FileSizeFormatter.format(-5_000L))
        assertEquals("512 B", FileSizeFormatter.format(512L)) // B 级不带小数
        assertEquals("1.5 KB", FileSizeFormatter.format(1_536L))
        assertEquals("1 MB", FileSizeFormatter.format(1_048_576L)) // 整数不留 .0
        assertEquals("1 TB", FileSizeFormatter.format(1_099_511_627_776L))
    }

    @Test
    fun `单位上限是 TB 超过就留在 TB 而不是断言失败`() {
        // 5 PB：注释明写"单位到 TB"，所以这里应给出大数字而不是凭空冒出一个 PB
        val huge = FileSizeFormatter.format(5_629_499_534_213_120L)

        assertEquals("5120 TB", huge)
    }

    @Test
    fun `小数点永远是点不随系统区域变成逗号`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // de-DE 用逗号做小数点

            val out = FileSizeFormatter.format(1_536L)

            assertEquals("1.5 KB", out)
            assertFalse("出现区域相关的逗号小数点：$out", out.contains(','))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `指定零位小数时不留下多余的小数点`() {
        val out = FileSizeFormatter.format(1_536L, decimals = 0)

        assertEquals("2 KB", out)
        assertFalse(out.contains('.'))
    }
}
