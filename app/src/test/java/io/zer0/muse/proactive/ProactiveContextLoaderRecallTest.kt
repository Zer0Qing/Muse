package io.zer0.muse.proactive

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.zer0.memory.fact.FactDb
import io.zer0.memory.fact.FactEntity
import io.zer0.memory.fact.FactStore
import io.zer0.muse.data.proactive.PendingThoughtEntity
import io.zer0.muse.data.proactive.ProactiveOutcomeRepository
import io.zer0.muse.data.proactive.ProactiveRelationRepository
import io.zer0.muse.data.proactive.ProactiveThoughtRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * v17: 主动消息的牵挂推导不得使用「已出场」（分数低于衰减阈值）的记忆。
 *
 * 这条路径的产出会写进主动消息的上下文，所以出场的记忆从这里漏进去，
 * 等于「衰减出场」没生效。此处用真实 FactStore（in-memory Room）+ 假的仓库/运行环境，
 * 直接验证推导结果里只剩仍在召回范围内的记忆。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProactiveContextLoaderRecallTest {

    private lateinit var db: FactDb
    private lateinit var store: FactStore
    private lateinit var thoughts: ProactiveThoughtRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()
        store = FactStore(db.factDao(), db)
        thoughts = mockk(relaxed = true)
        coEvery { thoughts.getOpenSourceRefs(any()) } returns emptySet()
        coEvery { thoughts.note(any()) } returns true
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun loader(): ProactiveContextLoader = ProactiveContextLoader(
        stores = ProactiveStores(
            thoughts = thoughts,
            relations = mockk<ProactiveRelationRepository>(relaxed = true),
            outcomes = mockk<ProactiveOutcomeRepository>(relaxed = true),
        ),
        runtime = mockk(relaxed = true),
        sources = ProactiveSources(factStore = store, milestoneDao = mockk(relaxed = true)),
    )

    @Test
    fun `deriveThoughts skips suppressed memories`() = runTest {
        val now = Instant.now().toEpochMilli()
        // 仍在召回中的记忆:命中 CARE 规则,应推导出牵挂
        val kept = store.add(FactStore.Fact(fact = "最近总是失眠"))
        // 已出场的记忆:同样命中 CARE 规则,但不许进入推导
        db.factDao().insert(
            FactEntity(
                fact = "最近总是头疼",
                tags = "[]",
                createdAt = Instant.ofEpochMilli(now).toString(),
                importance = 0,
                suppressedAt = Instant.ofEpochMilli(now).toString(),
            ),
        )
        store.rebuildFtsIndex()

        loader().deriveThoughts("default", now)

        val recorded = slot<PendingThoughtEntity>()
        coVerify(exactly = 1) { thoughts.note(capture(recorded)) }
        assertEquals("只应为仍在召回中的记忆记账", "fact_$kept", recorded.captured.sourceRef)
        assertTrue("推导内容不应包含已出场的记忆", !recorded.captured.seed.contains("头疼"))
    }
}
