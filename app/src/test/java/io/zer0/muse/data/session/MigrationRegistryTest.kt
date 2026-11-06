package io.zer0.muse.data.session

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v2.7.x(S3): 迁移包装层的记账与失败语义。
 *
 * 三条不能退让的行为都在这里钉住:
 *  1. **总步数是本次要跑的步数**,不是整条链的长度(126)。用整条链当分母,
 *     用户看到的是「1/126」,那比不报进度更误导。
 *  2. 迁移异常**原样抛出**且仍被记录。观察层出错只许丢日志,绝不允许吃掉迁移异常 ——
 *     那会把「数据迁移失败」变成「迁移似乎成功了」。
 *  3. 一轮结果只在最后一步或失败时发布一次,字段是真累加而不是最后一步的值。
 */
class MigrationRegistryTest {

    private val db = mockk<SupportSQLiteDatabase>(relaxed = true)

    /** 假迁移:记录自己被调用,可选抛错。 */
    private class FakeMigration(from: Int, to: Int, private val boom: (() -> Unit)? = null) :
        Migration(from, to) {
        var calls = 0
        override fun migrate(db: SupportSQLiteDatabase) {
            calls += 1
            boom?.invoke()
        }
    }

    private fun chain(vararg pairs: Pair<Int, Int>, boomOn: Int? = null): List<Migration> = pairs.map { (from, to) ->
        val boom: (() -> Unit)? = if (to == boomOn) {
            { throw IllegalStateException("step $to failed") }
        } else {
            null
        }
        FakeMigration(from, to, boom)
    }

    private lateinit var recorded: MutableList<LastMigrationRun>
    private var clockValue = 0L

    @Before
    fun setup() {
        recorded = mutableListOf()
        clockValue = 0L
        MigrationProgressBus.reset()
    }

    private fun registry(all: List<Migration>): MigrationRegistry =
        MigrationRegistry(all = all, clock = { clockValue }, onRecord = { recorded.add(it) })

    /**
     * 按「每一步耗时」给时钟。
     *
     * 包装层每一步取两次时钟(起点、终点),所以这里第 2k 次返回当前累计值、
     * 第 2k+1 次加上第 k 步的耗时。上一版我直接手写六个返回值并把期望算错了 ——
     * 这种时钟一旦靠数下标来对齐,测试自己就会先于被测代码出错。
     */
    private fun tickingClock(durations: List<Long>): () -> Long {
        var calls = 0
        var now = 0L
        return {
            if (calls % 2 == 1) now += durations[calls / 2]
            calls += 1
            now
        }
    }

    @Test
    fun `耗时累加且最慢步骤单独记录`() {
        val all = chain(1 to 2, 2 to 3, 3 to 4)
        val clock = tickingClock(listOf(10L, 50L, 10L))
        val wrapped = MigrationRegistry(all = all, clock = clock, onRecord = { recorded.add(it) }).timed()
        wrapped.forEach { it.migrate(db) }

        assertEquals(1, recorded.size)
        val run = recorded.single()
        assertEquals("三步各 10/50/10 毫秒", 70L, run.totalMillis)
        assertEquals("最慢的一步", 50L, run.slowestStepMillis)
        assertEquals("最慢一步的起点版本", 2, run.slowestFromVersion)
        assertEquals(1, run.fromVersion)
        assertEquals(4, run.toVersion)
        assertEquals(3, run.steps)
        assertNull("全部成功时不许留下失败标记", run.failedAtVersion)
    }

    @Test
    fun `只跑链上需要的几步时分母是本次步数`() {
        val all = chain(1 to 2, 2 to 3, 3 to 4, 4 to 5)
        val wrapped = registry(all).timed()
        // 模拟 Room 从版本 3 起升级:只会调用 3→4 与 4→5
        clockValue = 1L
        wrapped[2].migrate(db)
        val mid = MigrationProgressBus.progress.value
        assertEquals(2, mid.totalSteps)
        assertEquals(1, mid.completedSteps)
        clockValue = 2L
        wrapped[3].migrate(db)
        val done = MigrationProgressBus.progress.value
        assertEquals(MigrationPhase.DONE, done.phase)
        assertEquals(2, done.completedSteps)
        assertEquals("步数不许超过总步数", 2, done.totalSteps)
    }

    @Test
    fun `迁移异常原样抛出并被记录`() {
        val all = chain(1 to 2, 2 to 3, 3 to 4, boomOn = 3)
        val wrapped = registry(all).timed()
        clockValue = 1L
        wrapped[0].migrate(db)

        val error = assertThrows(IllegalStateException::class.java) { wrapped[1].migrate(db) }
        assertEquals("step 3 failed", error.message)

        val progress = MigrationProgressBus.progress.value
        assertEquals(MigrationPhase.FAILED, progress.phase)
        val run = recorded.single()
        assertEquals("失败也要发布结果,否则诊断只看得见成功的迁移", 3, run.failedAtVersion)
        assertEquals(2, run.steps)
    }

    @Test
    fun `观察层出错不许影响迁移`() {
        val all = chain(1 to 2, 2 to 3)
        val boomRegistry = MigrationRegistry(
            all = all,
            clock = { 1L },
            onRecord = { throw IllegalStateException("prefs 写入失败") },
        ).timed()

        boomRegistry.forEach { it.migrate(db) }
        val fakes = all.map { it as FakeMigration }
        assertEquals("包装层的失败不得改变迁移的执行结果", listOf(1, 1), fakes.map { it.calls })
        assertEquals(MigrationPhase.DONE, MigrationProgressBus.progress.value.phase)
    }

    @Test
    fun `包装层保留版本号区间`() {
        val wrapped = registry(chain(7 to 8, 8 to 9)).timed()

        assertEquals(listOf(7 to 8, 8 to 9), wrapped.map { it.startVersion to it.endVersion })
        assertTrue("注册顺序即执行顺序", wrapped[0].startVersion < wrapped[1].startVersion)
    }

    @Test
    fun `进度层在没迁移时是不可见的`() {
        assertEquals(MigrationPhase.IDLE, MigrationProgressBus.progress.value.phase)
        assertTrue("没在跑就不该画这一层", !MigrationProgressBus.progress.value.visible)
    }
}
