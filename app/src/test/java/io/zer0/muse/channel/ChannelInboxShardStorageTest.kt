package io.zer0.muse.channel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong

/**
 * 收件箱分片存储（2026-11-01 性能整改）。
 *
 * 旧实现是**单个 JSON 数组文件**：一条渠道消息的生命周期里，领取 / 存草稿 /
 * 每次工具调用的断点（执行前后各一次）/ 完成，每次都是整表重写，全部持对象锁。
 * 改成"一条队列项一个分片"之后，落盘量只与这条记录有关。
 *
 * 这里锁五件事：
 *  1. 一次 `record` 只新增一个分片（不是整表重写）；
 *  2. 状态迁移不增删分片（同一条记录原地改写）；
 *  3. 重启后顺序与状态不变（顺序靠文件名里的 seq）；
 *  4. 旧单文件能迁移成分片，且顺序与旧数组一致；
 *  5. 坏分片被隔离，其余分片照常加载（对应"写到一半被杀"的现实场景）。
 */
class ChannelInboxShardStorageTest {

    private lateinit var directory: File
    private lateinit var inboxFile: File
    private lateinit var ledgerFile: File
    private lateinit var journal: ChannelInboxJournal
    private val nowMillis = AtomicLong(1_800_000_000_000L)

    private val shardDir: File get() = File(directory, ChannelInbox.INBOX_DIR_NAME)

    private fun shards(): List<File> = shardDir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("inbox-shard-test").toFile()
        inboxFile = File(directory, "inbox.json")
        ledgerFile = File(directory, "events.json")
        journal = newJournal()
        journal.attach(inboxFile, ledgerFile)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun newJournal(): ChannelInboxJournal = ChannelInboxJournal(nowMillis = { nowMillis.get() })

    private fun source(eventId: String) = ChannelInbox.Source(
        platform = "QQ",
        from = "user",
        channelId = "qq-a",
        eventId = eventId,
    )

    private fun inbound(eventId: String, summary: String) = ChannelInbox.Inbound(
        platform = "QQ",
        from = "user",
        summary = summary,
        sourceChannelId = "qq-a",
        sourceEventId = eventId,
        dispatchId = "dispatch-$eventId",
        dispatchState = ChannelInbox.DispatchState.PENDING,
    )

    @Test
    fun `record_writes_one_shard_per_item`() {
        journal.record(source("e1"), inbound("e1", "第一条"))
        assertEquals(1, shards().size)

        journal.record(source("e2"), inbound("e2", "第二条"))
        assertEquals("每条事件一个分片", 2, shards().size)
        assertFalse("不得再写旧单文件", inboxFile.exists())
    }

    @Test
    fun `state_transitions_rewrite_in_place`() {
        journal.record(source("e1"), inbound("e1", "待处理"))
        val claimed = journal.claimNextPending()!!
        journal.savePreparedReply(claimed.dispatchId, "已生成的回复")
        journal.completeDispatch(claimed.dispatchId, ignored = false)

        assertEquals("状态迁移不应增删分片", 1, shards().size)
        val text = shards().single().readText()
        assertTrue("分片里应能看到终态", text.contains("COMPLETED"))
        assertFalse("完成后不应再留着草稿", text.contains("已生成的回复"))
    }

    @Test
    fun `restart_preserves_order_and_state`() {
        listOf("a", "b", "c").forEach { id -> journal.record(source("e-$id"), inbound("e-$id", "内容-$id")) }
        nowMillis.addAndGet(1_000)
        journal.claimNextPending()

        val restarted = newJournal().also { it.attach(inboxFile, ledgerFile) }
        val order = restarted.snapshot().map { it.summary }
        // 入队顺序的既有语义是**新事件在前**（record 用 listOf(item) + 旧列表），
        // 领取也是从这个顺序里取第一个 PENDING —— 所以这里断言的是"重启前后完全一致"。
        assertEquals("重启不得改变队列顺序（新事件在前）", listOf("内容-c", "内容-b", "内容-a"), order)
        assertEquals(
            "已领取的那条重启后仍是 PROCESSING（由 recoverInterruptedDispatches 再决定去向）",
            ChannelInbox.DispatchState.PROCESSING,
            restarted.snapshot().first().dispatchState,
        )
    }

