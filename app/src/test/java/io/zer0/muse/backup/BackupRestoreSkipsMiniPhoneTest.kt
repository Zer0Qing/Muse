package io.zer0.muse.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.zer0.memory.fact.FactDb
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.summary.MemoryDb
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.session.MessageEntity
import io.zer0.muse.data.session.MessageImageStore
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.SessionRepository
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
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * 恢复侧与迁移侧口径一致的守护：旧小手机的私信会话不进库。
 *
 * 121→122 迁移已经把库里的 `isMiniPhone = 1` 会话连同消息删掉，主列表也不再过滤它们；
 * 若恢复路径把旧备份里的这些行原样写回，用户会看到一堆"凭空冒出来的会话"。
 * 这里用两条真实恢复路径（NDJSON 流式 / 单 JSON）验证：写入前就被过滤，普通会话不受影响。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupRestoreSkipsMiniPhoneTest {

    private lateinit var context: Context
    private lateinit var museDb: MuseDb
    private lateinit var memoryDb: MemoryDb
    private lateinit var factDb: FactDb
    private lateinit var imageStore: MessageImageStore
    private lateinit var tempDir: File

    private val normalSession = SessionEntity(id = "s-normal", title = "普通会话", createdAt = 1L, updatedAt = 1L)
    private val miniSession = SessionEntity(
        id = "s-mini",
        title = "旧小手机私信",
        createdAt = 1L,
        updatedAt = 1L,
        isMiniPhone = true,
    )
    private val normalMessage = MessageEntity(
        id = "m-normal",
        sessionId = "s-normal",
        role = "USER",
        content = "普通会话的消息",
        createdAt = 1L,
    )
    private val miniMessage = MessageEntity(
        id = "m-mini",
        sessionId = "s-mini",
        role = "USER",
        content = "旧小手机私信的消息",
        createdAt = 1L,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "restore-journal.json").delete()
        File(context.filesDir, "restore-staging").deleteRecursively()
        museDb = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build()
        memoryDb = Room.inMemoryDatabaseBuilder(context, MemoryDb::class.java)
            .allowMainThreadQueries()
            .build()
        factDb = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()
        tempDir = Files.createTempDirectory("backup-miniphone-restore").toFile()
        imageStore = MessageImageStore(tempDir)
    }

    @After
    fun tearDown() {
        museDb.close()
        memoryDb.close()
        factDb.close()
        tempDir.deleteRecursively()
        File(context.filesDir, "restore-journal.json").delete()
        File(context.filesDir, "restore-staging").deleteRecursively()
    }

    @Test
    fun `NDJSON 恢复跳过旧小手机会话与其消息`() = runBlocking {
        val service = newService()

        invokeNdJsonRecovery(
            service,
            sequenceOf(
                // meta 声明的是备份里的原始条数(2/2)，恢复后实际入库应为 1/1
                metaLine(sessions = 2, messages = 2),
                dataLine("session", SessionEntity.serializer(), normalSession),
                dataLine("session", SessionEntity.serializer(), miniSession),
                dataLine("message", MessageEntity.serializer(), normalMessage),
                dataLine("message", MessageEntity.serializer(), miniMessage),
            ),
        )

        assertOnlyNormalSessionRestored()
    }

    @Test
    fun `单 JSON 备份恢复跳过旧小手机会话与其消息`() = runBlocking {
        val service = newService()
        val backup = BackupService.Backup(
            exportedAt = 1L,
            sessions = listOf(normalSession, miniSession),
            messages = listOf(normalMessage, miniMessage),
        )

        invokeApplyBackupInternal(service, backup)

        assertOnlyNormalSessionRestored()
    }

    private suspend fun assertOnlyNormalSessionRestored() {
        assertEquals(
            "只应恢复普通会话，旧小手机私信会话不得进库",
            listOf("s-normal"),
            museDb.sessionDao().observeAll().first().map { it.id },
        )
        assertEquals(
            "普通会话的消息必须原样恢复",
            "普通会话的消息",
            museDb.messageDao().getByMessageId("m-normal")?.content,
        )
        assertNull(
            "旧小手机会话的消息不得进库",
            museDb.messageDao().getByMessageId("m-mini"),
        )
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private fun newService(): BackupService {
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.cloudBackupConfigFlow } returns flowOf(CloudBackupConfig())
        val sessionRepository = mockk<SessionRepository>(relaxed = true)
        return BackupService(
            db = museDb,
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

    private fun metaLine(sessions: Int, messages: Int): String = buildJsonObject {
        put("type", "meta")
        put("version", 4)
        put("sessions", sessions)
        put("messages", messages)
    }.toString()

    private val backupJson = Json { encodeDefaults = true }

    private fun <T> dataLine(type: String, serializer: kotlinx.serialization.KSerializer<T>, value: T): String =
        buildJsonObject {
            put("type", type)
            put("data", backupJson.encodeToJsonElement(serializer, value))
        }.toString()

    /** 走真实的 NDJSON 恢复入口（含恢复账本与回滚包装）。 */
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
                    @Suppress("UNCHECKED_CAST")
                    continuation.resume(result as Pair<Int, Int>)
                }
            } catch (error: InvocationTargetException) {
                continuation.resumeWithException(error.targetException)
            } catch (error: Throwable) {
                continuation.resumeWithException(error)
            }
        }

    /** 走真实的单 JSON 恢复落库路径。 */
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
                    @Suppress("UNCHECKED_CAST")
                    continuation.resume(result as Pair<Int, Int>)
                }
            } catch (error: InvocationTargetException) {
                continuation.resumeWithException(error.targetException)
            } catch (error: Throwable) {
                continuation.resumeWithException(error)
            }
        }
}
