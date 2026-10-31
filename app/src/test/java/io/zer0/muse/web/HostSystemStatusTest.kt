package io.zer0.muse.web

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.zer0.common.AppJson
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.data.stats.AutoBackupLogEntity
import io.zer0.muse.schedule.ChatGenerationManager
import io.zer0.muse.ui.ChatUiState
import io.zer0.muse.ui.ChatViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * `system.status` 的载荷、体积口径、备份映射与焦点行为。
 *
 * 体积与备份两块不经过 Android 运行时也能验:目录统计的根目录可注入,备份映射是纯函数。
 * 回执结构走网关真连一次,断言的是浏览器真正收到的那条 JSON。
 */
class HostSystemStatusTest {

    private class FakeConnection {
        val incoming = Channel<Frame>(Channel.UNLIMITED)
        val events = mutableListOf<HostEvent>()
        val rawEvents = mutableListOf<String>()
        private val mock = mockk<DefaultWebSocketServerSession>(relaxed = true)

        val session: DefaultWebSocketServerSession = mock

        init {
            every { mock.incoming } returns incoming
            coEvery { mock.send(any<Frame>()) } coAnswers {
                val frame = firstArg<Frame>()
                if (frame is Frame.Text) {
                    val text = frame.data.decodeToString()
                    rawEvents += text
                    events += AppJson.decodeFromString<HostEvent>(text)
                }
            }
        }

        suspend fun send(json: String) {
            incoming.send(Frame.Text(json))
        }
    }

    private class FakeSystemStatusSource(private val status: HostSystemStatus) : HostSystemStatusSource {
        override suspend fun status(): HostSystemStatus = status
    }

    private val tempDir: File = Files.createTempDirectory("host-system-status").toFile()

    private fun gateway(readSources: HostReadSources): HostWebSocketGateway {
        val chat = mockk<ChatViewModel>(relaxed = true)
        every { chat.state } returns MutableStateFlow(ChatUiState())
        every { chat.messages } returns MutableStateFlow(emptyList())
        val generations = mockk<ChatGenerationManager>(relaxed = true)
        every { generations.activeGenerations } returns
            MutableStateFlow<Map<String, ChatGenerationManager.ActiveGeneration>>(emptyMap())
        return HostWebSocketGateway(
            chatViewModel = chat,
            generationManager = generations,
            sessionRepo = mockk<SessionRepository>(relaxed = true),
            assistantRepo = mockk<AssistantRepository>(relaxed = true),
            settingsRepo = mockk<SettingsRepository>(relaxed = true),
            settingsStrings = HostSettingsStrings { name, _ -> "text:$name" },
            mediaLinks = HostMediaLinks(
                HostMediaStore(
                    imageDir = File(tempDir, "muse_images").apply { mkdirs() },
                    voiceDir = File(tempDir, "voice_messages").apply { mkdirs() },
                ),
            ),
            readSources = readSources,
        )
    }

