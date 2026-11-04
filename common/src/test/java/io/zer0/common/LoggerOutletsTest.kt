package io.zer0.common

import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

/**
 * `Logger` 三份出口的端到端脱敏（§3 里那条"只能靠读代码"的证据缺口，本轮补上）。
 *
 * 之前只能在纯函数层测 `sanitizePii`，而真正的承诺是：
 * **Logcat、`muse.log`、app 的 `DebugLogStore`（会显示在 DebugScreen 并写进导出文件）
 * 三份出口拿到的都必须是脱敏视图** —— 少一份就是漏一份，而漏的那份恰恰最可能被带出设备。
 * 这只有跑真出口才验得出来，读代码读不出来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LoggerOutletsTest {

    private val apiKey = "sk-proj-AbCdEfGhIjKlMnOpQrStUvWxYz1234"
    private val phone = "13812345678"
    private val querySecret = "QUERYSECRET9999"

    private val capturedSink = mutableListOf<Triple<String, String, Throwable?>>()
    private val capturedMessages = mutableListOf<String>()

    @After
    fun tearDown() {
        Logger.sink = null
        Logger.enabled = true
        capturedSink.clear()
        capturedMessages.clear()
    }

    private fun newFailure(): Throwable {
        val root = IllegalStateException("上游拒绝: api_key=$apiKey")
        return java.io.IOException("请求失败 https://api.example/v1?token=$querySecret", root)
    }

    @Test
    fun `sink 出口拿到的是脱敏后的消息与因果链`() {
        Logger.sink = { level, tag, msg, throwable ->
            capturedMessages += "$level|$tag|$msg"
            capturedSink += Triple(level, tag, throwable)
        }

        Logger.w("Tunnel", "联系 手机 $phone 确认", newFailure())

        val line = capturedMessages.single()
        assertTrue("sink 的消息里仍带手机号：$line", !line.contains(phone))
        assertTrue("消息应留下类别标记而不是静默丢内容：$line", line.contains("[手机号]"))
        val throwable = capturedSink.single().third
        assertNotNull("throwable 要传给订阅方，否则 DebugScreen 看不到堆栈", throwable)
        val text = throwable!!.message.orEmpty() + throwable.stackTraceToString()
        assertTrue("sink 收到的 throwable 漏了密钥：$text", !text.contains(apiKey))
        assertTrue("sink 收到的 throwable 漏了 URL 凭据：$text", !text.contains(querySecret))
    }

    @Test
    fun `日志文件出口落盘的内容不含凭据但保留堆栈`() {
        val context = RuntimeEnvironment.getApplication()
        Logger.initFileLog(context)
        val file = File(context.cacheDir, "logs/muse.log")

        Logger.e("Tunnel", "手机号 $phone 的订单失败", newFailure())

        assertTrue("文件没生成，说明这条出口根本没被验到", file.exists())
        val content = file.readText()
        assertTrue("日志文件里仍有手机号（这份日志会被回捞导出）", !content.contains(phone))
        assertTrue("日志文件里仍有 api_key（同上）", !content.contains(apiKey))
        assertTrue("日志文件里仍有 URL 凭据（同上）", !content.contains(querySecret))
        // 脱敏不能把可排查性一起抹掉：堆栈帧与因果链都要在
        assertTrue("堆栈丢了：排查时看不到抛出点", content.contains("io.zer0.common.LoggerOutletsTest"))
        assertTrue("因果链文本要保留：$content", content.contains("Caused by:"))
        assertTrue("要留下类别标记，让人知道这里曾有敏感值", content.contains("[手机号]"))
    }

    @Test
    fun `Logcat 出口收到的 throwable 也是脱敏视图`() {
        // 不依赖"清空日志"这种全局状态（Robolectric 各版本 API 名还不一致）：
        // 用一个只在本用例出现的 tag，抓不到就说明出口没走到。
        val tag = "OutletsLogcat"

        Logger.e(tag, "key=PLAINLOGCATSECRET 出错了", newFailure())

        val entry = ShadowLog.getLogs().lastOrNull { it.tag == tag }
        assertNotNull("ShadowLog 没抓到 Logcat 调用，这条测试就是空的", entry)
        val thrown = entry!!.throwable
        assertNotNull("Logcat 要拿到 throwable（否则开发者看不到栈）", thrown)
        val text = thrown!!.message.orEmpty()
        assertTrue("Logcat 的 throwable 漏了密钥：$text", !text.contains(apiKey))
        assertTrue("Logcat 的 throwable 漏了 URL 凭据：$text", !text.contains(querySecret))
        assertTrue("Logcat 的 throwable 漏了消息里的凭据：$text", !text.contains("PLAINLOGCATSECRET"))
        assertTrue("原始异常类型名要留住（栈顶类名靠它定位）：$text", text.contains("IOException"))
        // 消息本身也脱敏
        assertTrue("Logcat 的 message 没脱敏：${entry.msg}", !entry.msg!!.contains("PLAINLOGCATSECRET"))
    }
}
