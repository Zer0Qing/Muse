package io.zer0.memory.fact

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * v2.6.23(M4): 记忆账本审计的计数口径。
 *
 * 审计是**内部**设施:它不修数据、不提示用户,只在有人问「它是不是记乱了」时给得出数字。
 * 所以这里钉的是两件事 ——
 *  1. 每种坏数据都被**对应**那一项抓到(不能全部塌成一个「有问题」的总数);
 *  2. 好数据不许被误报(no-finding 路径必须真的为 0),
 *     尤其 entity_key 缺失的存量记录不算重复(宁漏不错,与 D6 检测器同一取向)。
 *
 * 全部走 `ProviderMemoryAuditSource.auditOne`(纯 DAO 计数),
 * 多库遍历的聚合与容错另有两例。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FactAuditTest {

    private lateinit var db: FactDb
    private lateinit var dao: FactDao

    private val now: Instant = Instant.parse("2026-11-06T00:00:00Z")

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.factDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insert(
        fact: String,
        entityKey: String? = null,
        evidenceQuote: String? = "原话",
        supersedesId: Long? = null,
        mergedIntoId: Long? = null,
        conflictStatus: String = "none",
        expiresAt: String? = null,
    ): Long = dao.insert(
        FactEntity(
            fact = fact,
            createdAt = "2026-11-05T00:00:00Z",
            entityKey = entityKey,
            evidenceQuote = evidenceQuote,
            supersedesId = supersedesId,
            mergedIntoId = mergedIntoId,
            conflictStatus = conflictStatus,
            expiresAt = expiresAt,
        ),
    )

    private suspend fun audit() = ProviderMemoryAuditSource.auditOne(dao, now)

    // ── 干净账本 ──────────────────────────────────────────────

    @Test
    fun `空账本与正常账本都不报任何异常`() = runTest {
        val empty = audit()
        assertEquals("空库仍算「读到了一个库」,否则导出里分不清空账本与未装配", 1, empty.databasesChecked)
        assertEquals(0, empty.totalFacts)
        assertFalse(empty.hasFindings)

        insert("用户喜欢手冲咖啡", entityKey = "user|饮品", evidenceQuote = "我最近改喝手冲了")
        insert("用户住在杭州", entityKey = null, evidenceQuote = null)
        val report = audit()
        assertEquals(2, report.totalFacts)
        // 无证据那一条是**存量迁移**的常态,但审计仍要如实计数,不能因为「可能正常」就不报
        assertEquals(1, report.withoutEvidence)
        assertTrue(report.hasFindings)
        assertEquals(0, report.duplicateEntities)
        assertEquals(0, report.danglingSupersedes)
        assertEquals(0, report.danglingMergedInto)
        assertEquals(0, report.pendingConflicts)
        assertEquals(0, report.expiredNotSwept)
    }

    // ── 逐项抓到 ──────────────────────────────────────────────

    @Test
    fun `同实体多条不同断言计入重复候选`() = runTest {
        insert("用户是设计师", entityKey = "user|职业")
        insert("用户养了一只猫", entityKey = "user|宠物")
        insert("用户在上海工作", entityKey = "user|职业")
        insert("用户换了工作", entityKey = "user|职业")

        val report = audit()
        assertEquals("只有 user|职业 这一个实体挂了多条不同断言", 1, report.duplicateEntities)
    }

    @Test
    fun `同一实体同一断言不计入重复候选`() = runTest {
        // COUNT(DISTINCT fact):完全相同的文本重复是另一回事(去重器负责),
        // 这里要抓的是「同一个实体说了好几件不同的事」。
        insert("用户是设计师", entityKey = "user|职业")
        insert("用户是设计师", entityKey = "user|职业")
        assertEquals(0, audit().duplicateEntities)
    }

    @Test
    fun `没有实体键的记录不被当成重复`() = runTest {
        // 与 D6 检测器一致:中文名等没有归一化键的存量不误判。
        insert("张三喜欢篮球", entityKey = null)
        insert("张三喜欢足球", entityKey = null)
        assertEquals(0, audit().duplicateEntities)
    }

    @Test
    fun `覆盖链与合并指向缺失行被分别抓到`() = runTest {
        val keep = insert("用户改喝茶了")
        insert("用户喜欢咖啡", supersedesId = keep)
        insert("用户喜欢美式", supersedesId = 9_999L)
        insert("用户习惯早起", mergedIntoId = keep)
        insert("用户习惯晚睡", mergedIntoId = 8_888L)

        val report = audit()
        assertEquals(1, report.danglingSupersedes)
        assertEquals(1, report.danglingMergedInto)
    }

    @Test
    fun `删除覆盖目标后孤儿事实立刻可查`() = runTest {
        val target = insert("用户旧偏好")
        insert("用户新偏好", supersedesId = target)
        assertEquals(0, audit().danglingSupersedes)

        dao.deleteById(target)
        val report = audit()
        assertEquals("被覆盖方还在,目标没了", 1, report.danglingSupersedes)
        assertEquals(1, report.totalFacts)
    }

    @Test
    fun `待裁决矛盾计入`() = runTest {
        insert("用户住在杭州", conflictStatus = "pending")
        insert("用户住在苏州", conflictStatus = "pending")
        insert("用户已婚", conflictStatus = "none")
        assertEquals(2, audit().pendingConflicts)
    }

    @Test
    fun `过期未清扫按 Instant 判定而不是按字符串比较`() = runTest {
        // 写入侧是 Instant.now().toString(),秒小数位长度不固定:"...:25Z" 与 "...:25.486Z" 都会出现。
        // 关键是这条:2026-11-06T00:00:00.5Z 比 now(整点)晚半秒,**没过期**,
        // 但按字符串比,".5Z" 排在 "Z" 前面(0x2E < 0x5A),会被误判成已过期 ——
        // 这就是审计为什么必须在 Kotlin 侧解析时间。
        insert("还没过期", expiresAt = "2026-11-06T00:00:00.5Z")
        insert("已经过期一条", expiresAt = "2026-11-05T23:59:59Z")
        insert("过期但带小数秒", expiresAt = "2026-11-05T23:59:58.486Z")
        // 解析失败的时间戳不算过期(与 FactStore.isExpired 的从严取向一致)
        insert("时间戳坏掉", expiresAt = "not-a-time")

        val report = audit()
        assertEquals(2, report.expiredNotSwept)
        assertTrue(report.hasFindings)
    }

    @Test
    fun `正常一条也不报发现`() = runTest {
        insert("一条正常事实", entityKey = "user|饮品", evidenceQuote = "我改喝茶了")
        assertFalse(audit().hasFindings)
    }

    // ── 多库聚合与容错 ────────────────────────────────────────

    @Test
    fun `未装配的审计源给出全零而不是异常`() = runTest {
        val report = MemoryAuditSource.EMPTY.audit(now)
        assertEquals(FactAuditReport.EMPTY, report)
        assertEquals("未装配时读到 0 个库,导出里能分辨「没装配」与「干净」", 0, report.databasesChecked)
    }

    @Test
    fun `多库聚合逐项相加并记住读到的库数`() = runTest {
        val extra = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FactDb::class.java,
        ).allowMainThreadQueries().build()
        try {
            val other = extra.factDao()
            other.insert(FactEntity(fact = "分库事实一", createdAt = "2026-11-05T00:00:00Z", evidenceQuote = null))
            other.insert(FactEntity(fact = "分库事实二", createdAt = "2026-11-05T00:00:00Z", evidenceQuote = "原话"))
            insert("主库事实", evidenceQuote = null)

            val report = ProviderMemoryAuditSource.aggregate(listOf(dao, other), now)
            assertEquals(2, report.databasesChecked)
            assertEquals(3, report.totalFacts)
            assertEquals("两库各一条无证据,必须相加而不是覆盖", 2, report.withoutEvidence)
        } finally {
            extra.close()
        }
    }

    @Test
    fun `一个库都读不到时记成零个库而不是干净`() = runTest {
        val report = ProviderMemoryAuditSource.aggregate(emptyList(), now)
        assertEquals(FactAuditReport.EMPTY, report)
        assertEquals(0, report.databasesChecked)
        assertFalse("零个库不算「有发现」,但也不能被读成账本干净 —— 导出里靠 databasesChecked 区分", report.hasFindings)
    }

    @Test
    fun `审计时间戳缺省用当前时刻`() = runTest {
        val future = Instant.now().plus(30, ChronoUnit.DAYS).toString()
        insert("还没到期的事", expiresAt = future)
        assertEquals(0, MemoryAuditSource.EMPTY.audit().expiredNotSwept)
        assertEquals(0, audit().expiredNotSwept)
    }
}