    @Test
    fun `system status carries the whole snapshot`() = runTest {
        val status = HostSystemStatus(
            appVersion = "2.6.18",
            appVersionCode = 278,
            uptimeMs = 90_000L,
            storage = HostStorageBytes(
                databaseBytes = 1_024,
                mediaBytes = 2_048,
                voiceBytes = 4_096,
                workspaceBytes = 8_192,
                cacheBytes = 16_384,
                totalAppBytes = 31_744,
            ),
            backup = HostBackupStatus(
                lastBackupAt = 1_700_000_000_000L,
                lastBackupBytes = 65_536,
                autoBackupEnabled = true,
                cloudBackupEnabled = false,
            ),
            capabilities = HostDeviceCapabilities(
                accessibility = true,
                shell = false,
                root = true,
                shizuku = false,
                virtualDisplay = true,
                notificationListener = true,
            ),
            battery = HostBatteryStatus(level = 55, charging = true),
        )
        val gateway = gateway(HostReadSources(system = FakeSystemStatusSource(status)))
        val browser = FakeConnection()
        val job = launch { gateway.serve(browser.session) }
        advanceUntilIdle()

        browser.send("""{"protocolVersion":1,"type":"system.status","requestId":"req-status"}""")
        advanceUntilIdle()

        val event = browser.events.single { it.type == HostSystemStatusReads.TYPE }
        assertEquals("req-status", event.requestId)
        assertNull(event.error)
        val payload = requireNotNull(event.systemStatus)
        assertEquals("2.6.18", payload.appVersion)
        assertEquals(278L, payload.appVersionCode)
        assertEquals(90_000L, payload.uptimeMs)
        assertEquals(1_024L, payload.storage.databaseBytes)
        assertEquals(31_744L, payload.storage.totalAppBytes)
        assertEquals(1_700_000_000_000L, payload.backup.lastBackupAt)
        assertEquals(65_536L, payload.backup.lastBackupBytes)
        assertEquals(true, payload.backup.autoBackupEnabled)
        assertEquals(false, payload.backup.cloudBackupEnabled)
        assertEquals(HostDeviceCapabilities(true, false, true, false, true, true), payload.capabilities)
        assertEquals(55, payload.battery?.level)
        assertEquals(true, payload.battery?.charging)

        // 网页端读到的是 JSON:七个顶层字段一个不少,capabilities 六项都是布尔字面量。
        val raw = browser.rawEvents.first { it.contains(HostSystemStatusReads.TYPE) }
        val wire = AppJson.parseToJsonElement(raw).jsonObject["systemStatus"]!!.jsonObject
        assertEquals(
            setOf("appVersion", "appVersionCode", "uptimeMs", "storage", "backup", "capabilities", "battery"),
            wire.keys,
        )
        val capabilities = wire["capabilities"]!!.jsonObject
        assertEquals(CAPABILITY_KEYS, capabilities.keys)
        capabilities.forEach { (key, value) ->
            assertTrue("$key 必须是布尔值,实际是 $value", (value as? JsonPrimitive)?.booleanOrNull != null)
        }
        assertEquals(
            setOf("databaseBytes", "mediaBytes", "voiceBytes", "workspaceBytes", "cacheBytes", "totalAppBytes"),
            wire["storage"]!!.jsonObject.keys,
        )

        // 新命令也要在快照的能力位里声明,网页端据此决定要不要发这条命令。
        val snapshot = browser.events.first { it.type == "state.snapshot" }
        assertTrue("system_status" in snapshot.capabilityFlags)

        browser.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `unassembled system status still answers with six boolean capabilities`() = runTest {
        val gateway = gateway(HostReadSources())
        val browser = FakeConnection()
        val job = launch { gateway.serve(browser.session) }
        advanceUntilIdle()

        browser.send("""{"protocolVersion":1,"type":"system.status","requestId":"req-empty"}""")
        advanceUntilIdle()

        val event = browser.events.single { it.type == HostSystemStatusReads.TYPE }
        assertNull(event.error)
        val payload = requireNotNull(event.systemStatus)
        assertEquals(
            HostDeviceCapabilities(
                accessibility = false,
                shell = false,
                root = false,
                shizuku = false,
                virtualDisplay = false,
                notificationListener = false,
            ),
            payload.capabilities,
        )
        assertNull(payload.battery)
        assertNull(payload.backup.lastBackupAt)

        val raw = browser.rawEvents.first { it.contains(HostSystemStatusReads.TYPE) }
        val capabilities = AppJson.parseToJsonElement(raw).jsonObject["systemStatus"]!!
            .jsonObject["capabilities"]!!.jsonObject
        assertEquals(CAPABILITY_KEYS, capabilities.keys)
        capabilities.forEach { (key, value) ->
            assertEquals("$key 必须是 false 而不是 null", false, (value as JsonPrimitive).booleanOrNull)
        }

        browser.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `storage usage sums each injected directory`() = runTest {
        val database = tempDir.resolve("databases").apply { mkdirs() }
        val media = tempDir.resolve("muse_images").apply { mkdirs() }
        val voice = tempDir.resolve("voice_messages").apply { mkdirs() }
        val workspace = tempDir.resolve("workspace").apply { mkdirs() }
        val cache = tempDir.resolve("cache").apply { mkdirs() }

        // 每个目录都放一层子目录,顺带证明统计是递归而不是只看顶层文件。
        writeBytes(database, "muse.db", 1_000)
        writeBytes(File(database, "wal").apply { mkdirs() }, "muse.db-wal", 24)
        writeBytes(media, "a.png", 2_000)
        writeBytes(voice, "a.m4a", 3_000)
        writeBytes(File(workspace, "docs").apply { mkdirs() }, "note.md", 4_000)
        writeBytes(cache, "tmp.bin", 5_000)

        val usage = HostStorageUsage(
            databaseDir = database,
            mediaDir = media,
            voiceDir = voice,
            workspaceDir = workspace,
            cacheDir = cache,
        ).measure()

        assertEquals(1_024L, usage.databaseBytes)
        assertEquals(2_000L, usage.mediaBytes)
        assertEquals(3_000L, usage.voiceBytes)
        assertEquals(4_000L, usage.workspaceBytes)
        assertEquals(5_000L, usage.cacheBytes)
        assertEquals(15_024L, usage.totalAppBytes)
        assertEquals(
            usage.databaseBytes + usage.mediaBytes + usage.voiceBytes + usage.workspaceBytes + usage.cacheBytes,
            usage.totalAppBytes,
        )
    }

    @Test
    fun `storage usage treats missing directories as zero`() = runTest {
        val present = tempDir.resolve("only-media").apply { mkdirs() }
        writeBytes(present, "a.png", 777)

        val usage = HostStorageUsage(
            databaseDir = tempDir.resolve("no-databases"),
            mediaDir = present,
            voiceDir = null,
            workspaceDir = tempDir.resolve("no-workspace"),
            cacheDir = tempDir.resolve("no-cache"),
        ).measure()

        assertEquals(777L, usage.mediaBytes)
        assertEquals(777L, usage.totalAppBytes)
        assertEquals(0L, usage.databaseBytes)
    }

    @Test
    fun `backup without a record reports null instead of zero`() {
        val none = backupStatusOf(
            latest = null,
            autoBackupEnabled = true,
            cloudConfigured = false,
            cloudAutoSync = true,
        )
        assertNull(none.lastBackupAt)
        assertNull(none.lastBackupBytes)
        assertEquals(true, none.autoBackupEnabled)
        // 配了云备份但没开自动同步:不会自己跑,不算「已开启」。
        assertEquals(false, none.cloudBackupEnabled)

        val recorded = backupStatusOf(
            latest = AutoBackupLogEntity(
                id = 7,
                backupPath = "/data/user/0/io.zer0.muse/databases/backups/muse_backup.zip",
                fileSizeBytes = 4_096,
                status = "success",
                createdAt = 1_700_000_000_000L,
            ),
            autoBackupEnabled = false,
            cloudConfigured = true,
            cloudAutoSync = true,
        )
        assertEquals(1_700_000_000_000L, recorded.lastBackupAt)
        assertEquals(4_096L, recorded.lastBackupBytes)
        assertEquals(true, recorded.cloudBackupEnabled)
    }

    @Test
    fun `system status answers without holding the focus lock`() = runTest {
        val gateway = gateway(HostReadSources(system = FakeSystemStatusSource(HostSystemStatus(appVersion = "2.6.18"))))
        // 先连上的连接持焦点;后连上的拿不到,模拟「另一个浏览器」。
        val holder = FakeConnection()
        val holderJob = launch { gateway.serve(holder.session) }
        advanceUntilIdle()
        val other = FakeConnection()
        val otherJob = launch { gateway.serve(other.session) }
        advanceUntilIdle()

        other.send("""{"protocolVersion":1,"type":"system.status","requestId":"req-status"}""")
        advanceUntilIdle()

        val status = other.events.single { it.type == HostSystemStatusReads.TYPE }
        assertEquals("req-status", status.requestId)
        assertNull(status.error)
        assertEquals("2.6.18", status.systemStatus?.appVersion)

        // 同一连接发写命令仍被焦点锁挡住 —— 说明上一条不是「锁没生效」而是「只读不需要锁」。
        other.send("""{"protocolVersion":1,"type":"session.rename","requestId":"req-rename","sessionId":"s-1","title":"x"}""")
        advanceUntilIdle()
        val rejected = other.events.first { it.requestId == "req-rename" }
        assertEquals("error", rejected.type)
        assertEquals(HostSessionCommands.ERROR_FOCUS_NOT_HELD, rejected.error)

        holder.incoming.close()
        other.incoming.close()
        advanceUntilIdle()
        holderJob.join()
        otherJob.join()
    }

    private companion object {
        /** capabilities 的六个键:少一个、多一个都算协议变更。 */
        val CAPABILITY_KEYS = setOf(
            "accessibility",
            "shell",
            "root",
            "shizuku",
            "virtualDisplay",
            "notificationListener",
        )

        fun writeBytes(dir: File, name: String, size: Int) {
            dir.mkdirs()
            File(dir, name).writeBytes(ByteArray(size))
        }
    }
}
