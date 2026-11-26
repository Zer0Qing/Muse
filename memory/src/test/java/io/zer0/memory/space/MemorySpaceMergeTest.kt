package io.zer0.memory.space

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.Model
import io.zer0.memory.compile.MemoryCompileTarget
import io.zer0.memory.compile.MemoryCompiler
import io.zer0.memory.compile.MemoryFileWriter
import io.zer0.memory.dream.MemoryDreamRevisionEntity
import io.zer0.memory.dream.MemoryDreamStateEntity
import io.zer0.memory.fact.FactDb
import io.zer0.memory.fact.FactEntity
import io.zer0.memory.fact.FactProjectionEntity
import io.zer0.memory.fact.FactStore
import io.zer0.memory.fact.FactTombstoneEntity
import io.zer0.memory.fact.ProfileSection
import io.zer0.memory.fact.ProfileSnapshotEntity
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.reflection.MemoryContradictionStore
import io.zer0.memory.runtime.DefaultMemoryWriteGate
import io.zer0.memory.runtime.MemoryWriteGate
import io.zer0.memory.summary.DailyStateEntity
import io.zer0.memory.summary.MemoryDb
import io.zer0.memory.summary.ScopedCompiledSectionEntity
import io.zer0.memory.summary.SessionSummaryEntity
import kotlinx.coroutines.runBlocking
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
import java.io.File
import java.nio.file.Files

