package io.zer0.muse.backup

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.card.CardEntity
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.6.8: 卡片停靠态**落库往返**验证。
 *
 * 上一份测试只能证明「字段没丢」和「源码里有那行调用」，证明不了「真把行写进库了」，
 * 也证明不了 dockSlot 的唯一索引在恢复顺序下不会绊脚。这里用真实 Room 内存库跑：
 *
 *  导出（readAllCardEntities → toRecord → inDockOrder）
 *    → 清空（deleteAllCardEntities，模拟恢复前置）
 *    → 写回（insertCardEntities）
 *    → 断言行数、逐字段内容、停靠区顺序
 *
 * 与 `applyBackupInternal` / `applyNdJsonStreaming` 用的是同一组函数，因此这里过，
 * 两条恢复路径的卡片部分就过。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupCardRestoreRoundTripTest {

    private lateinit var database: MuseDb

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun card(id: String, dockSlot: Int? = null, createdAt: Long = 1_000L, title: String = "标题 $id") = CardEntity(
        id = id,
        legacyCardId = "c_$id",
        originSessionId = "s1",
        originMessageId = "m-$id",
        title = title,
        code = "<div>$id</div>",
        isSvg = false,
        dataJson = """{"v":"$id"}""",
        dockSlot = dockSlot,
        dockedAt = dockSlot?.let { 2_000L },
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    /**
     * 复刻导出侧：全量读出 → 转线格式 → 按停靠顺序定序。
     *
     * 包在 `withTransaction` 里 —— 真实 `buildBackup` / `writeNdJson` 就是这样调的，
     * 而这套读写用 `openHelper.writableDatabase` 而非 DAO。若它拿的不是事务那条连接，
     * 这里会死锁或读到事务外的状态。
     */
    private suspend fun exportCards(): List<CardEntityRecord> = database.withTransaction {
        database.readAllCardEntities().map { it.toRecord() }.inDockOrder()
    }

    /** 复刻恢复侧：清空 → 校验槽位 → 逐条写回（同在恢复事务内，与 applyBackupInternal 一致）。 */
    private suspend fun restoreCards(records: List<CardEntityRecord>) = database.withTransaction {
        database.deleteAllCardEntities()
        val entities = records.map { it.toEntity() }
        requireNoDuplicateDockSlots(entities)
        database.insertCardEntities(entities)
    }

    @Test
    fun `docked card layout survives export and restore`() = runTest {
        val cards = listOf(
            card("slot-2", dockSlot = 2, createdAt = 1_000L),
            card("slot-0", dockSlot = 0, createdAt = 2_000L),
            card("slot-1", dockSlot = 1, createdAt = 3_000L),
        )
        cards.forEach { database.cardEntityDao().upsert(it) }

        restoreCards(exportCards())

        assertEquals("行数必须一致", 3, database.countCardEntities())
        assertEquals(
            "停靠顺序必须保持(槽位是停靠区 Pager 的唯一顺序来源)",
            listOf("slot-0", "slot-1", "slot-2"),
            database.cardEntityDao().getDockedCards().map { it.id },
        )
        assertEquals(
            "逐字段内容必须一致(内容丢了等于卡是空壳)",
            cards.sortedBy { it.id },
            database.readAllCardEntities().sortedBy { it.id },
        )
    }

    @Test
    fun `inline cards are preserved alongside docked ones`() = runTest {
        val docked = card("docked", dockSlot = 0, createdAt = 1_000L)
        val inlineA = card("inline-a", dockSlot = null, createdAt = 2_000L)
        val inlineB = card("inline-b", dockSlot = null, createdAt = 3_000L)
        listOf(docked, inlineA, inlineB).forEach { database.cardEntityDao().upsert(it) }

        restoreCards(exportCards())

        assertEquals("未停靠卡同样要保留", 3, database.countCardEntities())
        assertEquals(
            "未停靠卡不能挤进停靠区",
            listOf("docked"),
            database.cardEntityDao().getDockedCards().map { it.id },
        )
        assertNull("未停靠卡的槽位必须仍是 null", database.cardEntityDao().getById("inline-a")?.dockSlot)
        assertEquals("未停靠卡内容也必须在", "<div>inline-b</div>", database.cardEntityDao().getById("inline-b")?.code)
    }

    @Test
    fun `restore replaces stale rows instead of merging`() = runTest {
        database.cardEntityDao().upsert(card("stale", dockSlot = 5, createdAt = 100L))
        val incoming = listOf(card("fresh", dockSlot = 0, createdAt = 200L))

        restoreCards(incoming.map { it.toRecord() })

        assertEquals("恢复必须整体替换,不残留旧卡", 1, database.countCardEntities())
        assertNull("旧卡应被清掉", database.cardEntityDao().getById("stale"))
        assertEquals(listOf("fresh"), database.cardEntityDao().getDockedCards().map { it.id })
    }

    @Test
    fun `duplicate dock slots in backup fail the restore`() = runTest {
        val duplicated = listOf(card("a", dockSlot = 0), card("b", dockSlot = 0))

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { restoreCards(duplicated.map { it.toRecord() }) }
        }
        assertEquals("校验失败会让整个恢复事务回滚，库里不留下半套卡片", 0, database.countCardEntities())
    }

    @Test
    fun `restored cards notify observers so the dock refreshes`() = runTest {
        // 恢复走的是手写 SQL（DAO 没有全量接口），必须确认它仍触发 Room 的表级失效通知，
        // 否则恢复完成停靠区还显示恢复前的旧卡。
        val before = database.cardEntityDao().observeDockedCards().first()
        assertTrue("前置:恢复前没有停靠卡", before.isEmpty())

        restoreCards(listOf(card("fresh", dockSlot = 0).toRecord()))

        assertEquals(
            "恢复后停靠区订阅必须能看到新卡",
            listOf("fresh"),
            database.cardEntityDao().observeDockedCards().first().map { it.id },
        )
    }

    @Test
    fun `card data binding survives restore`() = runTest {
        // dataJson 是 show_card / update_card_data 的绑定数据，丢了卡片就渲染不出内容。
        val rich = card("rich", dockSlot = 1).copy(
            dataJson = """{"rows":[1,2,3],"title":"表格"}""",
            title = "数据卡",
            code = "<svg></svg>",
            isSvg = true,
        )
        database.cardEntityDao().upsert(rich)

        restoreCards(exportCards())

        val restored = database.cardEntityDao().getById("rich")
        assertEquals("""{"rows":[1,2,3],"title":"表格"}""", restored?.dataJson)
        assertEquals("数据卡", restored?.title)
        assertEquals("<svg></svg>", restored?.code)
        assertEquals("SVG 分支标志必须保留", true, restored?.isSvg)
    }
}
