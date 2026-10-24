package io.zer0.muse.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.zer0.memory.fact.FactDb
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.summary.MemoryDb
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.session.MessageImageStore
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.data.stats.AutoBackupLogDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import java.util.Base64
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * v4.1: 目录型文件备份([BackupBinaryFiles])与它在 [BackupService] 里的接线守护。
 *
 * 缺陷背景:此前 [BackupService.backupFileStorePaths] 只覆盖单文件白名单,
 * `note_attachments/`(NoteAttachmentStore,二进制与文本混存)与 `avatar/`
 * (AvatarStorage 的私有副本)完全不进备份;`card_data.json` 也漏在白名单外。
 * 换机恢复后 QuickNoteEntity.attachmentsJson / AssistantEntity.avatarImageUrl
 * 全部指向不存在的文件,卡片数据整份丢失。
 *
 * 覆盖五件事:
 *  1. 往返字节一致(导出 → Base64 → 恢复,含真实 writeNdJson 导出侧);
 *  2. 路径穿越被拒绝且绝不写白名单外(含 `..`、绝对路径、反斜杠、空段);
 *  3. 容量上限(单文件 2MB / 目录 16MB)超限跳过并计数,其余文件照常恢复;
 *  4. 写失败向上传播(共享恢复账本,fail-fast 才能触发 recovery point 回滚);
 *  5. 向后兼容(旧备份无 binaryFiles 字段仍能解析与恢复)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupBinaryFilesTest {

    private lateinit var context: Context
    private lateinit var museDb: MuseDb
    private lateinit var memoryDb: MemoryDb
    private lateinit var factDb: FactDb
    private lateinit var imageStore: MessageImageStore
    private lateinit var service: BackupService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cleanFilesDir()
        museDb = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).allowMainThreadQueries().build()
        memoryDb = Room.inMemoryDatabaseBuilder(context, MemoryDb::class.java).allowMainThreadQueries().build()
        factDb = Room.inMemoryDatabaseBuilder(context, FactDb::class.java).allowMainThreadQueries().build()
        imageStore = MessageImageStore(Files.createTempDirectory("binary-backup-images").toFile())
        service = newService()
    }

    @After
    fun tearDown() {
        museDb.close()
        memoryDb.close()
        factDb.close()
        cleanFilesDir()
    }

    private fun cleanFilesDir() {
        File(context.filesDir, "restore-journal.json").delete()
        File(context.filesDir, "restore-staging").deleteRecursively()
        File(context.filesDir, "note_attachments").deleteRecursively()
        File(context.filesDir, "avatar").deleteRecursively()
        File(context.filesDir, "card_data.json").delete()
    }

    // ── 模块行为(纯文件系统) ──────────────────────────────────────────────

    private fun tempRoot(): File = Files.createTempDirectory("muse-binary-backup").toFile()

    @Test
    fun `note attachment and avatar bytes round trip unchanged`() = runBlocking {
        val source = tempRoot()
        try {
            val text = "笔记附件正文\n第二行".toByteArray(Charsets.UTF_8)
            val binary = ByteArray(64) { (it * 7).toByte() } + byteArrayOf(0x00, 0xFF.toByte(), 0x7F)
            File(source, "note_attachments").mkdirs()
            File(source, "avatar").mkdirs()
            File(source, "note_attachments/a.txt").writeBytes(text)
            File(source, "avatar/b.bin").writeBytes(binary)

            val snapshot = BackupBinaryFiles.read(source)
            assertEquals(0, snapshot.skipped.skippedFiles)
            assertEquals(listOf("avatar/b.bin", "note_attachments/a.txt"), snapshot.items.map { it.path })

            // 模拟备份落盘/读回:Base64 文本是唯一载体,往返后必须字节一致。
            val encoded = snapshot.asBase64Map()

            val target = tempRoot()
            try {
                val result = BackupBinaryFiles.restore(encoded, target)
                assertEquals(2, result.writtenFiles)
                assertEquals(0, result.skippedFiles)
                assertArrayEquals(text, File(target, "note_attachments/a.txt").readBytes())
                assertArrayEquals(binary, File(target, "avatar/b.bin").readBytes())
            } finally {
                target.deleteRecursively()
            }
        } finally {
            source.deleteRecursively()
        }
    }

    @Test
    fun `restore rejects traversal keys and never writes outside files dir`() = runBlocking {
        val root = tempRoot()
        try {
            val outside = File(root.parentFile, "muse-escaped-${System.nanoTime()}.txt")
            val payload = Base64.getEncoder().encodeToString("evil".toByteArray())
            val malicious = mapOf(
                "../evil.txt" to payload,
                "note_attachments/../../evil.txt" to payload,
                "avatar/../../../evil.txt" to payload,
                "/absolute/evil.txt" to payload,
                "note_attachments\\..\\evil.txt" to payload,
                "note_attachments//nested.txt" to payload,
                "note_attachments/./dot.txt" to payload,
                "note_attachments/../evil.txt" to payload,
                // 合法条目:必须仍能写入,证明拒绝逻辑不是"全盘失败"
                "note_attachments/ok.txt" to payload,
            )

            val result = BackupBinaryFiles.restore(malicious, root)

            assertEquals("只有白名单内的合法路径允许写回", 1, result.writtenFiles)
            assertEquals("其余 8 条非法路径必须被拒绝", 8, result.skippedFiles)
            assertFalse("不得写到 filesDir 之外", outside.exists())
            assertFalse(File(root.parentFile, "evil.txt").exists())
            assertFalse(File(root, "evil.txt").exists())
            assertFalse(File(root, "absolute/evil.txt").exists())
            assertFalse(File(root, "nested.txt").exists())
            assertFalse(File(root, "dot.txt").exists())
            assertTrue(File(root, "note_attachments/ok.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `path allowlist rejects every non whitelisted or ambiguous form`() {
        listOf(
            "",
            "card_data.json",
            "note_attachments",
            "note_attachments/",
            "avatars/x.png",
            "note_attachment/x.png",
            "../note_attachments/x.png",
            "/note_attachments/x.png",
            "note_attachments/../x.png",
            "note_attachments/../../x.png",
            "note_attachments/sub/../../x.png",
            "note_attachments\\x.png",
            "note_attachments//x.png",
            "note_attachments/./x.png",
            "note_attachments/x\u0000.png",
        ).forEach { path ->
            assertFalse("必须拒绝: $path", BackupBinaryFiles.isAllowedPath(path))
            assertNull("非法路径不得解析出目标文件: $path", BackupBinaryFiles.resolveTarget(tempRoot(), path))
        }
        assertTrue(BackupBinaryFiles.isAllowedPath("note_attachments/a.txt"))
        assertTrue(BackupBinaryFiles.isAllowedPath("avatar/deep/nested.jpg"))
    }

    @Test
    fun `oversized single file is skipped with counts while others still restore`() = runBlocking {
        val root = tempRoot()
        try {
            val tooBig = ByteArray((BackupBinaryFiles.MAX_FILE_BYTES + 1).toInt()) { 1 }
            val small = "small".toByteArray()
            File(root, "note_attachments").mkdirs()
            File(root, "avatar").mkdirs()
            File(root, "note_attachments/huge.png").writeBytes(tooBig)
            File(root, "note_attachments/small.txt").writeBytes(small)
            File(root, "avatar/a.jpg").writeBytes(small)

            val snapshot = BackupBinaryFiles.read(root)
            assertEquals("超限文件不进备份", 1, snapshot.skipped.skippedFiles)
            assertEquals(tooBig.size.toLong(), snapshot.skipped.skippedBytes)
            assertEquals(listOf("avatar/a.jpg", "note_attachments/small.txt"), snapshot.items.map { it.path })

            // 恢复侧同样有上限:手工塞一个超限条目,必须被跳过且不影响其余文件。
            val encoded = snapshot.asBase64Map() +
                ("note_attachments/injected.png" to Base64.getEncoder().encodeToString(tooBig))
            val target = tempRoot()
            try {
                val result = BackupBinaryFiles.restore(encoded, target)
                assertEquals(2, result.writtenFiles)
                assertEquals(1, result.skippedFiles)
                assertFalse(File(target, "note_attachments/injected.png").exists())
                assertArrayEquals(small, File(target, "note_attachments/small.txt").readBytes())
                assertArrayEquals(small, File(target, "avatar/a.jpg").readBytes())
            } finally {
                target.deleteRecursively()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `directory total cap stops further files and reports skipped bytes`() {
        val root = tempRoot()
        try {
            val dir = File(root, "avatar").apply { mkdirs() }
            // 每个 1.5MB:第 11 个文件会越过 16MB 目录合计上限。
            val chunk = ByteArray(1536 * 1024) { 2 }
            repeat(12) { index ->
                File(dir, "avatar_%02d.jpg".format(index)).writeBytes(chunk)
            }

            val snapshot = BackupBinaryFiles.read(root)

            assertEquals("16MB / 1.5MB = 最多 10 个文件进备份", 10, snapshot.items.size)
            assertEquals(2, snapshot.skipped.skippedFiles)
            assertEquals(chunk.size.toLong() * 2, snapshot.skipped.skippedBytes)
            assertTrue(snapshot.items.all { it.path.startsWith("avatar/") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `read ignores non whitelisted directories and regular file stores`() {
        val root = tempRoot()
        try {
            File(root, "note_attachments").mkdirs()
            File(root, "channel_configs.json").writeText("{}")
            File(root, "messages_images").mkdirs()
            File(root, "messages_images/x.png").writeBytes(byteArrayOf(1, 2, 3))
            File(root, "note_attachments/keep.txt").writeText("keep")

            val snapshot = BackupBinaryFiles.read(root)

            assertEquals(listOf("note_attachments/keep.txt"), snapshot.items.map { it.path })
            assertEquals(0, snapshot.skipped.skippedFiles)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `restore propagates write failure so the recovery point can roll back`() {
        val root = tempRoot()
        try {
            val payload = Base64.getEncoder().encodeToString("x".toByteArray())
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    BackupBinaryFiles.restore(
                        files = mapOf("note_attachments/a.txt" to payload),
                        filesDir = root,
                    ) { _, _ -> error("synthetic binary write failure") }
                }
            }
            assertFalse("写失败时不得留下半写文件", File(root, "note_attachments/a.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `import buffer drops traversal and oversized entries before they reach memory`() {
        val buffer = BackupBinaryFiles.Buffer()
        val small = Base64.getEncoder().encodeToString("ok".toByteArray())
        val big = Base64.getEncoder().encodeToString(ByteArray((BackupBinaryFiles.MAX_FILE_BYTES + 1).toInt()))

        buffer.offer("note_attachments/../evil.txt", small)
        buffer.offer("card_data.json", small)
        buffer.offer("avatar/huge.jpg", big)
        buffer.offer("avatar/ok.jpg", small)

        assertEquals(setOf("avatar/ok.jpg"), buffer.toMap().keys)
        assertEquals(3, buffer.stats.skippedFiles)
    }

    // ── BackupService 接线(真实 filesDir + 内存库) ─────────────────────────

    @Test
    fun `ndjson restore writes note attachments and avatars byte for byte`() = runBlocking {
        val session = SessionEntity(id = "s1", title = "t", createdAt = 1L, updatedAt = 1L)
        val attachmentBytes = "附件内容".toByteArray(Charsets.UTF_8)
        val avatarBytes = byteArrayOf(0x01, 0x00, 0xFF.toByte(), 0x7F)

        invokeNdJsonRecovery(
            service,
            sequenceOf(
                metaLine(sessions = 1, messages = 0, binaryFiles = 2),
                dataLine("session", SessionEntity.serializer(), session),
                binaryFileLine("note_attachments/a.txt", attachmentBytes),
                binaryFileLine("avatar/b.bin", avatarBytes),
            ),
        )

        assertArrayEquals(attachmentBytes, File(context.filesDir, "note_attachments/a.txt").readBytes())
        assertArrayEquals(avatarBytes, File(context.filesDir, "avatar/b.bin").readBytes())
    }

    @Test
    fun `ndjson restore drops traversal keys without failing the restore`() = runBlocking {
        val session = SessionEntity(id = "s2", title = "t", createdAt = 1L, updatedAt = 1L)
        val outside = File(context.filesDir.parentFile, "binary-escaped-${System.nanoTime()}.txt")
        val payload = "evil".toByteArray()

        invokeNdJsonRecovery(
            service,
            sequenceOf(
                // meta 声明 2 条:分发侧两条都要计数,才能通过 B-34 对账。
                metaLine(sessions = 1, messages = 0, binaryFiles = 2),
                dataLine("session", SessionEntity.serializer(), session),
                binaryFileLine("../evil.txt", payload),
                binaryFileLine("note_attachments/../../evil.txt", payload),
            ),
        )

        assertFalse("路径穿越必须被拒绝,不得写到 filesDir 之外", outside.exists())
        assertFalse(File(context.filesDir, "evil.txt").exists())
        assertFalse(File(context.filesDir.parentFile, "evil.txt").exists())
        // 恢复本身要成功:会话确实落库。
        assertEquals(1, museDb.sessionDao().observeAll().first().size)
    }

    @Test
    fun `card data file store round trips through the allowlisted file stores`() = runBlocking {
        assertTrue(
            "card_data.json 必须在文件型白名单内(此前漏在白名单外,卡片数据不进备份)",
            "card_data.json" in BackupService.backupFileStorePaths,
        )
        val file = File(context.filesDir, "card_data.json")
        val payload = """{"card-1":"{\"value\":42}"}"""
        file.writeText(payload)

        @Suppress("UNCHECKED_CAST")
        val snapshot = callSuspend(service, "readFileStores", emptyArray(), emptyArray()) as Map<String, String>
        assertEquals(payload, snapshot["card_data.json"])

        file.delete()
        callSuspend(service, "writeFileStores", arrayOf(Map::class.java), arrayOf<Any>(snapshot))
        assertEquals(payload, file.readText())
    }

    @Test
    fun `backup json carries binary files and old backups without the field still parse`() {
        val json = Json { ignoreUnknownKeys = true }
        val encoded = Base64.getEncoder().encodeToString("bytes".toByteArray())
        val backup = BackupService.Backup(
            exportedAt = 1L,
            sessions = emptyList(),
            messages = emptyList(),
            binaryFiles = mapOf("note_attachments/a.txt" to encoded),
        )
        val restored = json.decodeFromString(
            BackupService.Backup.serializer(),
            json.encodeToString(BackupService.Backup.serializer(), backup),
        )
        assertEquals(encoded, restored.binaryFiles["note_attachments/a.txt"])

        // 向后兼容:旧备份没有 binaryFiles 字段 → 默认空 map,不报错。
        val oldStyle = """{"version":3,"exportedAt":1,"sessions":[],"messages":[]}"""
        val old = json.decodeFromString(BackupService.Backup.serializer(), oldStyle)
        assertEquals(emptyMap<String, String>(), old.binaryFiles)
    }

    @Test
    fun `real ndjson export then restore round trips directory files`() = runBlocking {
        // 真正走一遍导出侧 writeNdJson → 导入侧 applyNdJsonStreamingWithRecovery,
        // 而不是手工拼行:确保 meta 计数、行类型、恢复分发三处接线一致。
        val attachment = "附件字节".toByteArray(Charsets.UTF_8)
        val avatar = ByteArray(32) { (it * 5).toByte() }
        File(context.filesDir, "note_attachments").mkdirs()
        File(context.filesDir, "avatar").mkdirs()
        File(context.filesDir, "note_attachments/a.txt").writeBytes(attachment)
        File(context.filesDir, "avatar/b.bin").writeBytes(avatar)
        File(context.filesDir, "card_data.json").writeText("""{"c1":"{}"}""")

        val exported = exportNdJsonLines()
        assertTrue("导出必须带 binaryFile 行", exported.any { it.contains("\"binaryFile\"") })
        assertTrue("导出必须带 card_data.json 的 fileStore 行", exported.any { it.contains("card_data.json") })

        // 清掉源文件,模拟换机后的空目录。
        File(context.filesDir, "note_attachments").deleteRecursively()
        File(context.filesDir, "avatar").deleteRecursively()
        File(context.filesDir, "card_data.json").delete()

        invokeNdJsonRecovery(service, exported.asSequence())

        assertArrayEquals(attachment, File(context.filesDir, "note_attachments/a.txt").readBytes())
        assertArrayEquals(avatar, File(context.filesDir, "avatar/b.bin").readBytes())
        assertEquals("""{"c1":"{}"}""", File(context.filesDir, "card_data.json").readText())
    }

    // ── 反射工具 ──────────────────────────────────────────────────────────

    private fun newService(): BackupService {
        val settings = mockk<SettingsRepository>(relaxed = true)
        coEvery { settings.exportSettingsSnapshot(any()) } returns emptyMap()
        every { settings.cloudBackupConfigFlow } returns flowOf(CloudBackupConfig())
        val sessionRepository = mockk<SessionRepository>(relaxed = true)
        coEvery { sessionRepository.rebuildFtsIndex() } just Runs
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

    private fun metaLine(sessions: Int, messages: Int, binaryFiles: Int): String = buildJsonObject {
        put("type", "meta")
        put("version", 4)
        put("sessions", sessions)
        put("messages", messages)
        put("binaryFiles", binaryFiles)
    }.toString()

    private fun binaryFileLine(path: String, bytes: ByteArray): String = buildJsonObject {
        put("type", "binaryFile")
        put("path", path)
        put("content", Base64.getEncoder().encodeToString(bytes))
    }.toString()

    private val backupJson = Json { encodeDefaults = true }

    private fun <T> dataLine(type: String, serializer: kotlinx.serialization.KSerializer<T>, value: T): String = buildJsonObject {
        put("type", type)
        put("data", backupJson.encodeToJsonElement(serializer, value))
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
                if (result !== kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) {
                    @Suppress("UNCHECKED_CAST")
                    continuation.resume(result as Pair<Int, Int>)
                }
            } catch (error: InvocationTargetException) {
                continuation.resumeWithException(error.targetException)
            } catch (error: Throwable) {
                continuation.resumeWithException(error)
            }
        }

    private suspend fun callSuspend(service: BackupService, name: String, argTypes: Array<Class<*>>, args: Array<Any>): Any? =
        suspendCancellableCoroutine { continuation ->
            val method = BackupService::class.java.getDeclaredMethod(name, *argTypes, Continuation::class.java)
                .apply { isAccessible = true }
            try {
                val result = method.invoke(service, *args, continuation)
                if (result !== kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) continuation.resume(result)
            } catch (error: InvocationTargetException) {
                continuation.resumeWithException(error.targetException)
            } catch (error: Throwable) {
                continuation.resumeWithException(error)
            }
        }

    /**
     * 反射调用 `writeNdJson` 并收集写出的行。
     *
     * 不能用 suspendCancellableCoroutine 的 continuation 直接传给目标方法:
     * 目标方法挂起后会用 NdJsonExportSummary 恢复它,而我们的 continuation
     * 恢复类型是 List<String>,会在 resume 处 ClassCastException。
     * 这里用独立的内部 continuation 接收目标结果,再转换后恢复外层。
     */
    private suspend fun exportNdJsonLines(): List<String> = suspendCancellableCoroutine { outer ->
        val method = BackupService::class.java.getDeclaredMethod(
            "writeNdJson",
            java.io.BufferedWriter::class.java,
            Boolean::class.javaPrimitiveType,
            Continuation::class.java,
        ).apply { isAccessible = true }
        val sink = java.io.StringWriter()
        val writer = java.io.BufferedWriter(sink)
        val inner = object : Continuation<Any?> {
            override val context: kotlin.coroutines.CoroutineContext = EmptyCoroutineContext
            override fun resumeWith(result: Result<Any?>) {
                result.onSuccess {
                    writer.flush()
                    outer.resume(sink.toString().lines().filter { it.isNotBlank() })
                }.onFailure { outer.resumeWithException(it) }
            }
        }
        try {
            method.invoke(service, writer, false, inner)
        } catch (error: InvocationTargetException) {
            outer.resumeWithException(error.targetException)
        } catch (error: Throwable) {
            outer.resumeWithException(error)
        }
    }
}
