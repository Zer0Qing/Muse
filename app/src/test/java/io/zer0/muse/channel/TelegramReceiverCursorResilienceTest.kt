package io.zer0.muse.channel

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-CHANNEL-F2: Telegram 接收循环的本地游标读取不得让 job 永久死亡。
 *
 * 原实现 loadOffset 里 `error("Telegram offset 文件格式无效")` 在循环外抛出,
 * offset 文件一旦损坏,接收 job 每次启动即死且无重启兜底 —— 渠道永久失联。
 */
class TelegramReceiverCursorResilienceTest {

    private fun source(): String = java.io.File("src/main/java/io/zer0/muse/channel/TelegramReceiver.kt").readText()

    @Test
    fun `corrupt offset file no longer throws`() {
        val src = source()
        assertTrue(
            "loadOffset 不应再用 error() 抛异常终止 job",
            !src.contains("error(\"Telegram offset 文件格式无效\")"),
        )
        assertTrue(
            "损坏 offset 应记录告警并丢弃游标",
            src.contains("Telegram offset 文件损坏"),
        )
    }

    @Test
    fun `cursor initialization failure retries instead of exiting loop`() {
        val src = source()
        assertTrue(
            "游标初始化必须包 runCatching,失败退避重试而非退出循环",
            src.contains("runCatching { TelegramCursor(config.id, loadOffset(config.id)) }"),
        )
    }
}
