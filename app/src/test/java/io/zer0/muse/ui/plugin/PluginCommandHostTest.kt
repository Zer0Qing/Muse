package io.zer0.muse.ui.plugin

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.zer0.muse.data.plugin.PluginCommandRegistry
import io.zer0.muse.tools.SkillMediaToolsImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 插件命令的界面宿主：**什么时候拦截发送、什么时候放行**。
 *
 * 这里最要紧的一条是"不该拦截的绝不拦截"：
 * `/` 开头的普通文本（例如贴一个路径）必须照常发给模型 ——
 * 静默吞掉用户输入比少一个功能糟糕得多。
 */
class PluginCommandHostTest {

    private lateinit var registry: PluginCommandRegistry
    private lateinit var mediaTools: SkillMediaToolsImpl
    private var input = ""

    private val entry = PluginCommandRegistry.Entry(
        pluginId = "pnl",
        pluginName = "面板插件",
        name = "daily",
        description = "生成日报",
        takesInput = true,
        handler = "makeDaily",
    )

    @Before
    fun setUp() {
        registry = mockk(relaxed = true)
        mediaTools = mockk(relaxed = true)
        input = ""
    }

    private fun host() = PluginCommandHost(registry, mediaTools) { input = it }

    @Test
    fun `plain text is not intercepted`() = runBlocking {
        val handled = host().handle("今天天气不错")

        assertFalse("普通文本必须放行", handled)
    }

    @Test
    fun `unknown slash text is not intercepted either`() = runBlocking {
        // 用户可能只是想发一个以 / 开头的路径
        coEvery { registry.find(any()) } returns null
        val handled = host().handle("/usr/local/bin")

        assertFalse("不存在的命令必须放行给模型", handled)
    }

    @Test
    fun `matching command runs and writes the result back into the input`() = runBlocking {
        coEvery { registry.find("daily") } returns entry
        coEvery { mediaTools.invokePluginCommand("pnl", "makeDaily", "本周") } returns "日报正文"
        val h = host()

        val handled = h.handle("/daily 本周")

        assertTrue("命中的命令要被拦截", handled)
        assertEquals("结果写回输入框，用户可先看再发", "日报正文", input)
        coVerify(exactly = 1) { mediaTools.invokePluginCommand("pnl", "makeDaily", "本周") }
    }

    @Test
    fun `failure text also lands in the input so nothing is silent`() = runBlocking {
        coEvery { registry.find("daily") } returns entry
        coEvery { mediaTools.invokePluginCommand(any(), any(), any()) } returns "命令执行失败：插件已禁用"
        val h = host()

        h.handle("/daily")

        assertTrue("失败原因要能被用户看到: $input", input.contains("命令执行失败"))
    }

    @Test
    fun `suggestions appear only while typing a bare command name`() = runBlocking {
        coEvery { registry.list() } returns listOf(entry, entry.copy(name = "data"))
        val h = host()

        h.refresh("/da")
        assertEquals(listOf("daily", "data"), h.suggestions.map { it.name })

        h.refresh("/daily 参数")
        assertTrue("打了空格之后不再提示", h.suggestions.isEmpty())

        h.refresh("普通文本")
        assertTrue(h.suggestions.isEmpty())
    }

    @Test
    fun `command name matching ignores case`() = runBlocking {
        coEvery { registry.find("daily") } returns entry
        coEvery { mediaTools.invokePluginCommand(any(), any(), any()) } returns "ok"
        val h = host()

        assertTrue(h.handle("/DAILY"))
    }
}
