package io.zer0.muse.data.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.card.CardEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [io.zer0.muse.data.card.CardEntityDao] 的行为测试（MuseDb 118）。
 *
 * 迁移测试只能证明「表与索引建对了」，证明不了 DAO 的 SQL 语义；
 * 这里用内存库把规格 §3.2 的三条语义钉住：
 *  - 停靠卡按 dockSlot 升序读出（Pager 顺序稳定）；
 *  - 未停靠的卡不进停靠列表，且可以有多张（唯一索引不吃 NULL）；
 *  - 清除 dockSlot 只取消停靠，行还在（拖回不删卡）。
 *
 * 放在 data/session 包下是为了与 MuseDb 迁移测试同目录，复用同一个内存库装配方式。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CardEntityDaoTest {

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

    private fun card(id: String, originMessageId: String? = null, dockSlot: Int? = null, createdAt: Long = 1_000L) = CardEntity(
        id = id,
        legacyCardId = "c_$id",
        originSessionId = "s1",
        originMessageId = originMessageId,
        title = "标题 $id",
        code = "<div>$id</div>",
        isSvg = false,
        dataJson = """{"v":1}""",
        dockSlot = dockSlot,
        dockedAt = dockSlot?.let { 2_000L },
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    @Test
    fun upsertThenReadByIdAndLegacyId() = runTest {
        val dao = database.cardEntityDao()
        dao.upsert(card("id-1"))

        val byId = dao.getById("id-1")
        assertEquals("标题 id-1", byId?.title)
        assertEquals("""{"v":1}""", byId?.dataJson)
        assertEquals("旧 c_xxx 应能反查同一张卡", "id-1", dao.getByLegacyCardId("c_id-1")?.id)
        assertNull("不存在的 id 返回 null", dao.getById("nope"))
    }

    @Test
    fun upsertOverwritesExistingRow() = runTest {
        val dao = database.cardEntityDao()
        dao.upsert(card("id-1", originMessageId = "m1"))
        dao.upsert(card("id-1", originMessageId = "m1").copy(title = "改过的标题", updatedAt = 9_000L))

        assertEquals("同 id upsert 应覆盖而不是插两行", 1, dao.getByOriginMessageId("m1").size)
        assertEquals("改过的标题", dao.getById("id-1")?.title)
    }

    @Test
    fun dockedCardsAreOrderedBySlotAndExcludeInlineOnes() = runTest {
        val dao = database.cardEntityDao()
        dao.upsert(card("a", dockSlot = 2, createdAt = 1_000L))
        dao.upsert(card("b", dockSlot = 0, createdAt = 2_000L))
        dao.upsert(card("c", dockSlot = 1, createdAt = 3_000L))
        // 两张未停靠的卡：唯一索引不应让它们互相冲突
        dao.upsert(card("d", createdAt = 4_000L))
        dao.upsert(card("e", createdAt = 5_000L))

        assertEquals(
            "停靠卡按 dockSlot 升序，未停靠卡不出现",
            listOf("b", "c", "a"),
            dao.getDockedCards().map { it.id },
        )
    }

    @Test
    fun updateAndClearDockSlotKeepsRow() = runTest {
        val dao = database.cardEntityDao()
        dao.upsert(card("a"))

        assertEquals("拖出：写入槽位", 1, dao.updateDockSlot("a", 0, 5_000L, 5_000L))
        assertEquals(listOf("a"), dao.getDockedCards().map { it.id })
        assertEquals(5_000L, dao.getById("a")?.dockedAt)

        assertEquals("拖回：清槽位", 1, dao.clearDockSlot("a", 6_000L))
        val after = dao.getById("a")
        assertTrue("拖回不删卡，行必须还在", after != null)
        assertNull("槽位应回到 NULL", after?.dockSlot)
        assertNull("停靠时间也应清空", after?.dockedAt)
        assertTrue("停靠列表应不再包含它", dao.getDockedCards().isEmpty())
        assertEquals("内容不应被拖回动作改动", "<div>a</div>", after?.code)
    }

    @Test
    fun cardsAreReadableByOriginMessage() = runTest {
        val dao = database.cardEntityDao()
        dao.upsert(card("a", originMessageId = "m1", createdAt = 1_000L))
        dao.upsert(card("b", originMessageId = "m1", createdAt = 2_000L))
        dao.upsert(card("c", originMessageId = "m2", createdAt = 3_000L))

        assertEquals(
            "一条消息可承载多张卡，按创建时间稳定排序",
            listOf("a", "b"),
            dao.getByOriginMessageId("m1").map { it.id },
        )
        assertTrue("别的消息的卡不应混进来", dao.getByOriginMessageId("m3").isEmpty())
    }

    // ── v2.6.9: 备份侧需要的全量接口 ──

    @Test
    fun getAllReturnsEveryRowIncludingInlineAndDocked() = runTest {
        val dao = database.cardEntityDao()
        dao.upsert(card("docked", dockSlot = 0))
        dao.upsert(card("inline-a"))
        dao.upsert(card("inline-b"))

        assertEquals("全量查询不能漏未停靠卡", 3, dao.getAll().size)
        assertEquals(
            "getAll 与 count 必须一致(恢复对账依赖后者)",
            dao.getAll().size,
            dao.count(),
        )
    }

    @Test
    fun getAllPreservesEveryColumnWithoutHandWrittenMapping() = runTest {
        // 这条就是替代旧反射测试的守护:读写走 Room 生成的映射,实体加列时
        // 不需要在备份侧同步任何列清单。这里逐字段断言,确保没有列被静默丢掉。
        val dao = database.cardEntityDao()
        val rich = card("rich", originMessageId = "m-rich", dockSlot = 1, createdAt = 4_000L).copy(
            isSvg = true,
            dataJson = """{"rows":[1,2,3]}""",
            updatedAt = 9_000L,
        )
        dao.upsert(rich)

        assertEquals("逐列往返必须原样", rich, dao.getAll().single())
    }

    @Test
    fun deleteAllClearsTableButKeepsSchemaUsable() = runTest {
        val dao = database.cardEntityDao()
        dao.upsert(card("a", dockSlot = 0))
        dao.upsert(card("b"))

        dao.deleteAll()

        assertEquals("清空后不应残留任何行", 0, dao.count())
        assertTrue(dao.getAll().isEmpty())
        assertTrue("清空后停靠区订阅也应为空", dao.getDockedCards().isEmpty())

        // 清空后还能正常写入(恢复流程就是先清空再批量写)
        dao.insertAll(listOf(card("fresh", dockSlot = 0)))
        assertEquals(listOf("fresh"), dao.getDockedCards().map { it.id })
    }

    @Test
    fun insertAllReplacesConflictingPrimaryKey() = runTest {
        val dao = database.cardEntityDao()
        dao.insertAll(listOf(card("same").copy(title = "旧标题")))
        dao.insertAll(listOf(card("same").copy(title = "新标题")))

        assertEquals("同主键批量写入是替换而不是报错", 1, dao.count())
        assertEquals("新标题", dao.getById("same")?.title)
    }

    @Test
    fun insertAllWithEmptyListIsNoOp() = runTest {
        // 空备份(无卡片)恢复时不能因空列表报错
        database.cardEntityDao().insertAll(emptyList())

        assertEquals(0, database.cardEntityDao().count())
    }
}
