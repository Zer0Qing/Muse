package io.zer0.muse.channel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Telegram 轮询失败文本的契约：**要留痕，但不能把机器人令牌一起留进日志**。
 *
 * 两件事各自对应一种真实坏法：
 *  - 不记日志 → 「用户从 Telegram 发消息永远没反应」在线上无从查起；
 *  - 直接记 `error.message` → Telegram 的报错常带请求 URL
 *    `/bot<数字>:<令牌>/getUpdates`，而 `Logger` 的脱敏只认 `token=`、`?key=` 这类**查询串**形状，
 *    路径里的令牌它不认 —— `muse.log` 是要从真机导出的，等于把机器人钥匙交出去。
 */
class TelegramPollGuardTest {

    @Test
    fun `失败文本带上渠道与原因`() {
        val text = telegramPollIssue("tg-work", IllegalStateException("connection reset"))

        assertTrue("要点明是哪个渠道：$text", text.contains("tg-work"))
        assertTrue("要带原因：$text", text.contains("connection reset"))
    }

    @Test
    fun `URL 路径里的机器人令牌必须被隐去`() {
        val leaky = "HTTP 401: https://api.telegram.org/bot123456789:AAHfakeTOKEN-abcdefghij/getUpdates failed"

        val text = telegramPollIssue("tg-1", IllegalStateException(leaky))

        assertFalse("令牌段还留在日志里：$text", text.contains("AAHfakeTOKEN-abcdefghij"))
        assertFalse("连数字 id 一起隐去：$text", text.contains("123456789:"))
        assertTrue("但要保留可定位的线索（哪个接口失败）：$text", text.contains("getUpdates"))
        assertTrue("要明确写出已隐藏：$text", text.contains("bot<已隐藏>"))
    }

    @Test
    fun `原因过长要截断而不刷屏`() {
        val huge = "x".repeat(5_000)

        val text = telegramPollIssue("tg-2", IllegalStateException(huge))

        // 前缀（含渠道名）本身要留着，所以只需断正文明显被截短
        assertTrue("正文应被截断：长度 ${text.length}", text.length < 500)
    }

    @Test
    fun `异常没有消息时也不能写出空日志`() {
        val noMessage = NullPointerException()
        val textNull = telegramPollIssue("tg-3", noMessage)
        assertTrue("退回类名：$textNull", textNull.contains("NullPointerException"))

        val textNone = telegramPollIssue("tg-4", null)
        assertTrue("连异常都没有时也要有可读说明：$textNone", textNone.contains("未知错误"))
    }

    /**
     * 接线守卫：这个函数必须真的被 `pollOnce` 用在失败路径上。
     *
     * 纯函数测得再好用，调用点没接上就等于什么都没修 ——
     * 而且这条失败路径需要真实网络故障才能触发，行为测试造不出来，
     * 所以按仓库既有做法锁一句代码结构（同 `ScreenReadCompletenessWiringTest`）。
     */
    @Test
    fun `轮询实现真的把失败交给了这个函数`() {
        val src = readSource("channel/TelegramReceiver.kt")

        assertTrue("pollOnce 里必须对 getUpdates 的结果记日志", src.contains(".onFailure { e -> Logger.w(TAG, telegramPollIssue("))
        assertFalse(
            "不允许再退回成不带日志的 getOrNull() ?: return（那正是本轮修掉的静默）",
            Regex("""getUpdates\([^)]*\)\.getOrNull\(\)\s*\n?\s*\?: return""").containsMatchIn(src),
        )
    }

    private fun readSource(relative: String): String {
        val candidates = listOf(
            File("src/main/java/io/zer0/muse/$relative"),
            File("app/src/main/java/io/zer0/muse/$relative"),
        )
        return requireNotNull(candidates.firstOrNull { it.exists() }) {
            "找不到 $relative，工作目录=${File(".").absolutePath}"
        }.readText()
    }
}
