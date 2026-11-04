package io.zer0.muse.crash

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * 安全模式标记的**可清除性**契约。
 *
 * 旧实现判"文件是否存在"，清除只有一条路：`File.delete()`。
 * 而 `delete()` 失败是**返回 false 不抛异常** —— 一旦删不掉（目录项被占用等），
 * 用户就**永远出不来安全模式**：点了"继续正常启动"，下次照旧进来，
 * 而且界面与日志都不会说为什么。
 *
 * 现在标记按内容判（旧版本写的本来就是 `"1"`，兼容），清除是"先删、删不掉改写 `0`"。
 * 这条分支用注入 `deleter` 来测：真造一个"删不掉的文件"跨平台不稳（只读位/占用都不可靠），
 * 而那恰恰是最要命的一支，不能因为没有稳定夹具就不测。
 */
class SafeModeMarkTest {

    private val dir: File = createTempDirectory("muse-safemode").toFile()
    private val flag get() = File(dir, "safe_mode.flag")

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `只有内容是一才算标记存在`() {
        flag.writeText(MuseCrashHandler.SAFE_MODE_MARK_ON)
        assertTrue("写着 1 才算要进安全模式", MuseCrashHandler.hasSafeModeMark(flag))

        flag.writeText(MuseCrashHandler.SAFE_MODE_MARK_OFF)
        assertFalse("改写为 0 之后不该再算", MuseCrashHandler.hasSafeModeMark(flag))

        flag.delete()
        assertFalse("文件不存在当然不算", MuseCrashHandler.hasSafeModeMark(flag))
    }

    @Test
    fun `旧版本带换行的标记仍然兼容`() {
        flag.writeText("1\n")
        assertTrue("按内容判不能把旧文件误判成没有标记，否则用户会跳过安全模式直接崩溃循环", MuseCrashHandler.hasSafeModeMark(flag))
    }

    @Test
    fun `正常情况先删文件`() {
        flag.writeText(MuseCrashHandler.SAFE_MODE_MARK_ON)

        assertEquals("deleted", MuseCrashHandler.clearSafeModeMark(flag, deleter = { f -> f.delete() }))
        assertFalse("删掉了就不该留文件", flag.exists())
    }

    @Test
    fun `删不掉时改写为零也要把标记清掉（卡在安全模式的回归）`() {
        flag.writeText(MuseCrashHandler.SAFE_MODE_MARK_ON)

        val outcome = MuseCrashHandler.clearSafeModeMark(flag, deleter = { false })

        assertEquals("该走回退改写：$outcome", "cleared", outcome)
        assertTrue("文件还在（没删动），但内容必须已失效", flag.exists())
        assertFalse("标记判定必须已经不再认它", MuseCrashHandler.hasSafeModeMark(flag))
    }

    @Test
    fun `删除动作抛异常同样走回退`() {
        flag.writeText(MuseCrashHandler.SAFE_MODE_MARK_ON)

        val outcome = MuseCrashHandler.clearSafeModeMark(
            flag,
            deleter = { throw java.io.IOException("device busy") },
        )

        assertEquals("异常不能把清除整件事带崩：$outcome", "cleared", outcome)
        assertFalse(MuseCrashHandler.hasSafeModeMark(flag))
    }

    @Test
    fun `标记本来就不存在时是幂等的`() {
        // 用户重复点"继续正常启动"、或从未进过安全模式
        assertEquals("deleted", MuseCrashHandler.clearSafeModeMark(flag, deleter = { false }))
    }
}
