package io.zer0.memory.dream

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.memory.runtime.MemoryScopeKey
import io.zer0.memory.summary.MemoryDb
import io.zer0.memory.summary.ScopedCompiledSectionEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Dream 发布/回滚的持久化语义回归。
 *
 * 这里钉住四件此前会直接伤害用户的事：
 *  - start 只保存 revision、不改段落（UI 说完成但记忆没变）；
 *  - restore 写回 next 而不是 before（回滚把「整理后」又写一遍）；
 *  - 发布/回滚整表删除（把无关的 FAILED/STALE 与新编译成果一起抹掉）；
 *  - 进程被杀后 running=true 永久卡住（之后每次 start 都报「已在运行」）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemoryDreamPublishTest {

    private lateinit var db: MemoryDb
    private val scope = MemoryScopeKey(assistantId = "default", scope = "main", spaceId = "default")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MemoryDb::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `start publishes changed sections and keeps unrelated non-ready rows`() = runTest {
        seed("facts", "旧事实", "READY")
        seed("today", "今天", "READY")
        seed("week", "失败的周报", "FAILED")
        val planner = plannerChanging("facts" to "新事实")
        val service = DefaultMemoryDreamService(db = db, planner = planner)

        val result = service.start(
            scope,
            DreamTrigger.MANUAL,
            mapOf("facts" to "旧事实", "today" to "今天"),
        )

        assertTrue(result is DreamStartResult.Started)
        val runId = (result as DreamStartResult.Started).runId
        assertEquals("新事实", db.scopedCompiledSectionDao().get("facts", "main", "default")?.content)
        assertEquals("READY", db.scopedCompiledSectionDao().get("facts", "main", "default")?.status)
        assertEquals("今天", db.scopedCompiledSectionDao().get("today", "main", "default")?.content)
        val failedWeek = db.scopedCompiledSectionDao().get("week", "main", "default")
        assertEquals("失败的周报", failedWeek?.content)
        assertEquals("FAILED", failedWeek?.status)
        assertNotNull(db.memoryDreamDao().findRevision(runId, "default", "main", "default"))
        val state = db.memoryDreamDao().state("default", "main", "default")
        assertFalse(state?.running ?: true)
        assertEquals(runId, state?.lastRevisionId)
        // 主助手默认槽位的旧编译表必须同步，否则 scoped 行缺失时旧内容会从兜底路径复活。
        assertEquals("新事实", db.compiledSectionDao().get("facts")?.content)
    }

    @Test
    fun `restore writes before content and removes keys added by dream`() = runTest {
        seed("facts", "旧事实", "READY")
        seed("week", "无关的过期周报", "STALE")
        val planner = plannerChanging("facts" to "新事实", "today" to "今天")
        val service = DefaultMemoryDreamService(db = db, planner = planner)
        val runId = (
            service.start(
                scope,
                DreamTrigger.MANUAL,
                mapOf("facts" to "旧事实"),
            ) as DreamStartResult.Started
            ).runId

        assertEquals("今天", db.scopedCompiledSectionDao().get("today", "main", "default")?.content)

        val restored = service.restore(scope, runId)

        assertTrue(restored is DreamRestoreResult.Restored)
        assertEquals("旧事实", db.scopedCompiledSectionDao().get("facts", "main", "default")?.content)
        assertNull("Dream 新增的段落应在回滚时删掉", db.scopedCompiledSectionDao().get("today", "main", "default"))
        assertEquals("", db.compiledSectionDao().get("today")?.content.orEmpty())
        val staleWeek = db.scopedCompiledSectionDao().get("week", "main", "default")
        assertEquals("无关的过期周报", staleWeek?.content)
        assertEquals("STALE", staleWeek?.status)
    }

    @Test
    fun `restore rejects when a later compile changed a dream section`() = runTest {
        seed("facts", "旧事实", "READY")
        val service = DefaultMemoryDreamService(db = db, planner = plannerChanging("facts" to "新事实"))
        val runId = (
            service.start(
                scope,
                DreamTrigger.MANUAL,
                mapOf("facts" to "旧事实"),
            ) as DreamStartResult.Started
            ).runId
        // 模拟 Dream 之后定时编译又改过这一段。
        db.scopedCompiledSectionDao().updateContent(
            "facts",
            "main",
            "default",
            "之后编译出的新内容",
            null,
            Instant.now().toString(),
        )

        val restored = service.restore(scope, runId)

        assertTrue(restored is DreamRestoreResult.Rejected)
        assertEquals(
            "memory changed since dream",
            (restored as DreamRestoreResult.Rejected).reason,
        )
        assertEquals(
            "之后编译出的新内容",
            db.scopedCompiledSectionDao().get("facts", "main", "default")?.content,
        )
    }

    @Test
    fun `stale running state is repaired on first hydrate`() = runTest {
        db.memoryDreamDao().upsertState(
            MemoryDreamStateEntity(
                assistantId = "default",
                scope = "main",
                spaceId = "default",
                running = true,
                runId = "run-from-killed-process",
                lastRevisionId = null,
                activeRevisionId = null,
                lastError = null,
            ),
        )
        val service = DefaultMemoryDreamService(db = db)

        val status = service.status(scope)

        assertFalse(status.running)
        assertEquals("previous dream run interrupted", status.lastError)
        assertFalse(db.memoryDreamDao().state("default", "main", "default")?.running ?: true)
        // 修复后必须能重新启动，而不是继续被「已在运行」挡住。
        val retried = service.start(scope, DreamTrigger.MANUAL, mapOf("facts" to "旧事实"))
        assertTrue(retried is DreamStartResult.Started)
    }

    @Test
    fun `cancelled start persists non-running state so it can be retried`() = runTest {
        val cancelledPlanner = object : MemoryDreamPlanner {
            override suspend fun plan(current: Map<String, String>): MemoryDreamPlan = throw CancellationException("user stopped")
        }
        val service = DefaultMemoryDreamService(db = db, planner = cancelledPlanner)

        try {
            service.start(scope, DreamTrigger.MANUAL, mapOf("facts" to "旧事实"))
        } catch (_: CancellationException) {
            // 取消必须原样抛出；这里关心的是持久化状态有没有收尾。
        }

        val state = db.memoryDreamDao().state("default", "main", "default")
        assertFalse(state?.running ?: true)
        assertEquals("cancelled", state?.lastError)
    }

    private suspend fun seed(key: String, content: String, status: String) {
        db.scopedCompiledSectionDao().upsert(
            ScopedCompiledSectionEntity(
                sectionKey = key,
                scope = "main",
                spaceId = "default",
                content = content,
                fingerprint = null,
                updatedAt = Instant.now().toString(),
                status = status,
            ),
        )
    }

    private fun plannerChanging(vararg sections: Pair<String, String>) = object : MemoryDreamPlanner {
        override suspend fun plan(current: Map<String, String>): MemoryDreamPlan = MemoryDreamPlan(sections = sections.toMap())
    }
}
