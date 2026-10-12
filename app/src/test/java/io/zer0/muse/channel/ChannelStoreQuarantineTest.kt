package io.zer0.muse.channel

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-CHANNEL-F4: 渠道存储恢复失败必须隔离坏文件,而非静默清零后覆盖。
 *
 * 原实现解码失败仅记日志、继续用空列表,后续写入会用新内容覆盖坏文件,
 * 原始现场丢失、无法诊断。项目已有 AtomicFileStore.quarantine 惯例。
 */
class ChannelStoreQuarantineTest {

    private fun source(name: String): String = java.io.File("src/main/java/io/zer0/muse/channel/$name").readText()

    @Test
    fun `inbox restore failure quarantines the file`() {
        val src = source("ChannelInbox.kt")
        assertTrue(
            "收件箱恢复失败必须隔离坏文件",
            src.contains("AtomicFileStore.quarantine(inboxFile"),
        )
    }

    @Test
    fun `conversation store restore failure quarantines the file`() {
        val src = source("ChannelConversationStore.kt")
        assertTrue(
            "对话历史恢复失败必须隔离坏文件",
            src.contains("AtomicFileStore.quarantine(target"),
        )
    }

    @Test
    fun `webhook ledger oversize quarantines instead of throwing`() {
        val src = source("WebhookEventDeduplicator.kt")
        assertTrue(
            "超容量账本应隔离重开,而非 require 抛错",
            src.contains("AtomicFileStore.quarantine(file, \"webhook_ledger_oversize\")"),
        )
        assertTrue(
            "不应再有 require(...) 容量抛错",
            !src.contains("require(loaded.size <= MAX_ENTRIES)"),
        )
    }
}
