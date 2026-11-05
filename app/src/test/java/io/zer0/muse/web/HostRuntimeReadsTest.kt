package io.zer0.muse.web

import android.content.Context
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.zer0.common.AppJson
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.diagnostics.MainThreadJankTracker
import io.zer0.muse.schedule.ChatGenerationManager
import io.zer0.muse.ui.ChatUiState
import io.zer0.muse.ui.ChatViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * S5「运行」页三条只读命令:日志尾部口径、单行截断、统计投影、未装配回退,
 * 以及网关真连一次 —— 断言的是浏览器实际收到的那条 JSON,不是内部函数。
 *
 * 文件与计数器都可注入,所以这三条命令不需要 Android 运行时也能验到底。
 */
class HostRuntimeReadsTest {

    private class FakeConnection {
        val incoming = Channel<io.ktor.websocket.Frame>(Channel.UNLIMITED)
        val events = mutableListOf<HostEvent>()
        private val mock = mockk<DefaultWebSocketServerSession>(relaxed = true)

        val session: DefaultWebSocketServerSession = mock

        init {
            every { mock.incoming } returns incoming
            coEvery { mock.send(any<io.ktor.websocket.Frame>()) } coAnswers {
                val frame = firstArg<io.ktor.websocket.Frame>()
                if (frame is io.ktor.websocket.Frame.Text) {
                    events += AppJson.decodeFromString<HostEvent>(frame.data.decodeToString())
                }
            }
        }

        suspend fun send(json: String) {
            incoming.send(io.ktor.websocket.Frame.Text(json))
        }
    }

    private class FakeRuntimeSource(
        private val logLines: List<String> = emptyList(),
        private val diagnostics: String? = null,
        private val runtimeStats: HostRuntimeStats = HostRuntimeStats(),
    ) : HostRuntimeSource {
        var requestedLimit: Int? = null

        override suspend fun logs(limit: Int): List<String> {
            requestedLimit = limit
            return logLines
        }

        override suspend fun diagnosticsJson(): String? = diagnostics

        override suspend fun stats(): HostRuntimeStats = runtimeStats
    }

    private val tempDir: File = Files.createTempDirectory("host-runtime-reads").toFile()

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

    private fun source(logFile: () -> File?): AndroidHostRuntimeSource = AndroidHostRuntimeSource(
        context = mockk<Context>(relaxed = true),
        logFile = logFile,
        jankSnapshot = { MainThreadJankTracker.Snapshot(1, 2, 3, 900L, windowStartedMs = 0L, nowMs = 120_000L) },
        latencySnapshot = { 40 to 120L },
    )

    // ── 行数与单行截断口径 ──────────────────────────────────────

    @Test
    fun `limit 口径缺省 200 且夹在 1 到 500`() {
        assertEquals(HostRuntimeReads.DEFAULT_LOG_LIMIT, HostRuntimeReads.logLimit(null))
        assertEquals(1, HostRuntimeReads.logLimit(0))
        assertEquals(1, HostRuntimeReads.logLimit(-5))
        assertEquals(7, HostRuntimeReads.logLimit(7))
        assertEquals(HostRuntimeReads.MAX_LOG_LIMIT, HostRuntimeReads.logLimit(9_999))
    }

    private fun writeLog(file: File, lines: List<String>): File = file.apply { writeText(lines.joinToString("\n")) }

    @Test
    fun `尾部取最近 N 行并保持文件里的先后顺序`() = runTest {
        val file = writeLog(File(tempDir, "muse.log"), (1..1_000).map { "line-$it" })
        val logs = source { file }.logs(200)
        assertEquals(200, logs.size)
        assertEquals("line-801", logs.first())
        assertEquals("line-1000", logs.last())
    }

