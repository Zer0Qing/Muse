package io.zer0.muse.channel

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ChannelContactMetaStoreTest {

    @After
    fun tearDown() {
        ChannelContactMetaStore.resetForTest()
    }

    private fun attachTempContext(): Context {
        val dir = Files.createTempDirectory("channel-meta-test").toFile()
        val ctx = mockk<Context>()
        every { ctx.applicationContext } returns ctx
        every { ctx.filesDir } returns dir
        ChannelContactMetaStore.attach(ctx)
        return ctx
    }

    @Test
    fun `resolvedName prioritizes alias over observed displayName over id`() {
        val ctx = attachTempContext()
        val chId = "ch-feishu"
        val userId = "ou_1234567890abcdef1234567890"

        assertEquals("ou_12345…567890", ChannelContactMetaStore.resolvedName(chId, userId, "群聊"))

        ChannelContactMetaStore.recordObservedProfile(chId, userId, "张三", isGroup = false)
        assertEquals("张三", ChannelContactMetaStore.resolvedName(chId, userId, "群聊"))

        ChannelContactMetaStore.setAlias(chId, userId, "产品经理小张")
        assertEquals("产品经理小张", ChannelContactMetaStore.resolvedName(chId, userId, "群聊"))

        // Re-attach from disk on same filesDir (simulates process restart after reset)
        val filesDir = ctx.filesDir
        ChannelContactMetaStore.resetForTest()
        val restartCtx = mockk<Context>()
        every { restartCtx.applicationContext } returns restartCtx
        every { restartCtx.filesDir } returns filesDir
        ChannelContactMetaStore.attach(restartCtx)
        assertEquals("产品经理小张", ChannelContactMetaStore.resolvedName(chId, userId, "群聊"))
    }

    @Test
    fun `aiPaused and assistant override persist cleanly and prune with channel`() {
        attachTempContext()
        ChannelContactMetaStore.setAiPaused("ch-a", "user-1", true)
        ChannelContactMetaStore.setAssistantOverride("ch-a", "user-1", "assistant-coder")

        assertTrue(ChannelContactMetaStore.isAiPaused("ch-a", "user-1"))
        assertEquals("assistant-coder", ChannelContactMetaStore.get("ch-a", "user-1")?.assistantIdOverride)

        ChannelContactMetaStore.removeForChannel("ch-a")
        assertFalse(ChannelContactMetaStore.isAiPaused("ch-a", "user-1"))
    }
}
