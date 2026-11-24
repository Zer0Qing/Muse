package io.zer0.muse.backup

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.zer0.memory.fact.FactDb
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.summary.MemoryDb
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.session.ConversationTurnEntity
import io.zer0.muse.data.session.MessageEntity
import io.zer0.muse.data.session.MessageImageStore
import io.zer0.muse.data.session.MessagePartEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.data.stats.AutoBackupLogDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * 叠加导入（[BackupImportMode.MERGE]）的契约。
 *
 * 三条不能被改掉的承诺：
 *  1. 本机原有会话与消息一条都不少；
 *  2. 备份里的对话以**新 id** 落库，引用关系（消息→会话、轮次→消息）整体重映射；
 *  3. 同一份备份导两次得到两份副本，而不是互相覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupMergeImporterTest {

    private lateinit var context: Context
    private lateinit var db: MuseDb
    private lateinit var memoryDb: MemoryDb
    private lateinit var factDb: FactDb
    private lateinit var service: BackupService
    private lateinit var tempDir: File

    private val json = Json { encodeDefaults = true }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "restore-journal.json").delete()
        File(context.filesDir, "restore-staging").deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // 生产库在 onOpen 启用外键：合并必须同样成立，否则真机才会炸。
                        db.setForeignKeyConstraintsEnabled(true)
                    }
                },
            )
            .build()
        memoryDb = Room.inMemoryDatabaseBuilder(context, MemoryDb::class.java)
            .allowMainThreadQueries()
            .build()
        factDb = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()
        tempDir = Files.createTempDirectory("backup-merge").toFile()
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.cloudBackupConfigFlow } returns flowOf(CloudBackupConfig())
        service = BackupService(
            db = db,
            memoryDb = memoryDb,
            factDb = factDb,
            cloudBackupService = mockk(relaxed = true),
            settings = settings,
            autoBackupLogDao = mockk<AutoBackupLogDao>(relaxed = true),
            sessionRepository = mockk<SessionRepository>(relaxed = true),
            restoreJournal = RestoreJournal(context),
            restoreStagingStore = RestoreStagingStore(context),
            context = context,
            factStore = mockk<FactStore>(relaxed = true),
            factDbProvider = mockk<FactDbProvider>(relaxed = true),
            messageImageStore = MessageImageStore(tempDir),
        )
    }

    @After
    fun tearDown() {
        db.close()
        memoryDb.close()
        factDb.close()
        tempDir.deleteRecursively()
        File(context.filesDir, "restore-journal.json").delete()
        File(context.filesDir, "restore-staging").deleteRecursively()
    }

    @Test
    fun `merge keeps existing sessions and adds backup chats with fresh ids`() = runBlocking {
        val mine = SessionEntity(id = "mine-s1", title = "我的会话", createdAt = 10L, updatedAt = 20L)
        db.sessionDao().insert(mine)
        db.messageDao().upsert(
            MessageEntity(
                id = "mine-m1",
                sessionId = mine.id,
                role = "USER",
                content = "本机旧消息",
                createdAt = 11L,
            ),
        )

        val backupLines = ndjson(
            session("src-s1", "备份会话"),
            message("src-m1", "src-s1", "备份里的旧消息"),
        )

        val (sessions, messages) = invokeMerge(backupLines)

        assertEquals(1, sessions)
        assertEquals(1, messages)
        val allSessions = db.sessionDao().observeAll().first()
        assertEquals("本机会话必须原样保留", 2, allSessions.size)
        assertTrue(allSessions.any { it.id == mine.id })
        assertTrue(allSessions.any { it.title == "备份会话" })
        // 新会话不能沿用备份里的 id：否则再导一次会撞主键。
        assertTrue(allSessions.none { it.id == "src-s1" })
        assertEquals("合并进来的会话要重建上下文", true, allSessions.first { it.title == "备份会话" }.warmupPending)
        assertEquals("本机消息不能被删", 1, db.messageDao().observeBySession(mine.id).first().size)
    }

    @Test
    fun `merge remaps message ids and foreign keys so references stay intact`() = runBlocking {
        val backupLines = ndjson(
            session("src-s1", "备份会话"),
            message("src-m1", "src-s1", "第一条"),
            message("src-m2", "src-s1", "第二条"),
        )

        invokeMerge(backupLines)

        val merged = db.sessionDao().observeAll().first().single()
        val mergedMessages = db.messageDao().observeBySession(merged.id).first()
        assertEquals(2, mergedMessages.size)
        // 消息必须挂到新会话 id 上，且自身 id 也换新。
        assertTrue(mergedMessages.all { it.sessionId == merged.id })
        assertTrue(mergedMessages.none { it.id == "src-m1" || it.id == "src-m2" })
        assertEquals(setOf("第一条", "第二条"), mergedMessages.map { it.content }.toSet())
    }

    @Test
    fun `importing the same backup twice yields two independent copies`() = runBlocking {
        val backupLines = ndjson(
            session("src-s1", "备份会话"),
            message("src-m1", "src-s1", "内容"),
        )

        invokeMerge(backupLines)
        invokeMerge(backupLines)

        val allSessions = db.sessionDao().observeAll().first()
        assertEquals("同一份备份导两次应当得到两份副本", 2, allSessions.size)
        assertNotEquals("两次导入的会话 id 必须不同", allSessions[0].id, allSessions[1].id)
        val totalMessages = allSessions.sumOf { db.messageDao().observeBySession(it.id).first().size }
        assertEquals(2, totalMessages)
    }

    @Test
    fun `message referencing a session absent from the backup is skipped instead of orphaned`() = runBlocking {
        // 备份被手工裁剪：只剩消息、没有它所属的会话。
        val backupLines = ndjson(
            session("src-s1", "备份会话"),
            message("src-m1", "src-s1", "正常"),
            message("orphan-m", "missing-session", "孤儿"),
        )

        invokeMerge(backupLines)

        val merged = db.sessionDao().observeAll().first().single()
        val messages = db.messageDao().observeBySession(merged.id).first()
        assertEquals(1, messages.size)
        assertEquals("正常", messages.first().content)
        // 不允许留下指向不存在会话的消息。
        assertEquals(0, db.messageDao().observeBySession("missing-session").first().size)
    }

    @Test
    fun `merge does not import assistants from the backup`() = runBlocking {
        val backupLines = ndjson(
            session("src-s1", "备份会话"),
            message("src-m1", "src-s1", "内容"),
            typeLine(
                "assistant",
                AssistantEntity.serializer(),
                AssistantEntity(id = "src-assistant", name = "备份助手"),
            ),
        )

        invokeMerge(backupLines)

        assertEquals("合并不导入备份里的助手", 0, db.assistantDao().getAll().size)
    }

    @Test
    fun `conversation turn and message part follow the remapped session`() = runBlocking {
        val backupLines = ndjson(
            session("src-s1", "备份会话"),
            message("src-m1", "src-s1", "问题"),
            message("src-m2", "src-s1", "回答"),
            typeLine(
                "conversationTurn",
                ConversationTurnEntity.serializer(),
                ConversationTurnEntity(
                    turnId = "src-t1",
                    sessionId = "src-s1",
                    inputUserMessageId = "src-m1",
                    assistantMessageId = "src-m2",
                    phase = "DONE",
                    startedAt = 1L,
                    updatedAt = 2L,
                ),
            ),
            typeLine(
                "messagePart",
                MessagePartEntity.serializer(),
                MessagePartEntity(
                    messageId = "src-m1",
                    partIndex = 0,
                    kind = "TEXT",
                    text = "问题",
                    createdAt = 1L,
                ),
            ),
        )

        invokeMerge(backupLines)

        val merged = db.sessionDao().observeAll().first().single()
        val messages = db.messageDao().observeBySession(merged.id).first()
        val turns = db.conversationTurnDao().getAll()
        assertEquals(1, turns.size)
        assertEquals("轮次必须指向新会话", merged.id, turns.first().sessionId)
        val messageIds = messages.map { it.id }.toSet()
        assertTrue("轮次的输入消息要指向重映射后的消息", turns.first().inputUserMessageId in messageIds)
        assertTrue("轮次的回复消息要指向重映射后的消息", turns.first().assistantMessageId in messageIds)
        assertEquals(1, db.messagePartDao().getAll().size)
    }

    private suspend fun invokeMerge(lines: Sequence<String>): Pair<Int, Int> = suspendCoroutine { continuation ->
        val method = BackupService::class.java.getDeclaredMethod(
            "applyNdJsonMerge",
            Sequence::class.java,
            Continuation::class.java,
        ).apply { isAccessible = true }
        try {
            val result = method.invoke(service, lines, continuation)
            if (result !== COROUTINE_SUSPENDED) {
                val pair = result as? Pair<*, *> ?: error("unexpected merge result: $result")
                continuation.resume((pair.first as Int) to (pair.second as Int))
            }
        } catch (error: InvocationTargetException) {
            continuation.resumeWithException(error.targetException)
        } catch (error: Throwable) {
            continuation.resumeWithException(error)
        }
    }

    private fun session(id: String, title: String): String = typeLine(
        "session",
        SessionEntity.serializer(),
        SessionEntity(id = id, title = title, createdAt = 100L, updatedAt = 200L),
    )

    private fun message(id: String, sessionId: String, content: String): String = typeLine(
        "message",
        MessageEntity.serializer(),
        MessageEntity(
            id = id,
            sessionId = sessionId,
            role = "USER",
            content = content,
            createdAt = 150L,
        ),
    )

    private fun <T> typeLine(type: String, serializer: KSerializer<T>, value: T): String = buildJsonObject {
        put("type", type)
        put("data", json.encodeToJsonElement(serializer, value))
    }.toString()

    private fun ndjson(vararg lines: String): Sequence<String> =
        (sequenceOf("""{"type":"meta","version":4}""") + lines.asSequence())
            .filter { it.isNotBlank() }
}