/**
 * 删除 Space = 合并进 default。
 *
 * 这套测试钉的是「别丢东西」：事实、删除标记、画像、摘要、矛盾清单、daily 文件是内容，
 * 必须一条不少地出现在 default；TODAY/WEEK/LONGTERM、每日进度、Dream 回滚点是派生物，
 * 必须失效或清掉 —— 保留旧文本等于让「按另一个空间算出来的结论」继续注入，或让一次回滚
 * 盖掉刚并进来的内容。
 *
 * 用真内存库 + 真文件跑：UUID 键冲突、唯一索引、日期合并这些都不是靠假 DAO 能测出来的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemorySpaceMergeTest {

    private lateinit var mainDb: FactDb
    private lateinit var shardDb: FactDb
    private lateinit var memoryDb: MemoryDb
    private lateinit var root: File
    private lateinit var writer: MemoryFileWriter
    private lateinit var contradictions: MemoryContradictionStore
    private lateinit var repository: MemorySpaceRepository
    private var sourceSpaceId: String = ""

    /** 合并路径不该调模型：FACTS 重建是确定性投影。 */
    private object NoLlm : MemoryLlmClient {
        override suspend fun callText(
            systemPrompt: String,
            userContent: String,
            model: Model?,
            temperature: Float,
            maxTokens: Int,
            timeoutMs: Long,
        ): String = error("Space 合并不该调用 LLM")
    }

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        mainDb = newFactDb(context)
        // 第二个库代表某个助手的 facts_<id>.db：只合主库会让被删空间的事实留在分库成孤儿。
        shardDb = newFactDb(context)
        memoryDb = Room.inMemoryDatabaseBuilder(context, MemoryDb::class.java)
            .allowMainThreadQueries()
            .build()
        root = Files.createTempDirectory("muse-space-merge").toFile()
        writer = MemoryFileWriter(root)
        contradictions = MemoryContradictionStore(File(root, "memory_contradictions.json"))
        repository = newRepository(writeGate = null)
        repository.ensureDefaultSpaceExists()
        sourceSpaceId = requireNotNull(repository.createSpace("工作")) { "建不出测试用的空间" }
    }

    @After
    fun tearDown() {
        mainDb.close()
        shardDb.close()
        memoryDb.close()
        root.deleteRecursively()
    }

    private fun newFactDb(context: Context): FactDb =
        Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()

    private fun newCompiler(): MemoryCompiler = MemoryCompiler(
        sectionDao = memoryDb.compiledSectionDao(),
        llmClient = NoLlm,
        fileWriter = writer,
        factStore = FactStore(mainDb.factDao(), mainDb),
        scopedSectionDao = memoryDb.scopedCompiledSectionDao(),
    )

    private fun newRepository(writeGate: MemoryWriteGate?): MemorySpaceRepository = MemorySpaceRepository(
        spaceDao = mainDb.memorySpaceDao(),
        memoryLinkDao = mainDb.memoryLinkDao(),
        mergeDeps = MemorySpaceMergeDeps(
            factDbs = { listOf(mainDb, shardDb) },
            memoryDb = memoryDb,
            fileWriter = writer,
            contradictions = contradictions,
            compiler = newCompiler(),
            writeGate = writeGate,
        ),
    )

    private suspend fun fact(text: String, spaceId: String, db: FactDb = mainDb): Long =
        db.factDao().insert(FactEntity(fact = text, createdAt = NOW, spaceId = spaceId))

    @Test
    fun `事实 墓碑 画像 投影 图谱边都并进默认空间`() = runBlocking {
        val first = fact("用户习惯周五下午开会", sourceSpaceId)
        val second = fact("用户常去城南咖啡馆", sourceSpaceId)
        fact("用户在学日语", sourceSpaceId, shardDb)
        mainDb.memoryLinkDao().insert(
            io.zer0.memory.ai.MemoryLinkEntity(
                sourceFactId = first,
                targetFactId = second,
                sourceTitle = "开会",
                targetTitle = "咖啡馆",
                createdAt = NOW,
            ),
        )
        // default 里已经有同一条删除标记：合并必须靠 INSERT OR IGNORE 跳过，不能撞唯一键。
        mainDb.factTombstoneDao().insertIfAbsent(
            FactTombstoneEntity(normalizedFact = "周五开会", scope = "main", spaceId = "default", deletedAt = NOW),
        )
        mainDb.factTombstoneDao().insertIfAbsent(
            FactTombstoneEntity(normalizedFact = "周五开会", scope = "main", spaceId = sourceSpaceId, deletedAt = NOW),
        )
        mainDb.profileSnapshotDao().upsert(
            ProfileSnapshotEntity(
                scope = "main",
                spaceId = sourceSpaceId,
                section = ProfileSection.STABLE_FACTS,
                autoContent = "- 用户在杭州",
                sourceFactIds = "[\"1\"]",
                evidenceFingerprint = "fp",
                updatedAt = NOW,
            ),
        )
        mainDb.factProjectionDao().upsert(
            FactProjectionEntity(
                generationId = "gen-1",
                sessionId = "s1",
                sourceRevision = 1,
                scope = "main",
                spaceId = sourceSpaceId,
                state = "ACTIVE",
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )

        assertTrue(repository.deleteSpace(sourceSpaceId))

        assertEquals("主库的事实要全在 default", 2, mainDb.memorySpaceDao().countFactsInSpace("default"))
        assertEquals("分库的事实也要一起迁", 1, shardDb.memorySpaceDao().countFactsInSpace("default"))
        assertEquals(0, mainDb.memorySpaceDao().countFactsInSpace(sourceSpaceId))
        assertEquals(0, shardDb.memorySpaceDao().countFactsInSpace(sourceSpaceId))

        assertEquals(
            "重复的删除标记只留一条",
            listOf("周五开会"),
            mainDb.factTombstoneDao().getNormalizedFacts("main", "default"),
        )
        assertTrue(mainDb.factTombstoneDao().getNormalizedFacts("main", sourceSpaceId).isEmpty())

        assertEquals("图谱边要跟着事实走，否则成孤儿", 1, mainDb.memoryLinkDao().listBySpace("default").size)
        assertTrue(mainDb.memoryLinkDao().listBySpace(sourceSpaceId).isEmpty())

        val profile = mainDb.profileSnapshotDao().getOne("main", "default", ProfileSection.STABLE_FACTS)
        assertNotNull("画像整段搬过去", profile)
        assertEquals("- 用户在杭州", profile?.autoContent)
        assertTrue(mainDb.profileSnapshotDao().getAllBySpace(sourceSpaceId).isEmpty())

        assertEquals(
            "投影 generation 也跟着进 default，否则下次发布又会写回已删空间",
            "default",
            mainDb.factProjectionDao().get("gen-1")?.spaceId,
        )
    }

    @Test
    fun `画像合并保留目标覆写 自动正文取较新 来源 id 去重合并`() = runBlocking {
        mainDb.profileSnapshotDao().upsert(
            ProfileSnapshotEntity(
                scope = "main",
                spaceId = "default",
                section = ProfileSection.STABLE_FACTS,
                autoContent = "- 旧自动正文",
                manualContent = "- 用户手改的样子",
                sourceFactIds = "[\"1\",\"2\"]",
                evidenceFingerprint = "fp-old",
                updatedAt = "2026-11-01T00:00:00Z",
                lastRefreshAt = "2026-11-01T00:00:00Z",
            ),
        )
        mainDb.profileSnapshotDao().upsert(
            ProfileSnapshotEntity(
                scope = "main",
                spaceId = sourceSpaceId,
                section = ProfileSection.STABLE_FACTS,
                autoContent = "- 新自动正文",
                manualContent = null,
                sourceFactIds = "[\"2\",\"3\"]",
                evidenceFingerprint = "fp-new",
                updatedAt = "2026-11-20T00:00:00Z",
                lastRefreshAt = "2026-11-20T00:00:00Z",
            ),
        )

        assertTrue(repository.deleteSpace(sourceSpaceId))

        val merged = mainDb.profileSnapshotDao().getOne("main", "default", ProfileSection.STABLE_FACTS)
        assertNotNull(merged)
        assertEquals("覆写是用户手改的样子，不许被源空间盖掉", "- 用户手改的样子", merged?.manualContent)
        assertEquals("自动正文取较新的一侧", "- 新自动正文", merged?.autoContent)
        assertEquals("fp-new", merged?.evidenceFingerprint)
        assertEquals("来源 id 去重合并", "[\"1\",\"2\",\"3\"]", merged?.sourceFactIds)
    }

    @Test
    fun `摘要只换空间 源编译段落删除 default 的 FACTS 按合并结果重建`() = runBlocking {
        memoryDb.sessionSummaryDao().upsert(
            SessionSummaryEntity(
                sessionId = "s1",
                createdAt = NOW,
                updatedAt = NOW,
                summary = "## 重要事实\n- 用户在杭州",
                spaceId = sourceSpaceId,
            ),
        )
        val scoped = memoryDb.scopedCompiledSectionDao()
        scoped.upsert(scopedRow("facts", sourceSpaceId, "- 源空间旧投影"))
        scoped.upsert(scopedRow("today", sourceSpaceId, "源空间的今天"))
        scoped.upsert(scopedRow("facts", "default", "- 默认空间旧投影"))
        scoped.upsert(scopedRow("today", "default", "默认空间的今天"))
        fact("用户习惯周五下午开会", sourceSpaceId)

        assertTrue(repository.deleteSpace(sourceSpaceId))

        assertEquals("摘要只是换了归属空间，正文不动", "default", memoryDb.sessionSummaryDao().get("s1")?.spaceId)
        assertTrue("源空间的段落是派生物，Space 没了就删", scoped.getForScope("main", sourceSpaceId).isEmpty())

        val facts = scoped.get("facts", "main", "default")
        assertNotNull(facts)
        assertTrue(
            "FACTS 必须按合并后的事实表重建，而不是保留源空间的旧文本",
            facts?.content.orEmpty().contains("周五下午开会"),
        )
        assertFalse(facts?.content.orEmpty().contains("源空间旧投影"))
        assertEquals("重建出来的段落是可信状态", "READY", facts?.status)

        val today = scoped.get("today", "main", "default")
        assertEquals("TODAY 没有可立即重建的数据源：内容保留", "默认空间的今天", today?.content)
        assertEquals("但必须标失效，别让旧文本继续被当成可信版本", "STALE", today?.status)
    }

    @Test
    fun `每日进度清掉源与默认两侧 否则合并后当天直接跳过重编`() = runBlocking {
        // 源空间里得真有一条内容，受影响的作用域才推得出来（这里是 main）。
        fact("用户习惯周五下午开会", sourceSpaceId)
        memoryDb.dailyStateDao().upsert(
            DailyStateEntity(key = "main|$sourceSpaceId", logicalDate = "2026-11-20", updatedAt = NOW),
        )
        memoryDb.dailyStateDao().upsert(
            DailyStateEntity(key = "main|default", logicalDate = "2026-11-20", updatedAt = NOW),
        )
        memoryDb.dailyStateDao().upsert(
            DailyStateEntity(key = "assistant-b|default", logicalDate = "2026-11-20", updatedAt = NOW),
        )

        assertTrue(repository.deleteSpace(sourceSpaceId))

        assertNull(memoryDb.dailyStateDao().get("main|$sourceSpaceId"))
        assertNull("default 的完成标记会让当天不再重编，必须清掉", memoryDb.dailyStateDao().get("main|default"))
        assertNotNull(
            "没被这次合并波及的助手不该重跑",
            memoryDb.dailyStateDao().get("assistant-b|default"),
        )
    }

    @Test
    fun `daily 文件按日期并进默认空间 同一天去重后删源目录`() = runBlocking {
        val sourceTarget = MemoryCompileTarget(scope = MAIN_SCOPE, spaceId = sourceSpaceId)
        val defaultTarget = MemoryCompileTarget(scope = MAIN_SCOPE, spaceId = "default")
        writer.writeDailyMd("2026-11-20", "- 默认空间的事", defaultTarget)
        writer.writeDailyMd("2026-11-20", "- 源空间的事\n- 默认空间的事", sourceTarget)
        writer.writeDailyMd("2026-11-18", "- 源空间独有的一天", sourceTarget)

        assertTrue(repository.deleteSpace(sourceSpaceId))

        val merged = writer.readDailyEntryBody("2026-11-20", defaultTarget)
        assertEquals("同一天两边都有：两边的行都要在", 1, merged.lines().count { it.trim() == "- 默认空间的事" })
        assertEquals(1, merged.lines().count { it.trim() == "- 源空间的事" })
        assertEquals(
            "只有源空间有的一天不能丢，week 的 6 日窗口靠它",
            "- 源空间独有的一天",
            writer.readDailyEntryBody("2026-11-18", defaultTarget),
        )
        assertFalse(
            "源目录要删掉，否则下次同名空间会读到上一位的旧 daily",
            File(root, "memory/$MAIN_SCOPE/$sourceSpaceId").exists(),
        )
    }

    @Test
    fun `矛盾清单并入默认空间并按对去重`() = runBlocking {
        contradictions.save("main", sourceSpaceId, listOf("我住杭州" to "我住上海"))
        contradictions.save(
            "main",
            "default",
            listOf("我住杭州" to "我住上海", "养了一只猫" to "没有宠物"),
        )

        assertTrue(repository.deleteSpace(sourceSpaceId))

        val merged = contradictions.list("main", "default").map { it.a to it.b }
        assertEquals("同一对只留一份", 2, merged.size)
        assertTrue(merged.contains("我住杭州" to "我住上海"))
        assertTrue(merged.contains("养了一只猫" to "没有宠物"))
        assertTrue(contradictions.list("main", sourceSpaceId).isEmpty())
    }

    @Test
    fun `Dream 回滚点在源与默认两边都清掉`() = runBlocking {
        val dao = memoryDb.memoryDreamDao()
        dao.insertRevision(revision("rev-source", sourceSpaceId))
        dao.insertRevision(revision("rev-default", "default"))
        dao.upsertState(state("rev-source", sourceSpaceId))
        dao.upsertState(state("rev-default", "default"))

        assertTrue(repository.deleteSpace(sourceSpaceId))

        assertNull(dao.state("default", MAIN_SCOPE, sourceSpaceId))
        assertNull(
            "default 的旧快照也要清：留着它一次回滚就盖掉刚并进来的内容",
            dao.state("default", MAIN_SCOPE, "default"),
        )
        assertNull(dao.findRevision("rev-default", "default", MAIN_SCOPE, "default"))
    }

    @Test
    fun `默认空间删不掉 重复删除返回 false`() = runBlocking {
        assertFalse(repository.deleteSpace(MemorySpaceEntity.DEFAULT_SPACE_ID))
        assertNotNull(repository.getSpace(MemorySpaceEntity.DEFAULT_SPACE_ID))

        assertTrue(repository.deleteSpace(sourceSpaceId))
        assertFalse("第二次已经没有可删的东西，不能假装成功", repository.deleteSpace(sourceSpaceId))
    }

    @Test
    fun `恢复窗口内拒绝删除 恢复结束后同一份数据可以重试`() = runBlocking {
        val gate = DefaultMemoryWriteGate()
        val gated = newRepository(gate)
        val spaceId = requireNotNull(gated.createSpace("学习")) { "建不出测试用的空间" }
        fact("用户在学日语", spaceId)

        gate.open()
        try {
            assertFalse("恢复中写库会被整体替换，合并结果留不住", gated.deleteSpace(spaceId))
            assertNotNull("Space 必须还在，恢复结束后能重试", gated.getSpace(spaceId))
            assertEquals(1, gated.countFactsInSpace(spaceId))
        } finally {
            gate.close()
        }

        assertTrue("恢复结束后同一份数据要能正常合并", gated.deleteSpace(spaceId))
        assertEquals(1, gated.countFactsInSpace("default"))
    }

    private fun scopedRow(section: String, spaceId: String, content: String): ScopedCompiledSectionEntity =
        ScopedCompiledSectionEntity(
            sectionKey = section,
            scope = MAIN_SCOPE,
            spaceId = spaceId,
            content = content,
            updatedAt = NOW,
        )

    private fun revision(revisionId: String, spaceId: String): MemoryDreamRevisionEntity =
        MemoryDreamRevisionEntity(
            revisionId = revisionId,
            assistantId = "default",
            scope = MAIN_SCOPE,
            spaceId = spaceId,
            trigger = "MANUAL",
            beforeFingerprint = "fp",
            beforeJson = "{}",
            nextJson = "{}",
            publishedAt = NOW,
        )

    private fun state(revisionId: String, spaceId: String): MemoryDreamStateEntity =
        MemoryDreamStateEntity(
            assistantId = "default",
            scope = MAIN_SCOPE,
            spaceId = spaceId,
            running = false,
            runId = null,
            lastRevisionId = revisionId,
            activeRevisionId = revisionId,
            lastError = null,
        )

    private companion object {
        const val NOW = "2026-11-20T08:00:00Z"
        const val MAIN_SCOPE = "main"
    }
}
