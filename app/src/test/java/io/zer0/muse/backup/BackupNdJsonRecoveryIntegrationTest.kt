package io.zer0.muse.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.zer0.memory.fact.FactDb
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactEntity
import io.zer0.memory.fact.FactStore
import io.zer0.memory.summary.MemoryDb
import io.zer0.memory.summary.SessionSummaryEntity
import io.zer0.muse.data.AtomicFileStore
import io.zer0.muse.data.SecureKeyCipher
import io.zer0.muse.data.SecureKeyStore
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.computenode.RemoteComputeNodeEntity
import io.zer0.muse.data.session.MessageEntity
import io.zer0.muse.data.session.MessageImageStore
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.data.stats.AutoBackupLogDao
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupNdJsonRecoveryIntegrationTest {

    private lateinit var context: Context
    private lateinit var museDb: MuseDb
    private lateinit var memoryDb: MemoryDb
    private lateinit var factDb: FactDb
    private lateinit var imageStore: MessageImageStore
    private lateinit var tempDir: File

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
        tempDir = Files.createTempDirectory("backup-recovery-images").toFile()
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
    fun `fts failure rolls back muse memory and fact databases from ndjson recovery point`() = runBlocking {
        val now = 1_700_000_000_000L
        val oldSession = SessionEntity(
            id = "old-session",
            title = "old",
            createdAt = now,
            updatedAt = now,
        )
        val oldMessage = MessageEntity(
            id = "old-message",
            sessionId = oldSession.id,
            role = "USER",
            content = "old content",
            createdAt = now,
        )
        museDb.sessionDao().insert(oldSession)
        museDb.messageDao().upsert(oldMessage)
        memoryDb.sessionSummaryDao().upsert(
            SessionSummaryEntity(
                sessionId = oldSession.id,
                createdAt = "2026-10-02T00:00:00Z",
                updatedAt = "2026-10-02T00:00:00Z",
                summary = "old summary",
            ),
        )
        factDb.factDao().insert(
            FactEntity(
                fact = "old fact",
                createdAt = "2026-10-02T00:00:00Z",
            ),
        )

        val settings = mockk<SettingsRepository>(relaxed = true)
        coEvery { settings.exportSettingsSnapshot() } returns emptyMap()
        coEvery { settings.restoreSettingsSnapshot(any()) } just Runs
        everyCloudConfig(settings)

        val ftsCalls = AtomicInteger(0)
        val sessionRepository = mockk<SessionRepository>(relaxed = true)
        coEvery { sessionRepository.rebuildFtsIndex() } answers {
            if (ftsCalls.incrementAndGet() == 1) {
                error("synthetic target FTS failure")
            }
        }

        val service = BackupService(
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

        val targetSession = oldSession.copy(id = "target-session", title = "target")
        val targetMessage = oldMessage.copy(
            id = "target-message",
            sessionId = targetSession.id,
            content = "target content",
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                invokeNdJsonRecovery(
                    service,
                    sequenceOf(
                        metaLine(sessions = 1, messages = 1),
                        dataLine("session", SessionEntity.serializer(), targetSession),
                        dataLine("message", MessageEntity.serializer(), targetMessage),
                    ),
                )
            }
        }

        assertNotNull(museDb.sessionDao().getById(oldSession.id))
        assertEquals("old content", museDb.messageDao().getByMessageId(oldMessage.id)?.content)
        assertFalse(museDb.sessionDao().getById(targetSession.id) != null)
        assertEquals("old summary", memoryDb.sessionSummaryDao().get(oldSession.id)?.summary)
        assertEquals(listOf("old fact"), factDb.factDao().getAll().map { it.fact })
        assertEquals(2, ftsCalls.get())
        assertFalse(File(context.filesDir, "restore-journal.json").exists())
        assertTrue(!File(context.filesDir, "restore-staging").exists())
    }

    @Test
    fun `failed restore puts back local provider settings and node credentials`() = runBlocking {
        seedOldState()
        var rawProviders: String? = "original providers"
        val oldNode = RemoteComputeNodeEntity(nodeId = "original", encryptedCredential = "enc_v1:fixture")
        museDb.remoteComputeNodeDao().upsert(oldNode)
        val settings = mockk<SettingsRepository>(relaxed = true)
        everyCloudConfig(settings)
        coEvery { settings.exportSettingsSnapshot(any()) } returns emptyMap()
        coEvery { settings.rawProvidersForLocalRecovery() } answers { rawProviders }
        coEvery { settings.restoreLocalProviders(any()) } answers { rawProviders = firstArg() }
        coEvery { settings.restoreSettingsSnapshot(any(), any()) } answers {
            rawProviders = firstArg<Map<String, String>>()[BackupService.KEY_PROVIDERS_SETTING]
        }
        val fts = mockk<SessionRepository>(relaxed = true)
        val ftsCalls = AtomicInteger()
        coEvery { fts.rebuildFtsIndex() } answers {
            if (ftsCalls.incrementAndGet() == 1) {
                assertEquals("incoming providers", rawProviders)
                error("synthetic target FTS failure")
            }
        }
        val service = newService(settings, fts)
        val cipher = SecureKeyStore.delegate
        SecureKeyStore.delegate = object : SecureKeyCipher {
            override suspend fun encrypt(plain: String) = if (plain.isEmpty()) plain else "test:$plain"
            override suspend fun decrypt(stored: String) = stored.removePrefix("test:")
        }
        try {
            val settingsLine = dataLine(
                "settings",
                MapSerializer(String.serializer(), String.serializer()),
                mapOf(BackupService.KEY_PROVIDERS_SETTING to "incoming providers"),
            )
            assertThrows(IllegalStateException::class.java) {
                runBlocking { invokeNdJsonRecovery(service, sequenceOf(metaLine(0, 0, settings = 1), settingsLine)) }
            }
            assertEquals("original providers", rawProviders)
            assertEquals(oldNode.encryptedCredential, museDb.remoteComputeNodeDao().getById(oldNode.nodeId)?.encryptedCredential)
            assertEquals(2, ftsCalls.get())
        } finally {
            SecureKeyStore.delegate = cipher
        }
    }

    private suspend fun seedOldState(): Pair<SessionEntity, MessageEntity> {
        val now = 1_700_000_000_000L
        val oldSession = SessionEntity(
            id = "old-session",
            title = "old",
            createdAt = now,
            updatedAt = now,
        )
        val oldMessage = MessageEntity(
            id = "old-message",
            sessionId = oldSession.id,
            role = "USER",
            content = "old content",
            createdAt = now,
        )
        museDb.sessionDao().insert(oldSession)
        museDb.messageDao().upsert(oldMessage)
        memoryDb.sessionSummaryDao().upsert(
            SessionSummaryEntity(
                sessionId = oldSession.id,
                createdAt = "2026-10-02T00:00:00Z",
                updatedAt = "2026-10-02T00:00:00Z",
                summary = "old summary",
            ),
        )
        factDb.factDao().insert(
            FactEntity(
                fact = "old fact",
                createdAt = "2026-10-02T00:00:00Z",
            ),
        )
        return oldSession to oldMessage
    }

    private fun newService(settings: SettingsRepository, sessionRepository: SessionRepository): BackupService = BackupService(
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

    @Test
    fun `settings failure rolls back all databases from ndjson recovery point`() = runBlocking {
        val (oldSession, oldMessage) = seedOldState()
        val settingsWrites = AtomicInteger(0)
        val settings = mockk<SettingsRepository>(relaxed = true)
        coEvery { settings.exportSettingsSnapshot() } returns mapOf("theme_id" to "old")
        everyCloudConfig(settings)
        coEvery { settings.restoreSettingsSnapshot(any()) } answers {
            if (settingsWrites.incrementAndGet() == 1) {
                error("synthetic target settings failure")
            }
        }
        val sessionRepository = mockk<SessionRepository>(relaxed = true)
        coEvery { sessionRepository.rebuildFtsIndex() } just Runs
        val service = newService(settings, sessionRepository)
        val targetSession = oldSession.copy(id = "settings-target-session", title = "target")
        val targetMessage = oldMessage.copy(
            id = "settings-target-message",
            sessionId = targetSession.id,
            content = "target content",
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                invokeNdJsonRecovery(
                    service,
                    sequenceOf(
                        metaLine(sessions = 1, messages = 1, settings = 1),
                        dataLine("session", SessionEntity.serializer(), targetSession),
                        dataLine("message", MessageEntity.serializer(), targetMessage),
                        dataLine(
                            "settings",
                            MapSerializer(String.serializer(), String.serializer()),
                            mapOf("theme_id" to "new"),
                        ),
                    ),
                )
            }
        }

        assertEquals("old content", museDb.messageDao().getByMessageId(oldMessage.id)?.content)
        assertEquals("old summary", memoryDb.sessionSummaryDao().get(oldSession.id)?.summary)
        assertEquals(listOf("old fact"), factDb.factDao().getAll().map { it.fact })
        assertEquals(2, settingsWrites.get())
        assertFalse(File(context.filesDir, "restore-journal.json").exists())
    }

    @Test
    fun `file store failure rolls back old file and databases from ndjson recovery point`() = runBlocking {
        val (oldSession, oldMessage) = seedOldState()
        val file = File(context.filesDir, "channel_configs.json")
        file.writeText("old-file")
        val writes = AtomicInteger(0)
        mockkObject(AtomicFileStore)
        every { AtomicFileStore.writeText(any(), any()) } answers {
            val target = firstArg<File>()
            val content = secondArg<String>()
            if (target.name == "channel_configs.json" && writes.incrementAndGet() == 1) {
                error("synthetic target file-store failure")
            }
            target.parentFile?.mkdirs()
            target.writeText(content)
        }

        try {
            val settings = mockk<SettingsRepository>(relaxed = true)
            coEvery { settings.exportSettingsSnapshot() } returns emptyMap()
            everyCloudConfig(settings)
            val sessionRepository = mockk<SessionRepository>(relaxed = true)
            coEvery { sessionRepository.rebuildFtsIndex() } just Runs
            val service = newService(settings, sessionRepository)
            val targetSession = oldSession.copy(id = "file-target-session", title = "target")
            val targetMessage = oldMessage.copy(
                id = "file-target-message",
                sessionId = targetSession.id,
                content = "target content",
            )

            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    invokeNdJsonRecovery(
                        service,
                        sequenceOf(
                            metaLine(sessions = 1, messages = 1, fileStores = 1),
                            dataLine("session", SessionEntity.serializer(), targetSession),
                            dataLine("message", MessageEntity.serializer(), targetMessage),
                            fileStoreLine("channel_configs.json", "new-file"),
                        ),
                    )
                }
            }
        } finally {
            unmockkObject(AtomicFileStore)
        }

        assertEquals("old content", museDb.messageDao().getByMessageId(oldMessage.id)?.content)
        assertEquals("old summary", memoryDb.sessionSummaryDao().get(oldSession.id)?.summary)
        assertEquals(listOf("old fact"), factDb.factDao().getAll().map { it.fact })
        assertEquals("old-file", file.readText())
        assertEquals(2, writes.get())
        assertFalse(File(context.filesDir, "restore-journal.json").exists())
    }

    private fun everyCloudConfig(settings: SettingsRepository) {
        every { settings.cloudBackupConfigFlow } returns flowOf(CloudBackupConfig())
    }

    private fun metaLine(sessions: Int, messages: Int, settings: Int = 0, fileStores: Int = 0): String = buildJsonObject {
        put("type", "meta")
        put("version", 4)
        put("sessions", sessions)
        put("messages", messages)
        put("settings", settings)
        put("fileStores", fileStores)
    }.toString()

    private val backupJson = Json { encodeDefaults = true }

    private fun <T> dataLine(type: String, serializer: kotlinx.serialization.KSerializer<T>, value: T): String = buildJsonObject {
        put("type", type)
        put("data", backupJson.encodeToJsonElement(serializer, value))
    }.toString()

    private fun fileStoreLine(name: String, content: String): String = buildJsonObject {
        put("type", "fileStore")
        put("name", name)
        put("content", content)
    }.toString()

    private suspend fun invokeNdJsonRecovery(service: BackupService, lines: Sequence<String>): Pair<Int, Int> =
        suspendCancellableCoroutine { continuation ->
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
}