    @Test
    fun `recovered_dispatch_survives_restart`() {
        // 回归：recoverInterruptedDispatches 曾把"刚被规范化改过状态"的那几条当成被裁掉，
        // 于是"先写新分片、再删同名分片" —— 内存里有、磁盘上没有，下次重启这条事件就消失了。
        journal.record(source("e1"), inbound("e1", "被打断的派发"))
        val claimed = journal.claimNextPending()!!
        assertEquals(ChannelInbox.DispatchState.PROCESSING, claimed.dispatchState)

        // 模拟重启：新实例 attach 后把 PROCESSING 回退成 PENDING
        val restarted = newJournal().also { it.attach(inboxFile, ledgerFile) }
        restarted.recoverInterruptedDispatches()
        assertEquals(ChannelInbox.DispatchState.PENDING, restarted.snapshot().single().dispatchState)

        // 再重启一次：磁盘上必须还留着这条（修复前这里会查到 0 条）
        val again = newJournal().also { it.attach(inboxFile, ledgerFile) }
        assertEquals("恢复结果必须落盘，否则下次启动就丢事件", 1, again.snapshot().size)
        assertEquals("被打断的派发", again.snapshot().single().summary)
    }

    @Test
    fun `completed_entry_shard_is_kept_without_media_bytes`() {
        // 回归：完成派发时落盘的是 transform 之后的原始副本（还带 mediaBase64），
        // 而内存里已被 normalize 清空 —— 用 data class 相等判删除会把这份分片一起删掉。
        val withImage = inbound("e-media", "带图的完成项").copy(
            mediaKind = "image",
            mediaBase64 = "A".repeat(500),
        )
        journal.record(source("e-media"), withImage)
        val claimed = journal.claimNextPending()!!
        journal.completeDispatch(claimed.dispatchId, ignored = false)

        assertEquals("完成项的分片必须还在", 1, shards().size)
        val text = shards().single().readText()
        assertTrue("终态分片里应保留状态", text.contains("COMPLETED"))
        assertFalse("终态分片里不应再有图片字节", text.contains("A".repeat(200)))
    }

    @Test
    fun `legacy_single_file_is_migrated_to_shards`() {
        // 造一份旧版本落盘：三条记录，数组顺序即入队顺序。
        val legacy = listOf("legacy-1", "legacy-2", "legacy-3").mapIndexed { index, summary ->
            ChannelInbox.Inbound(
                platform = "QQ",
                from = "user",
                summary = summary,
                dispatchId = "old-$index",
                dispatchState = ChannelInbox.DispatchState.COMPLETED,
            )
        }
        inboxFile.writeText(
            kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(ChannelInbox.Inbound.serializer()),
                legacy,
            ),
        )

        val migrated = newJournal().also { it.attach(inboxFile, ledgerFile) }

        assertEquals("三条旧记录应各写一个分片", 3, shards().size)
        assertEquals(
            "迁移后顺序与旧数组一致",
            listOf("legacy-1", "legacy-2", "legacy-3"),
            migrated.snapshot().map { it.summary },
        )
        assertFalse("迁移完成后旧单文件应让位", inboxFile.exists())
        assertTrue("旧文件保留为 .migrated 供回查", File(directory, "inbox.json.migrated").exists())
    }

    @Test
    fun `corrupt_shard_is_quarantined_and_others_still_load`() {
        journal.record(source("e1"), inbound("e1", "好的一条"))
        File(shardDir, "000000000099-deadbeef.json").writeText("{ 写到一半 ")

        val restarted = newJournal().also { it.attach(inboxFile, ledgerFile) }
        assertEquals("坏分片应被跳过，好的一条照常加载", 1, restarted.snapshot().size)
        assertEquals("好的一条", restarted.snapshot().single().summary)
        assertTrue(
            "坏分片必须被隔离而不是静默删除",
            shardDir.listFiles().orEmpty().any { it.name.contains("corrupt") },
        )
    }

    @Test
    fun `shard_directory_is_in_backup_whitelist`() {
        assertTrue(
            "分片目录必须在目录型备份白名单内，否则恢复后待派发事件全部丢失",
            io.zer0.muse.backup.BackupBinaryFiles.allowedPrefixes.contains("channel_inbox/"),
        )
        assertTrue(
            "旧单文件仍留在文件白名单里（迁移源 + 老备份兼容）",
            io.zer0.muse.backup.BackupService.backupFileStorePaths.contains("channel_inbox.json"),
        )
    }

    @Test
    fun `clear_keeps_active_shards_and_drops_history`() {
        journal.record(source("e1"), inbound("e1", "已完成"))
        val claimed = journal.claimNextPending()!!
        journal.completeDispatch(claimed.dispatchId, ignored = false)
        journal.record(source("e2"), inbound("e2", "待处理"))

        assertEquals(2, shards().size)
        journal.clear()

        assertEquals("清空只保留未完成项", 1, shards().size)
        assertEquals("待处理", journal.snapshot().single().summary)
    }
}
