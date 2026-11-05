package io.zer0.muse.channel

import io.zer0.common.ProcessWriteGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong

/**
 * v2.6.23(C2): 去抖合批领取(journal 层)。
 *
 * AutoReply 的文本拼接与派发记账在 [ChannelAutoReply] 侧,
 * 本测试钉的是 journal 的批量语义本身:
 *  - 窗口未满:一条都不领取,队头 retryAt 推到「最后一句 + 窗口」;
 *  - 窗口已过:同源连发按时间序整批领取,封顶 maxBatch;
 *  - 不同渠道/不同来话者/媒体消息不被吸收;
 *  - 领取后各条独立 PROCESSING,互不影响后续记账。
 */
class ChannelDebounceClaimTest {
    private lateinit var directory: File
    private lateinit var inboxFile: File
    private lateinit var ledgerFile: File
    private lateinit var journal: ChannelInboxJournal
    private val nowMillis = AtomicLong(1_800_000_000_000L)

    private val base = 1_800_000_000_000L

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("channel-debounce").toFile()
        inboxFile = File(directory, "inbox.json")
        ledgerFile = File(directory, "events.json")
        journal = ChannelInboxJournal(nowMillis = { nowMillis.get() })
        journal.attach(inboxFile, ledgerFile)
    }

    @After
    fun tearDown() {
        ProcessWriteGate.end()
        directory.deleteRecursively()
    }

    private fun record(channel: String, from: String, text: String, at: Long, media: String = "") {
        val source = ChannelInbox.Source("QQ", from, channel, "ev-$channel-$from-$at")
        val accepted = journal.record(
            source,
            ChannelInbox.Inbound(
                platform = source.platform,
                from = from,
                summary = text,
                sourceChannelId = channel,
                sourceEventId = source.eventId,
                dispatchId = "d-$channel-$from-$at",
                // Inbound 的 dispatchState 默认是 COMPLETED(旧 JSON 兼容不重放),测试必须显式 PENDING
                dispatchState = ChannelInbox.DispatchState.PENDING,
                timestamp = at,
                mediaKind = media,
            ),
        )
        assertTrue("record 应受理 $channel/$from/$at", accepted)
    }

    @Test
    fun `窗口未满时一条都不领取并推迟队头`() {
        record("qq-a", "user1", "第一句", base)
        record("qq-a", "user1", "第二句", base + 1_000)
        nowMillis.set(base + 1_500)

        val batch = journal.claimPendingBatch(windowMs = 2_000, maxBatch = 8)

        assertTrue("窗口内不该领取", batch.isEmpty())
        val head = journal.snapshot().first { it.dispatchId == "d-qq-a-user1-$base" }
        assertEquals(base + 3_000, head.retryAtMillis)
        assertTrue("仍应保持 PENDING", head.dispatchState == ChannelInbox.DispatchState.PENDING)
    }

    @Test
    fun `窗口已过时同源连发整批领取`() {
        record("qq-a", "user1", "第一句", base)
        record("qq-a", "user1", "第二句", base + 1_000)
        record("qq-a", "user1", "第三句", base + 1_600)
        nowMillis.set(base + 4_000)

        val batch = journal.claimPendingBatch(windowMs = 2_000, maxBatch = 8)

        assertEquals(3, batch.size)
        assertEquals(listOf(base, base + 1_000, base + 1_600), batch.map { it.timestamp })
        assertTrue(batch.all { it.dispatchState == ChannelInbox.DispatchState.PROCESSING })
    }

    @Test
    fun `不同来话者与不同渠道不被吸收`() {
        record("qq-a", "user1", "在吗", base)
        record("qq-a", "user2", "在吗", base + 300)
        record("qq-b", "user1", "在吗", base + 300)
        nowMillis.set(base + 4_000)

        val batch = journal.claimPendingBatch(windowMs = 2_000, maxBatch = 8)

        assertEquals(1, batch.size)
        assertEquals("qq-a", batch.single().sourceChannelId)
    }

    @Test
    fun `媒体消息不等待窗口也不被并入文本批`() {
        // 队头是媒体:立即单条返回
        record("qq-a", "user1", "", base, media = "image")
        nowMillis.set(base + 10)
        val mediaBatch = journal.claimPendingBatch(windowMs = 2_000, maxBatch = 8)
        assertEquals(1, mediaBatch.size)

        // 文本队头 + 后到媒体:媒体不被吸收
        record("qq-a", "user1", "看下这张图", base + 100)
        record("qq-a", "user1", "", base + 200, media = "video")
        nowMillis.set(base + 5_000)
        val textBatch = journal.claimPendingBatch(windowMs = 2_000, maxBatch = 8)
        assertEquals(listOf("d-qq-a-user1-${base + 100}"), textBatch.map { it.dispatchId })
    }

    @Test
    fun `批量封顶后剩余留待下一批`() {
        repeat(4) { i -> record("qq-a", "user1", "第${i + 1}句", base + i * 100) }
        nowMillis.set(base + 4_000)

        val batch = journal.claimPendingBatch(windowMs = 2_000, maxBatch = 2)

        assertEquals(2, batch.size)
        val stillPending = journal.snapshot().count { it.dispatchState == ChannelInbox.DispatchState.PENDING }
        assertEquals(2, stillPending)
    }
}