    @Test
    fun `不足 N 行时给全部,日志文件缺失时给空而不是报错`() = runTest {
        val small = writeLog(File(tempDir, "small.log"), listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), source { small }.logs(200))
        assertEquals(emptyList<String>(), source { null }.logs(200))
        assertEquals(emptyList<String>(), source { File(tempDir, "never-created.log") }.logs(200))
    }

    @Test
    fun `超长行保头截断并加省略号,正常行原样`() = runTest {
        val long = "x".repeat(HostRuntimeReads.MAX_LINE_CHARS + 400)
        val file = writeLog(File(tempDir, "wide.log"), listOf("plain", long))
        val logs = source { file }.logs(200)
        assertEquals("plain", logs.first())
        assertEquals(HostRuntimeReads.MAX_LINE_CHARS, logs.last().length)
        assertTrue("保留的是行首(时间戳与标签在头部)", logs.last().startsWith("xxx"))
        assertTrue(logs.last().endsWith("…"))
    }

    @Test
    fun `只读当前日志文件,不回读轮转出去的上一份`() = runTest {
        val current = writeLog(File(tempDir, "current.log"), listOf("fresh"))
        writeLog(File(tempDir, "current.log.1"), listOf("stale"))
        assertEquals(listOf("fresh"), source { current }.logs(200))
    }

    // ── 统计投影 ────────────────────────────────────────────────

    private fun jankOf(jank: Int = 0, slow: Int = 0, block: Int = 0, worst: Long = 0, start: Long = 0, now: Long = 0) =
        MainThreadJankTracker.Snapshot(jank, slow, block, worst, windowStartedMs = start, nowMs = now)

    @Test
    fun `统计投影把两个计数器合成一份窗口统计`() {
        val stats = HostRuntimeReads.statsOf(
            jank = jankOf(jank = 11, slow = 4, block = 1, worst = 1_500L, start = 30_000L, now = 150_000L),
            webSamples = 120,
            webP95Ms = 210L,
        )
        assertEquals(120, stats.webSamples)
        assertEquals(210L, stats.webP95Ms)
        assertEquals(11, stats.jankCount)
        assertEquals(4, stats.slowCount)
        assertEquals(1, stats.blockCount)
        assertEquals(1_500L, stats.worstMs)
        assertEquals(120L, stats.windowSeconds)
    }

    @Test
    fun `样本不足时不给可信的 p95`() {
        val thin = HostRuntimeReads.statsOf(jankOf(), webSamples = 19, webP95Ms = 33L)
        assertEquals(33L, thin.webP95Ms)
        assertFalse("阈值以下的 p95 必须标不可信", thin.webP95Reliable)
        assertTrue(HostRuntimeReads.statsOf(jankOf(), webSamples = 20, webP95Ms = 33L).webP95Reliable)
        assertNull(HostRuntimeReads.statsOf(jankOf(), webSamples = 0, webP95Ms = null).webP95Ms)
    }

    @Test
    fun `窗口秒数只有一个来源`() = runTest {
        // 时间戳 0 是合法起点(elapsedRealtime 从开机算起),不能当哨兵;窗口长度也不许两处各算一份。
        assertEquals(0L, jankOf(start = 0L, now = 0L).windowSeconds)
        assertEquals(120L, source { null }.stats().windowSeconds)
        assertEquals(120L, HostRuntimeReads.statsOf(jankOf(start = 0L, now = 120_000L), 0, null).windowSeconds)
    }

    // ── 未装配回退 ──────────────────────────────────────────────

    @Test
    fun `未装配时三条命令照常回执空值`() = runTest {
        assertEquals(emptyList<String>(), HostRuntimeSource.Empty.logs(200))
        assertNull(HostRuntimeSource.Empty.diagnosticsJson())
        assertEquals(HostRuntimeStats(), HostRuntimeSource.Empty.stats())
    }

    // ── 网关真连 ────────────────────────────────────────────────

    @Test
    fun `三条命令走网关回执各自的载荷且不参与焦点锁`() = runTest {
        val stats = HostRuntimeStats(webSamples = 64, webP95Ms = 99L, webP95Reliable = true, jankCount = 2, windowSeconds = 60L)
        val source = FakeRuntimeSource(
            logLines = listOf("I/Chat: ok", "W/Db: slow"),
            diagnostics = """{"app":{"versionName":"2.7.0"}}""",
            runtimeStats = stats,
        )
        val gateway = gateway(HostReadSources(runtime = source))
        val browser = FakeConnection()
        val job = launch { gateway.serve(browser.session) }
        advanceUntilIdle()

        browser.send("""{"protocolVersion":1,"type":"runtime.logs","requestId":"req-logs","limit":50}""")
        browser.send("""{"protocolVersion":1,"type":"runtime.diagnostics","requestId":"req-diag"}""")
        browser.send("""{"protocolVersion":1,"type":"runtime.stats","requestId":"req-stats"}""")
        advanceUntilIdle()

        val logs = browser.events.single { it.type == HostRuntimeReads.LOGS_TYPE }
        assertEquals("req-logs", logs.requestId)
        assertNull(logs.error)
        assertEquals(listOf("I/Chat: ok", "W/Db: slow"), logs.runtimeLogs)
        assertEquals(50, source.requestedLimit)

        val diagnostics = browser.events.single { it.type == HostRuntimeReads.DIAGNOSTICS_TYPE }
        assertEquals("req-diag", diagnostics.requestId)
        assertEquals("""{"app":{"versionName":"2.7.0"}}""", diagnostics.runtimeDiagnostics)

        val statsEvent = browser.events.single { it.type == HostRuntimeReads.STATS_TYPE }
        assertEquals(stats, statsEvent.runtimeStats)

        job.cancel()
    }

    @Test
    fun `limit 不传时按缺省条数请求,装配缺失也回执空而不是报错`() = runTest {
        val source = FakeRuntimeSource()
        val gateway = gateway(HostReadSources(runtime = source))
        val browser = FakeConnection()
        val job = launch { gateway.serve(browser.session) }
        advanceUntilIdle()

        browser.send("""{"protocolVersion":1,"type":"runtime.logs","requestId":"req-default"}""")
        browser.send("""{"protocolVersion":1,"type":"runtime.diagnostics","requestId":"req-null"}""")
        advanceUntilIdle()

        assertEquals(HostRuntimeReads.DEFAULT_LOG_LIMIT, source.requestedLimit)
        val logs = browser.events.single { it.type == HostRuntimeReads.LOGS_TYPE }
        assertEquals(emptyList<String>(), logs.runtimeLogs)
        assertNull(logs.error)
        val diagnostics = browser.events.single { it.type == HostRuntimeReads.DIAGNOSTICS_TYPE }
        assertNull(diagnostics.runtimeDiagnostics)
        assertNull(diagnostics.error)

        job.cancel()
    }

    @Test
    fun `能力位声明三条运行命令,老 Host 客户端据此决定要不要发命令`() = runTest {
        val gateway = gateway(HostReadSources.Empty)
        val browser = FakeConnection()
        val job = launch { gateway.serve(browser.session) }
        advanceUntilIdle()

        val flags = browser.events.first { it.type == "state.snapshot" }.capabilityFlags
        assertTrue("缺 runtime_logs", flags.contains("runtime_logs"))
        assertTrue("缺 runtime_diagnostics", flags.contains("runtime_diagnostics"))
        assertTrue("缺 runtime_stats", flags.contains("runtime_stats"))

        job.cancel()
    }
}
