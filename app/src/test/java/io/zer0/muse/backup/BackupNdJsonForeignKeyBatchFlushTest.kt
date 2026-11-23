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
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.data.session.ConversationEventEntity
import io.zer0.muse.data.session.ConversationTurnEntity
import io.zer0.muse.data.session.MessageEntity
import io.zer0.muse.data.session.MessageImageStore
import io.zer0.muse.data.session.MessagePartEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.data.session.ToolRoundEntity
import io.zer0.muse.data.stats.AutoBackupLogDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedWriter
import java.io.File
import java.io.StringWriter
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * 守护 NDJSON 流式恢复在启用 SQLite 外键约束时的父子表刷盘顺序。
 *
 * 生产库 [MuseDb] 在 onOpen 时启用 `setForeignKeyConstraintsEnabled(true)`。
 * 当父表条数少于单批上限（500）仍滞留在内存缓冲、而子表条数达到 500 触发中途刷盘时，
 * 必须先将父表缓冲落库，否则会触发 `SQLITE_CONSTRAINT_FOREIGNKEY (787)` 并整体回滚。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupNdJsonForeignKeyBatchFlushTest {

    private lateinit var context: Context
    private lateinit var sourceDb: MuseDb
    private lateinit var targetDb: MuseDb
    private lateinit var memoryDb: MemoryDb
    private lateinit var factDb: FactDb
    private lateinit var imageStore: MessageImageStore
    private lateinit var tempDir: File

    private val backupJson = Json { encodeDefaults = true }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "restore-journal.json").delete()
        File(context.filesDir, "restore-staging").deleteRecursively()
        sourceDb = openFkEnforcedMuseDb()
        targetDb = openFkEnforcedMuseDb()
        memoryDb = Room.inMemoryDatabaseBuilder(context, MemoryDb::class.java)
            .allowMainThreadQueries()
            .build()
        factDb = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()
        tempDir = Files.createTempDirectory("backup-fk-batch-flush").toFile()
        imageStore = MessageImageStore(tempDir)
    }

    @After
    fun tearDown() {
        sourceDb.close()
        targetDb.close()
        memoryDb.close()
        factDb.close()
        tempDir.deleteRecursively()
        File(context.filesDir, "restore-journal.json").delete()
        File(context.filesDir, "restore-staging").deleteRecursively()
    }

    @Test
    fun `ndjson round trip restores fewer than 500 sessions with over 500 messages under enforced foreign keys`() = runBlocking {
        // 模拟手机端：2 个会话 + 520 条消息（会话 < 500，消息 >= 500）
        val s1 = SessionEntity(id = "phone-s1", title = "日常", createdAt = 100L, updatedAt = 200L)
        val s2 = SessionEntity(id = "phone-s2", title = "工作", createdAt = 101L, updatedAt = 201L)
        sourceDb.sessionDao().insert(s1)
        sourceDb.sessionDao().insert(s2)
        val sourceMessages = (1..520).map { idx ->
            MessageEntity(
                id = "phone-m-$idx",
                sessionId = if (idx % 2 == 0) s1.id else s2.id,
                role = if (idx % 2 == 0) "USER" else "ASSISTANT",
                content = "message-$idx",
                createdAt = 1_000L + idx,
                seq = idx.toLong(),
            )
        }
        sourceDb.messageDao().upsertAll(sourceMessages)

        // 模拟平板端现有数据：1 个会话 + 53 条消息
        val tabletSession = SessionEntity(id = "tablet-s1", title = "平板旧会话", createdAt = 50L, updatedAt = 60L)
        targetDb.sessionDao().insert(tabletSession)
        targetDb.messageDao().upsertAll(
            (1..53).map { idx ->
                MessageEntity(
                    id = "tablet-m-$idx",
                    sessionId = tabletSession.id,
                    role = "USER",
                    content = "tablet-$idx",
                    createdAt = 500L + idx,
                    seq = idx.toLong(),
                )
            },
        )

        val ndjsonText = exportNdJsonText(newService(sourceDb))
        val (restoredSessions, restoredMessages) = invokeNdJsonRecovery(
            newService(targetDb),
            ndjsonText.lineSequence().filter { it.isNotBlank() },
        )

        assertEquals(2, restoredSessions)
        assertEquals(520, restoredMessages)
        assertEquals(setOf(s1.id, s2.id), targetDb.sessionDao().observeAll().first().map { it.id }.toSet())
        assertEquals(260, targetDb.messageDao().observeBySession(s1.id).first().size)
        assertEquals(260, targetDb.messageDao().observeBySession(s2.id).first().size)
    }

    @Test
    fun `ndjson streaming flushes parent buffers before multi-level fk child batches hit 500`() = runBlocking {
        // 父表均远小于 500：2 个会话、4 条消息、2 个回合；
        // 子表全部超过 500 触发中途刷盘：505 messageParts、505 toolRounds、505 conversationEvents。
        val sessions = listOf(
            SessionEntity(id = "s-1", title = "S1", createdAt = 1L, updatedAt = 1L),
            SessionEntity(id = "s-2", title = "S2", createdAt = 2L, updatedAt = 2L),
        )
        val messages = (1..4).map { idx ->
            MessageEntity(
                id = "m-$idx",
                sessionId = if (idx <= 2) "s-1" else "s-2",
                role = "ASSISTANT",
                content = "body-$idx",
                createdAt = 10L + idx,
                seq = idx.toLong(),
            )
        }
        val turns = listOf(
            ConversationTurnEntity(
                turnId = "t-1",
                sessionId = "s-1",
                inputUserMessageId = "m-1",
                assistantMessageId = "m-2",
                phase = "COMPLETED",
                startedAt = 10L,
                updatedAt = 20L,
            ),
            ConversationTurnEntity(
                turnId = "t-2",
                sessionId = "s-2",
                inputUserMessageId = "m-3",
                assistantMessageId = "m-4",
                phase = "COMPLETED",
                startedAt = 30L,
                updatedAt = 40L,
            ),
        )
        val events = (1..505).map { idx ->
            ConversationEventEntity(
                sessionId = if (idx % 2 == 0) "s-1" else "s-2",
                eventSeq = idx.toLong(),
                eventId = "ev-$idx",
                turnId = if (idx % 2 == 0) "t-1" else "t-2",
                type = "TURN_STARTED",
                payloadJson = "{}",
                payloadHash = "h-$idx",
                payloadLength = 2,
                createdAt = 100L + idx,
            )
        }
        val parts = (1..505).map { idx ->
            MessagePartEntity(
                messageId = "m-${(idx % 4) + 1}",
                partIndex = idx,
                kind = "TEXT",
                text = "part-$idx",
                createdAt = 200L + idx,
            )
        }
        val toolRounds = (1..505).map { idx ->
            ToolRoundEntity(
                id = "tr-$idx",
                turnId = if (idx % 2 == 0) "t-1" else "t-2",
                roundIndex = idx,
                toolCallId = "call-$idx",
                toolName = "echo",
                argsJson = "{}",
                status = "OK",
                startedAt = 300L + idx,
            )
        }

        val lines = buildList {
            add(
                buildJsonObject {
                    put("type", "meta")
                    put("version", 5)
                    put("sessions", sessions.size)
                    put("messages", messages.size)
                    put("conversationEvents", events.size)
                    put("conversationTurns", turns.size)
                    put("messageParts", parts.size)
                    put("toolRounds", toolRounds.size)
                }.toString(),
            )
            sessions.forEach { add(dataLine("session", SessionEntity.serializer(), it)) }
            messages.forEach { add(dataLine("message", MessageEntity.serializer(), it)) }
            events.forEach { add(dataLine("conversationEvent", ConversationEventEntity.serializer(), it)) }
            turns.forEach { add(dataLine("conversationTurn", ConversationTurnEntity.serializer(), it)) }
            parts.forEach { add(dataLine("messagePart", MessagePartEntity.serializer(), it)) }
            toolRounds.forEach { add(dataLine("toolRound", ToolRoundEntity.serializer(), it)) }
        }

        val (restoredSessions, restoredMessages) = invokeNdJsonRecovery(newService(targetDb), lines.asSequence())

        assertEquals(2, restoredSessions)
        assertEquals(4, restoredMessages)
        assertEquals(505, targetDb.conversationEventDao().getAll().size)
        assertEquals(2, targetDb.conversationTurnDao().getAll().size)
        assertEquals(505, targetDb.messagePartDao().getAll().size)
        assertEquals(505, targetDb.toolRoundDao().getAll().size)
    }

    @Test
    fun `skipping legacy miniphone session also filters its fk child tables in ndjson and json restore`() = runBlocking {
        val normalSession = SessionEntity(id = "s-keep", title = "Keep", createdAt = 1L, updatedAt = 1L)
        val miniSession = SessionEntity(id = "s-mini", title = "Mini", createdAt = 2L, updatedAt = 2L, isMiniPhone = true)
        val normalMsg = MessageEntity(id = "m-keep", sessionId = "s-keep", role = "USER", content = "ok", createdAt = 3L)
        val miniMsg = MessageEntity(id = "m-mini", sessionId = "s-mini", role = "USER", content = "skip", createdAt = 4L)
        val miniArtifact = ArtifactEntity(
            id = "a-mini",
            sessionId = "s-mini",
            messageId = "m-mini",
            type = "text",
            title = "mini",
            content = "x",
            createdAt = 5L,
        )
        val miniTurn = ConversationTurnEntity(
            turnId = "t-mini",
            sessionId = "s-mini",
            inputUserMessageId = "m-mini",
            assistantMessageId = "m-mini",
            phase = "COMPLETED",
            startedAt = 5L,
            updatedAt = 6L,
        )
        val miniEvent = ConversationEventEntity(
            sessionId = "s-mini",
            eventSeq = 1L,
            eventId = "ev-mini",
            turnId = "t-mini",
            type = "TURN_STARTED",
            payloadJson = "{}",
            payloadHash = "h",
            payloadLength = 2,
            createdAt = 5L,
        )
        val miniPart = MessagePartEntity(
            messageId = "m-mini",
            partIndex = 0,
            kind = "TEXT",
            text = "skip",
            createdAt = 5L,
        )
        val miniToolRound = ToolRoundEntity(
            id = "tr-mini",
            turnId = "t-mini",
            roundIndex = 0,
            toolCallId = "c-mini",
            toolName = "echo",
            argsJson = "{}",
            status = "OK",
            startedAt = 5L,
        )

        val ndjsonLines = listOf(
            buildJsonObject {
                put("type", "meta")
                put("version", 5)
                put("sessions", 2)
                put("messages", 2)
                put("artifacts", 1)
                put("conversationEvents", 1)
                put("conversationTurns", 1)
                put("messageParts", 1)
                put("toolRounds", 1)
            }.toString(),
            dataLine("session", SessionEntity.serializer(), normalSession),
            dataLine("session", SessionEntity.serializer(), miniSession),
            dataLine("message", MessageEntity.serializer(), normalMsg),
            dataLine("message", MessageEntity.serializer(), miniMsg),
            dataLine("artifact", ArtifactEntity.serializer(), miniArtifact),
            dataLine("conversationEvent", ConversationEventEntity.serializer(), miniEvent),
            dataLine("conversationTurn", ConversationTurnEntity.serializer(), miniTurn),
            dataLine("messagePart", MessagePartEntity.serializer(), miniPart),
            dataLine("toolRound", ToolRoundEntity.serializer(), miniToolRound),
        )

        val service = newService(targetDb)
        invokeNdJsonRecovery(service, ndjsonLines.asSequence())
        assertEquals(listOf("s-keep"), targetDb.sessionDao().observeAll().first().map { it.id })
        assertNull(targetDb.messageDao().getByMessageId("m-mini"))
        assertEquals(0, targetDb.artifactDao().getAll().size)
        assertEquals(0, targetDb.conversationEventDao().getAll().size)
        assertEquals(0, targetDb.conversationTurnDao().getAll().size)
        assertEquals(0, targetDb.messagePartDao().getAll().size)
        assertEquals(0, targetDb.toolRoundDao().getAll().size)

        // 单 JSON 恢复路径同样跳过旧小手机子表，不触发外键约束异常
        val backup = BackupService.Backup(
            exportedAt = 10L,
            sessions = listOf(normalSession, miniSession),
            messages = listOf(normalMsg, miniMsg),
            artifacts = listOf(miniArtifact),
            conversationEvents = listOf(miniEvent),
            conversationTurns = listOf(miniTurn),
            messageParts = listOf(miniPart),
            toolRounds = listOf(miniToolRound),
        )
        invokeApplyBackupInternal(service, backup)
        assertEquals(listOf("s-keep"), targetDb.sessionDao().observeAll().first().map { it.id })
        assertEquals(0, targetDb.artifactDao().getAll().size)
        assertEquals(0, targetDb.conversationEventDao().getAll().size)
        assertEquals(0, targetDb.conversationTurnDao().getAll().size)
        assertEquals(0, targetDb.messagePartDao().getAll().size)
        assertEquals(0, targetDb.toolRoundDao().getAll().size)
    }

    private fun openFkEnforcedMuseDb(): MuseDb = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
        .allowMainThreadQueries()
        .addCallback(
            object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.setForeignKeyConstraintsEnabled(true)
                }
            },
        )
        .build()

    private fun newService(db: MuseDb): BackupService {
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.cloudBackupConfigFlow } returns flowOf(CloudBackupConfig())
        val sessionRepository = mockk<SessionRepository>(relaxed = true)
        return BackupService(
            db = db,
            memoryDb = memoryDb,
            factDb = factDb,
            cloudBackupService = mockk(relaxed = true),
            settings = settings,
            autoBackupLogDao = mockk<AutoBackupLogDao>(relaxed = true),
            sessionRepository = sessionRepository,
            restoreJournal = RestoreJournal(context),
            restoreStagingStore = RestoreStagingStore(context),
            context = context,
            factStore = mockk<FactStore>(relaxed = true),
            factDbProvider = mockk<FactDbProvider>(relaxed = true),
            messageImageStore = imageStore,
        )
    }

    private fun <T> dataLine(type: String, serializer: kotlinx.serialization.KSerializer<T>, value: T): String = buildJsonObject {
        put("type", type)
        put("data", backupJson.encodeToJsonElement(serializer, value))
    }.toString()

    private suspend fun exportNdJsonText(service: BackupService): String {
        val out = StringWriter()
        BufferedWriter(out).use { writer ->
            suspendCoroutine<Unit> { continuation ->
                val method = BackupService::class.java.getDeclaredMethod(
                    "writeNdJson",
                    BufferedWriter::class.java,
                    Boolean::class.javaPrimitiveType,
                    Continuation::class.java,
                ).apply { isAccessible = true }
                try {
                    val result = method.invoke(service, writer, false, continuation)
                    if (result !== COROUTINE_SUSPENDED) {
                        continuation.resume(Unit)
                    }
                } catch (error: InvocationTargetException) {
                    continuation.resumeWithException(error.targetException)
                } catch (error: Throwable) {
                    continuation.resumeWithException(error)
                }
            }
            writer.flush()
        }
        return out.toString()
    }

    private suspend fun invokeNdJsonRecovery(service: BackupService, lines: Sequence<String>): Pair<Int, Int> =
        suspendCoroutine { continuation ->
            val method = BackupService::class.java.getDeclaredMethod(
                "applyNdJsonStreamingWithRecovery",
                Sequence::class.java,
                Continuation::class.java,
            ).apply { isAccessible = true }
            try {
                val result = method.invoke(service, lines, continuation)
                if (result !== COROUTINE_SUSPENDED) {
                    val pair = result as? Pair<*, *> ?: error("unexpected restore result: $result")
                    val sessions = pair.first as? Int ?: error("unexpected session count: ${pair.first}")
                    val messages = pair.second as? Int ?: error("unexpected message count: ${pair.second}")
                    continuation.resume(sessions to messages)
                }
            } catch (error: InvocationTargetException) {
                continuation.resumeWithException(error.targetException)
            } catch (error: Throwable) {
                continuation.resumeWithException(error)
            }
        }

    private suspend fun invokeApplyBackupInternal(service: BackupService, backup: BackupService.Backup): Pair<Int, Int> =
        suspendCoroutine { continuation ->
            val method = BackupService::class.java.getDeclaredMethod(
                "applyBackupInternal",
                BackupService.Backup::class.java,
                Continuation::class.java,
            ).apply { isAccessible = true }
            try {
                val result = method.invoke(service, backup, continuation)
                if (result !== COROUTINE_SUSPENDED) {
                    val pair = result as? Pair<*, *> ?: error("unexpected restore result: $result")
                    val sessions = pair.first as? Int ?: error("unexpected session count: ${pair.first}")
                    val messages = pair.second as? Int ?: error("unexpected message count: ${pair.second}")
                    continuation.resume(sessions to messages)
                }
            } catch (error: InvocationTargetException) {
                continuation.resumeWithException(error.targetException)
            } catch (error: Throwable) {
                continuation.resumeWithException(error)
            }
        }
}
