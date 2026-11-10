package io.zer0.muse.crash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 崩溃日志脱敏的回归测试。
 *
 * 崩溃日志是要从真机导出来发给外人的（用户自己导出、或随反馈打包），
 * 里面出现一次凭据就等于凭据外泄一次。这里守住的是「哪些形状必须被抹掉」。
 *
 * 只测纯函数 [MuseCrashHandler.redactSensitive]，不碰 Android 上下文。
 */
class CrashLogRedactionTest {

    private fun redact(text: String): String = MuseCrashHandler.redactSensitive(text)

    @Test
    fun `URL 查询串里的 key 与 token 都要抹掉`() {
        val raw = "GET https://api.example.com/v1/chat?key=abcd1234EFGH&token=zzz999 failed"
        val out = redact(raw)
        assertFalse("查询串 key 的值不该留下: $out", out.contains("abcd1234EFGH"))
        assertFalse("查询串 token 的值不该留下: $out", out.contains("zzz999"))
        assertTrue("URL 骨架要保留，否则没法排查: $out", out.contains("api.example.com"))
    }

    @Test
    fun `x-api-key 请求头要抹掉`() {
        val raw = "Headers: {x-api-key: 9f8e7d6c5b4a3210abcd, content-type: application/json}"
        val out = redact(raw)
        assertFalse("x-api-key 的值不该留下: $out", out.contains("9f8e7d6c5b4a3210abcd"))
        assertTrue("头名要保留: $out", out.contains("x-api-key"))
    }

    @Test
    fun `Telegram 把令牌放在路径里，也要抹掉`() {
        val raw = "java.io.IOException: failed https://api.telegram.org/bot123456:AAH-someSecretToken_9/getUpdates"
        val out = redact(raw)
        assertFalse("路径令牌不该留下: $out", out.contains("AAH-someSecretToken_9"))
        assertTrue("域名与接口名要保留: $out", out.contains("getUpdates"))
    }

    @Test
    fun `PEM 私钥整块要抹掉`() {
        val raw = "cause: -----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA\n-----END RSA PRIVATE KEY-----"
        val out = redact(raw)
        assertFalse("私钥正文不该留下: $out", out.contains("MIIEowIBAAKCAQEA"))
    }

    @Test
    fun `普通堆栈文本不被误伤`() {
        val raw = "java.lang.IllegalStateException: session closed\n\tat io.zer0.muse.Foo.bar(Foo.kt:42)"
        val out = redact(raw)
        assertTrue("类名要保留: $out", out.contains("IllegalStateException"))
        assertTrue("堆栈帧要保留: $out", out.contains("Foo.kt:42"))
        assertTrue("消息文本要保留: $out", out.contains("session closed"))
    }
}
