package io.zer0.muse.channel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong

/**
 * 收件箱里图片 base64 的生命周期。
 *
 * 收件箱是**整表落盘**（`channel_inbox.json`），每次 record / 领取 / 完成派发都把最多
 * [ChannelInbox.MAX_PENDING_ITEMS] 条记录重新序列化。图片 base64 是这条链路上唯一的大字段 ——
 * 它一旦留在历史条目里，之后每一次状态迁移都要重新搬运这份字节。
 *
 * 正确时机：图片在自动回复构造上下文时已经复制进 ChannelConversationStore，
 * 所以**终态**（已完成 / 已忽略）就该清掉；未完成（PENDING / PROCESSING / BLOCKED）
 * 必须保留 —— 重试、恢复、人工复核都还要用它。
 */
class ChannelInboxMediaLifecycleTest {

    private lateinit var directory: File
    private lateinit var inboxFile: File
    private lateinit var ledgerFile: File
    private lateinit var journal: ChannelInboxJournal
    private val nowMillis = AtomicLong(1_800_000_000_000L)

    /** 1KB 的假图片 base64（够大，能在文件文本里被察觉）。 */
    private val imageBase64 = "iVBORw0KGgo" + "A".repeat(1_000)

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("inbox-media-test").toFile()
        inboxFile = File(directory, "inbox.json")
        ledgerFile = File(directory, "events.json")
        journal = ChannelInboxJournal(nowMillis = { nowMillis.get() })
        journal.attach(inboxFile, ledgerFile)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }


    /** 全部分片 + 旧单文件的文本（迁移前后都能断言）。 */
    private fun persistedText(): String {
        val dir = File(directory, ChannelInbox.INBOX_DIR_NAME)
        val shards = dir.listFiles().orEmpty().filter { it.isFile }
        val legacy = inboxFile.takeIf { it.exists() }?.let { listOf(it) }.orEmpty()
        return (shards + legacy).joinToString(separator = "") { it.readText() }
    }
    private fun source(eventId: String) = ChannelInbox.Source(
        platform = "QQ",
        from = "user",
        channelId = "qq-a",
        eventId = eventId,
    )

    private fun imageInbound(eventId: String) = ChannelInbox.Inbound(
        platform = "QQ",
        from = "user",
        summary = "图片消息",
        mediaKind = "image",
        mediaBase64 = imageBase64,
        sourceChannelId = "qq-a",
        sourceEventId = eventId,
        dispatchId = "dispatch-$eventId",
        dispatchState = ChannelInbox.DispatchState.PENDING,
    )

    @Test
    fun `completed_dispatch_drops_image_bytes_from_inbox`() {
        journal.record(source("e1"), imageInbound("e1"))
        val claimed = journal.claimNextPending()!!
        assertEquals("派发中必须还留着头像/图片字节供构造视觉请求", imageBase64, claimed.mediaBase64)

        journal.completeDispatch(claimed.dispatchId, ignored = false)

        val stored = journal.snapshot().single()
        assertEquals(ChannelInbox.DispatchState.COMPLETED, stored.dispatchState)
        assertEquals("完成之后收件箱不得再持有图片字节", "", stored.mediaBase64)
        assertTrue(
            "落盘文件里不应再出现这份 base64（否则每次状态迁移都在重复搬运）",
            !persistedText().contains("A".repeat(200)),
        )
    }

    @Test
    fun `ignored_dispatch_also_drops_image_bytes`() {
        journal.record(source("e2"), imageInbound("e2"))
        val claimed = journal.claimNextPending()!!
        journal.completeDispatch(claimed.dispatchId, ignored = true)
        assertEquals("", journal.snapshot().single().mediaBase64)
    }

    @Test
    fun `blocked_dispatch_keeps_image_bytes_for_diagnosis`() {
        journal.record(source("e3"), imageInbound("e3"))
        val claimed = journal.claimNextPending()!!
        journal.blockDispatch(claimed.dispatchId, "agent_checkpoint_unreadable")

        val stored = journal.snapshot().single()
        assertEquals(ChannelInbox.DispatchState.BLOCKED, stored.dispatchState)
        assertEquals("人工复核可能还要看这张图，BLOCKED 必须保留", imageBase64, stored.mediaBase64)
    }

    @Test
    fun `pending_retry_keeps_image_bytes`() {
        journal.record(source("e4"), imageInbound("e4"))
        val claimed = journal.claimNextPending()!!
        journal.retryDispatch(claimed.dispatchId)

        val stored = journal.snapshot().single()
        assertEquals(ChannelInbox.DispatchState.PENDING, stored.dispatchState)
        assertEquals("重试还要重新构造请求，必须保留", imageBase64, stored.mediaBase64)
    }

    @Test
    fun `legacy_completed_entry_is_stripped_on_restore`() {
        // 模拟旧版本落盘：历史条目里带着 base64。
        val legacy = ChannelInbox.Inbound(
            platform = "QQ",
            from = "user",
            summary = "旧图片",
            mediaKind = "image",
            mediaBase64 = imageBase64,
            dispatchId = "old-1",
            dispatchState = ChannelInbox.DispatchState.COMPLETED,
        )
        inboxFile.writeText(
            kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(ChannelInbox.Inbound.serializer()),
                listOf(legacy),
            ),
        )

        val restored = ChannelInboxJournal(nowMillis = { nowMillis.get() })
        restored.attach(inboxFile, ledgerFile)

        assertEquals("", restored.snapshot().single().mediaBase64)
        assertTrue(
            "恢复后应立即把精简结果写回，避免下次启动再搬一遍",
            !persistedText().contains("A".repeat(200)),
        )
    }
}
