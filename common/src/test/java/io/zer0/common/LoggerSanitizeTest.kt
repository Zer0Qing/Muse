package io.zer0.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志脱敏（`:common` 体检第三处）。
 *
 * `Logger` 的注释承诺"避免敏感信息输出到 Logcat 或日志文件"，但本轮之前它只做了一半：
 * `msg` 过 `sanitizePii`，**`Throwable` 原样交出去** —— 而 `muse.log` 是要从真机回捞、
 * 导出到设备之外的。本仓库异常消息里常见完整 URL（含 `?key=…`）与上游错误体。
 *
 * 另外，`sanitizePii` 以前是 `private`，同模块测试也读不到 —— 这就是这组正则
 * 一直没被钉住的原因。现在放开为 `internal`（不是 public，不扩大对外面）。
 */
class LoggerSanitizeTest {

    @Test
    fun `个人标识被替换成类别标记`() {
        // (原句, 期望出现的类别标记, 必须消失的那段内容)
        // 第三项必须写死：以前我用"取原文里的数字"当判据，邮箱样本一个数字都没有，
        // contains("") 恒真 —— 那条断言等于没断。
        val samples = listOf(
            Triple("联系 13812345678 处理", "[手机号]", "13812345678"),
            Triple("发到 user@example.com 了", "[邮箱]", "user@example.com"),
            Triple("身份证 110101199003074571", "[身份证]", "110101199003074571"),
            Triple("卡号 6222021234567890123", "[银行卡]", "6222021234567890123"),
            Triple("回源 192.168.100.200 失败", "[IP]", "192.168.100.200"),
        )

        samples.forEach { (input, marker, secret) ->
            val out = Logger.sanitizePii(input)
            assertTrue("「$input」应被脱敏为 $marker，实际：$out", out.contains(marker))
            assertFalse("原文仍可在日志里读到：$out", out.contains(secret))
        }
    }

    @Test
    fun `凭据三种写法都要盖住`() {
        // 这三组形状来自 v1.114 那次修复的注释：以前只有 token|password|apikey 且要求紧跟 = 或 :
        val header = Logger.sanitizePii("Authorization: Bearer sk-proj-abcdef123456")
        assertFalse("Bearer 后的密钥漏了：$header", header.contains("sk-proj-abcdef123456"))

        val kv = Logger.sanitizePii("""{"access_token":"zzz-secret-9999","refresh_token":"yyy-8888"}""")
        assertFalse("JSON 里的 access_token 漏了：$kv", kv.contains("zzz-secret-9999"))
        assertFalse("JSON 里的 refresh_token 漏了：$kv", kv.contains("yyy-8888"))

        val url = Logger.sanitizePii("https://api.example/v1/chat?key=SECRET123&model=x")
        assertFalse("URL query 的 key 漏了：$url", url.contains("SECRET123"))

        val bareKey = Logger.sanitizePii("请求参数 key=PLAINSECRET value=1")
        assertFalse("纯文本 key=… 没被脱敏：$bareKey", bareKey.contains("PLAINSECRET"))

        val wordKey = Logger.sanitizePii("cache monkey=keepme sort=ok")
        assertTrue("普通词不该被吃掉（monkey）：$wordKey", wordKey.contains("keepme"))
    }

    @Test
    fun `正常内容不被误改`() {
        // 误伤会让日志变成没法读的东西：这条是"别把普通数字都当卡号"的下限守护
        val benign = "模型 gpt-4o-mini 处理 1500 tokens 用时 3.2 秒 (attempt 2/3)"

        assertEquals(benign, Logger.sanitizePii(benign))
    }

    @Test
    fun `throwable 的消息与因果链都脱敏但堆栈保留`() {
        val root = IllegalStateException("上游拒绝: api_key=sk-live-LEAKME")
        val outer = java.io.IOException("请求失败 https://api.example/v1?key=QUERYSECRET", root)

        val safe = Logger.sanitizedThrowable(outer)

        assertFalse("顶层消息漏了：${safe.message}", safe.message!!.contains("sk-live-LEAKME"))
        assertFalse("cause 消息漏了：${safe.message}", safe.message!!.contains("QUERYSECRET"))
        assertTrue("因果链要以文本形式留住，别把排查信息一起丢掉", safe.message!!.contains("Caused by:"))
        assertTrue("调用栈要保住", safe.stackTrace.isNotEmpty())
        assertEquals(
            "栈顶应当还是抛出点这个类",
            outer.stackTrace.first().className,
            safe.stackTrace.first().className,
        )
    }

    @Test
    fun `不挂回原始 cause 以免又被原样打印`() {
        val outer = java.io.IOException("失败 key=AAA", IllegalStateException("内层 key=BBB"))

        val safe = Logger.sanitizedThrowable(outer)

        // Android 打印 throwable 时会连 cause 一起打；挂回去等于让未脱敏那条重新出现
        assertEquals(null, safe.cause)
        assertTrue(safe.message!!.contains("BBB") == false)
    }

    @Test
    fun `超长因果链有上限不至于把日志撑爆`() {
        var t: Throwable = IllegalStateException("最底层 key=DEEP")
        repeat(20) { depth -> t = RuntimeException("层 $depth", t) }

        val safe = Logger.sanitizedThrowable(t)
        val caused = Regex("Caused by: ").findAll(safe.message!!).count()

        // 引用生产常量，不在测试里抄第二份数字
        assertTrue("展开层数应受限，实际 caused=$caused", caused <= Logger.MAX_CAUSE_DEPTH - 1)
        assertFalse(safe.message!!.contains("DEEP"))
    }

}
