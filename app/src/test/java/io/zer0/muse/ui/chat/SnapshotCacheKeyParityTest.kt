package io.zer0.muse.ui.chat

import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.ui.ChatUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 静态快照缓存 key 的读写一致性。
 *
 * 读取端（本包的 buildSystemPromptForStream）与写入端（ChatViewModel.refreshContextInfo）
 * 共用 [ChatGenerationController.computeFullSnapshotKey]，只要有一侧漏传 sessionId，
 * 两侧 key 就永不相等 —— 表现是每次进会话后的首条消息都整份重建静态快照。
 *
 * 同时钉住 buildStaticSnapshot 的 sessionId：它决定「跨会话衔接」能否排除当前会话自己，
 * 漏传会把本轮对话尾巴当成「上一个会话」再注入一遍。
 */
class SnapshotCacheKeyParityTest {

    private val state = ChatUiState()

    private val controller: ChatGenerationController by lazy {
        val accessor = mockk<ChatStateAccessor>()
        every { accessor.snapshot } returns state
        ChatGenerationController(
            deps = mockk(relaxed = true),
            accessor = accessor,
            chatGenerationManager = mockk(relaxed = true),
            sessionManager = mockk(relaxed = true),
            settings = mockk(relaxed = true),
            notificationManager = mockk(relaxed = true),
            onCancelAncillaryJobs = {},
            onCancelPendingApprovals = {},
        )
    }

    private fun key(sessionId: String?): String = controller.computeFullSnapshotKey(
        assistant = null,
        memoryEnabled = false,
        useGlobalMemory = false,
        memoryScope = "main",
        memorySpaceId = "default",
        sessionId = sessionId,
    )

    @Test
    fun `session id takes part in snapshot key identity`() {
        assertNotEquals(key(null), key("s1"))
        assertNotEquals(key("s1"), key("s2"))
        assertEquals(key("s1"), key("s1"))
        assertTrue(key("s1").endsWith("|session=s1"))
        assertTrue(key(null).endsWith("|session="))
    }

    @Test
    fun `generation path feeds session id into both key and snapshot build`() {
        val text = sourceOf("io/zer0/muse/ui/chat/ChatGenerationController.kt")

        val keyArgs = between(text, "val currentKey =", "val staticSnapshot =")
        assertTrue(keyArgs.contains("computeFullSnapshotKey("))
        assertTrue(keyArgs.contains("sessionId = effSid,"))

        val buildArgs = between(text, "val staticSnapshot =", "is StaticPromptOutcome.Ready")
        assertTrue(buildArgs.contains("buildStaticSnapshot("))
        assertTrue(buildArgs.contains("sessionId = effSid,"))
    }

    @Test
    fun `write path puts the same session dimension into the key`() {
        val text = sourceOf("io/zer0/muse/ui/ChatViewModel.kt")
        val writeArgs = between(
            text,
            "cachedStaticSnapshotKey = generationController.computeFullSnapshotKey(",
            ")",
        )
        assertTrue(writeArgs.contains("sessionId = effSessionId,"))
    }

    /** 该文件是 CRLF，片段锚点必须取单行、不能带换行，否则匹配不到。 */
    private fun between(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        check(from >= 0) { "anchor not found: $start" }
        val to = text.indexOf(end, from + start.length)
        check(to > from) { "end anchor not found: $end" }
        return text.substring(from, to)
    }

    private fun sourceOf(relative: String): String = listOf(
        Path.of("src/main/java/$relative"),
        Path.of("app/src/main/java/$relative"),
    )
        .firstOrNull(Files::exists)
        ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
        ?: error("$relative not found")
}
