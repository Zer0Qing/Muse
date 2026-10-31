package io.zer0.muse.data.memory

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.Model
import io.zer0.memory.compile.MemoryCompileTarget
import io.zer0.memory.compile.MemoryCompiler
import io.zer0.memory.compile.MemoryFileWriter
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.reflection.MemoryContradictionStore
import io.zer0.memory.summary.MemoryDb
import io.zer0.memory.summary.ScopedCompiledSectionEntity
import io.zer0.memory.summary.SessionSummaryEntity
import io.zer0.muse.data.groupchat.GroupChatMemoryEntity
import io.zer0.muse.data.session.MessageFtsDdl
import io.zer0.muse.data.session.MessageFtsRuntime
import io.zer0.muse.data.session.MuseDb
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
import java.io.File
import java.util.UUID

/**
 * 会话/助手删除后的记忆清理回归测试。
 *
 * 覆盖三件容易退化的性质:
 *  - 删会话只清该会话的摘要与事实,别的会话不受影响;
 *  - 清理幂等 —— 删两次不抛、不重复计数;
 *  - 删完写墓碑 —— 下一轮编译不会把删掉的内容当"摘要孤儿"补录回表。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionMemoryCleanupTest {

    private lateinit var context: Context
    private lateinit var memoryDb: MemoryDb
    private lateinit var museDb: MuseDb
    private lateinit var factProvider: FactDbProvider
    private lateinit var filesRoot: File
    private lateinit var contradictions: MemoryContradictionStore
    private lateinit var cleanup: SessionMemoryCleanup
    private var previousFtsMode: Boolean = false

    private class NoopLlm : MemoryLlmClient {
        override suspend fun callText(
            systemPrompt: String,
            userContent: String,
            model: Model?,
            temperature: Float,
            maxTokens: Int,
            timeoutMs: Long,
        ): String = ""
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        previousFtsMode = MessageFtsRuntime.useFts5
        MessageFtsRuntime.useFts5 = false
        memoryDb = Room.inMemoryDatabaseBuilder(context, MemoryDb::class.java)
            .allowMainThreadQueries()
            .build()
        museDb = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(MessageFtsDdl.createSql(useFts5 = false))
                    }

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.setForeignKeyConstraintsEnabled(true)
                    }
                },
            )
            .build()
        factProvider = FactDbProvider(context)
        filesRoot = File(context.cacheDir, "memory-cleanup-${UUID.randomUUID()}")
        contradictions = MemoryContradictionStore(File(filesRoot, "memory_contradictions.json"))
        cleanup = SessionMemoryCleanup(
            summaryDao = memoryDb.sessionSummaryDao(),
            checkpointDao = memoryDb.contextCheckpointDao(),
            scopedSectionDao = memoryDb.scopedCompiledSectionDao(),
            groupChatMemoryDao = museDb.groupChatMemoryDao(),
            factDbProvider = factProvider,
            fileWriter = MemoryFileWriter(filesRoot),
            contradictionStore = contradictions,
        )
    }

    @After
    fun tearDown() {
        factProvider.releaseAll()
        museDb.close()
        memoryDb.close()
        filesRoot.deleteRecursively()
        MessageFtsRuntime.useFts5 = previousFtsMode
    }

    @Test
    fun `cleanSessions drops only the deleted session summaries and facts`() = runTest {
        val deleted = "session-deleted"
        val kept = "session-kept"
        memoryDb.sessionSummaryDao().upsert(summary(deleted, assistantId = "default"))
        memoryDb.sessionSummaryDao().upsert(summary(kept, assistantId = "default"))

        // 主库两条 + 分库一条属于被删会话；分库里另一条属于保留会话。
        val mainStore = factProvider.getFactStore("default")
        val subStore = factProvider.getFactStore("assistant_a")
        mainStore.add(FactStore.Fact(fact = "用户喜欢蓝色主题", sessionId = deleted), scope = "main")
        mainStore.add(FactStore.Fact(fact = "用户下周三有考试", sessionId = deleted), scope = "main")
        subStore.add(FactStore.Fact(fact = "子助手记下的偏好", sessionId = deleted), scope = "assistant_a")
        subStore.add(FactStore.Fact(fact = "保留会话的事实", sessionId = kept), scope = "assistant_a")

        cleanup.cleanSessions(listOf(deleted))

        assertEquals("被删会话不能留下摘要", 1, memoryDb.sessionSummaryDao().getAll().size)
        assertEquals(kept, memoryDb.sessionSummaryDao().getAll().single().sessionId)
        assertEquals("主库里该会话的事实要清空", 0, mainStore.getBySession(deleted).size)
        assertEquals("分库里该会话的事实同样要清空", 0, subStore.getBySession(deleted).size)
        assertEquals("别的会话的事实不受影响", 1, subStore.getBySession(kept).size)
    }

    @Test
    fun `cleanSessions is idempotent`() = runTest {
        val sessionId = "session-twice"
        memoryDb.sessionSummaryDao().upsert(summary(sessionId, assistantId = "default"))
        val store = factProvider.getFactStore("default")
        store.add(FactStore.Fact(fact = "用户养了一只叫团子的猫", sessionId = sessionId), scope = "main")
        store.add(FactStore.Fact(fact = "用户每天七点起床", sessionId = sessionId), scope = "main")

        cleanup.cleanSessions(listOf(sessionId))
        val tombstonesAfterFirst = store.getTombstones("main", "default").size
        // 第二次调用不得抛异常,也不得把计数/墓碑再翻一遍
        cleanup.cleanSessions(listOf(sessionId))

        assertEquals(0, store.getBySession(sessionId).size)
        assertEquals("重复清理不应重复写墓碑", tombstonesAfterFirst, store.getTombstones("main", "default").size)
        assertEquals(2, tombstonesAfterFirst)
    }

    @Test
    fun `cleaned facts stay deleted through the next compile pass`() = runTest {
        val sessionId = "session-tombstone"
        val deletedLine = "用户喜欢蓝色主题"
        val target = MemoryCompileTarget(assistantId = "default", scope = "main", spaceId = "default")
        val store = factProvider.getFactStore("default")
        store.add(FactStore.Fact(fact = deletedLine, sessionId = sessionId), scope = "main")
        // 删除前的编译产物里还留着这一行 —— 复活的入口正是"段内容被当作摘要孤儿补录回表"。
        memoryDb.scopedCompiledSectionDao().upsert(
            ScopedCompiledSectionEntity(
                sectionKey = MemoryCompiler.Section.FACTS.key,
                scope = "main",
                spaceId = "default",
                content = "- $deletedLine",
                updatedAt = "2026-01-01T00:00:00Z",
            ),
        )

        cleanup.cleanSessions(listOf(sessionId))

        assertTrue(
            "删事实必须留下墓碑,否则编译侧会把它补录回来",
            store.getTombstones("main", "default").any { it.contains("蓝色主题") },
        )

        // 模拟下一轮编译对账
        val compiler = MemoryCompiler(
            sectionDao = memoryDb.compiledSectionDao(),
            llmClient = NoopLlm(),
            fileWriter = null,
            factStore = store,
            scopedSectionDao = memoryDb.scopedCompiledSectionDao(),
            factDbProvider = factProvider,
        )
        compiler.reconcileFactsSectionWithStore(emptyList(), target)

        assertFalse(
            "墓碑命中后已删事实不得复活",
            store.getAll("main").any { it.fact.contains("蓝色主题") },
        )
    }

    @Test
    fun `cleanAssistant drops only the target assistant memory`() = runTest {
        val doomed = "assistant_doomed"
        val kept = "assistant_kept"
        memoryDb.sessionSummaryDao().upsert(summary("s-doomed", assistantId = doomed))
        memoryDb.sessionSummaryDao().upsert(summary("s-kept", assistantId = kept))
        memoryDb.scopedCompiledSectionDao().upsert(section(doomed))
        memoryDb.scopedCompiledSectionDao().upsert(section(kept))
        museDb.groupChatMemoryDao().insert(groupChatMemory("g-doomed", doomed))
        museDb.groupChatMemoryDao().insert(groupChatMemory("g-kept", kept))
        contradictions.save(doomed, "default", listOf("旧说法" to "新说法"))
        contradictions.save(kept, "default", listOf("保留的说法" to "另一个说法"))

        cleanup.cleanAssistant(doomed)

        assertEquals(
            "只删目标助手的摘要",
            listOf(kept),
            memoryDb.sessionSummaryDao().getAll().map { it.assistantId },
        )
        assertEquals(
            "只删目标作用域的编译段落",
            listOf(kept),
            memoryDb.scopedCompiledSectionDao().getAll().map { it.scope },
        )
        assertEquals(
            "只删目标助手的群聊记忆",
            listOf(kept),
            museDb.groupChatMemoryDao().getAll().map { it.assistantId },
        )
        assertTrue("目标助手的矛盾清单要清空", contradictions.list(doomed, "default").isEmpty())
        assertTrue("其他助手的矛盾清单要保留", contradictions.list(kept, "default").isNotEmpty())
    }

    @Test
    fun `cleanAssistant deletes the scope directory but keeps memory md`() = runTest {
        val doomed = "assistant_dir"
        val memoryDir = File(filesRoot, "memory")
        val rootMd = File(memoryDir, "memory.md").apply {
            parentFile?.mkdirs()
            writeText("# 主记忆")
        }
        val doomedDir = File(memoryDir, "$doomed/default").apply {
            mkdirs()
            File(this, "memory.md").writeText("# 待删助手的记忆")
            File(this, "daily").mkdirs()
            File(this, "daily/2026-01-01.md").writeText("## 2026-01-01\n\n正文")
        }
        val keptDir = File(memoryDir, "assistant_keep/default").apply {
            mkdirs()
            File(this, "memory.md").writeText("# 保留助手的记忆")
        }

        cleanup.cleanAssistant(doomed)

        assertFalse("目标 scope 目录要被删掉", doomedDir.exists())
        assertTrue("根上的 memory.md 不属于任何 scope,必须保留", rootMd.exists())
        assertTrue("其他 scope 目录不受影响", keptDir.exists())
    }

    private fun summary(sessionId: String, assistantId: String) = SessionSummaryEntity(
        sessionId = sessionId,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        summary = "### 重要事实\n- 用户喜欢蓝色主题",
        assistantId = assistantId,
    )

    private fun section(assistantId: String) = ScopedCompiledSectionEntity(
        sectionKey = MemoryCompiler.Section.FACTS.key,
        scope = assistantId,
        spaceId = "default",
        content = "- 某个事实",
        updatedAt = "2026-01-01T00:00:00Z",
    )

    private fun groupChatMemory(id: String, assistantId: String) = GroupChatMemoryEntity(
        id = id,
        groupChatId = "group-1",
        assistantId = assistantId,
        summary = "群聊里聊过的事",
    )
}
