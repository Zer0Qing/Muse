package io.zer0.muse.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.2.1 Termux 通道:结果解析与引导文案的防御性单测(纯 JVM)。
 */
class TermuxChannelTest {

    @Test
    fun `标准结果包解析`() {
        val result = TermuxChannel.parseResultEntries(
            mapOf("stdout" to "hello", "stderr" to "", "exitCode" to 0, "err" to 0),
        )
        assertEquals(0, result.exitCode)
        assertEquals("hello", result.stdout)
        assertEquals("", result.stderr)
        assertNull(result.error)
    }

    @Test
    fun `缺失字段安全兜底`() {
        val result = TermuxChannel.parseResultEntries(emptyMap())
        assertEquals(-1, result.exitCode)
        assertEquals("", result.stdout)
        assertNull(result.error)
    }

    @Test
    fun `errmsg 优先作为错误`() {
        val result = TermuxChannel.parseResultEntries(
            mapOf("stdout" to "", "stderr" to "", "exitCode" to 1, "err" to 5, "errmsg" to "allow-external-apps not configured"),
        )
        assertEquals(1, result.exitCode)
        assertEquals("allow-external-apps not configured", result.error)
    }

    @Test
    fun `Termux 成功码 -1 不算错误`() {
        // Termux 的 Errno.ERRNO_SUCCESS 就是 -1，旧实现把它当失败，
        // 导致命令明明跑完了却报「Termux 服务错误码 -1」。
        val result = TermuxChannel.parseResultEntries(
            mapOf("stdout" to "hello", "stderr" to "", "exitCode" to 0, "err" to -1, "errmsg" to null),
        )
        assertEquals(0, result.exitCode)
        assertEquals("hello", result.stdout)
        assertNull(result.error)
    }

    @Test
    fun `errCode 非零而无 errmsg 时报错误码`() {
        val result = TermuxChannel.parseResultEntries(mapOf("err" to 7))
        assertTrue(result.error!!.contains("7"))
    }

    @Test
    fun `字符串数值兼容`() {
        val result = TermuxChannel.parseResultEntries(
            mapOf("exit_code" to "9", "errCode" to "0", "stdout" to "ok"),
        )
        assertEquals(9, result.exitCode)
        assertEquals("ok", result.stdout)
        assertNull(result.error)
    }

    @Test
    fun `未安装引导要点`() {
        val text = TermuxChannel.describe(TermuxChannel.Availability.NotInstalled)
        assertTrue(text.contains("Termux"))
        assertTrue(text.contains("安装"))
    }

    @Test
    fun `权限引导要点`() {
        val text = TermuxChannel.describe(TermuxChannel.Availability.PermissionMissing)
        assertTrue(text.contains("附加权限"))
    }

    @Test
    fun `未配置引导含 allow-external-apps`() {
        val text = TermuxChannel.describe(TermuxChannel.Availability.NotConfigured("boom"))
        assertTrue(text.contains("allow-external-apps"))
        assertTrue(text.contains("boom"))
    }
}
