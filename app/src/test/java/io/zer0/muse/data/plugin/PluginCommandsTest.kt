package io.zer0.muse.data.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.tools.SkillMediaToolsImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 插件斜杠命令的发现与校验。
 *
 * 这里守的是"用户会看到的那些错"：命令名写错、两个插件抢同一个名字、
 * 插件被禁用后命令还挂在列表里、说明长到把列表撑爆。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PluginCommandsTest {

    private lateinit var context: Context
    private lateinit var manager: PluginManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = mockk(relaxed = true)
    }

    private fun plugin(id: String, enabled: Boolean = true): PluginManager.InstalledPlugin {
        val p = mockk<PluginManager.InstalledPlugin>(relaxed = true)
        every { p.id } returns id
        every { p.name } returns id
        every { p.enabled } returns enabled
        every { p.kind } returns "tool"
        return p
    }

    private fun verified(id: String, commands: List<PluginCommandDeclaration>): PluginManager.VerifiedPlugin {
        val v = mockk<PluginManager.VerifiedPlugin>(relaxed = true)
        every { v.manifest } returns PluginManifest(id = id, name = id, commands = commands)
        return v
    }

    private fun registry(enabled: Boolean = true) = PluginCommandRegistry(manager) { enabled }

    @Test
    fun `commands are listed only for enabled plugins`() = runBlocking {
        every { manager.list() } returns listOf(plugin("a"), plugin("b", enabled = false))
        every { manager.loadVerifiedPlugin("a") } returns verified("a", listOf(decl("daily", "run")))
        every { manager.loadVerifiedPlugin("b") } returns verified("b", listOf(decl("secret", "run")))

        val names = registry().list().map { it.name }

        assertEquals(listOf("daily"), names)
    }

    @Test
    fun `global switch off yields no commands`() = runBlocking {
        every { manager.list() } returns listOf(plugin("a"))
        every { manager.loadVerifiedPlugin("a") } returns verified("a", listOf(decl("daily", "run")))

        assertTrue("总开关关闭时不该暴露命令", registry(enabled = false).list().isEmpty())
    }

    @Test
    fun `invalid command name is skipped instead of breaking the list`() = runBlocking {
        every { manager.list() } returns listOf(plugin("a"))
        every { manager.loadVerifiedPlugin("a") } returns verified(
            "a",
            listOf(decl("Bad Name", "run"), decl("good", "run")),
        )

        val names = registry().list().map { it.name }

        assertEquals("坏名字只跳过自己，不能拖垮整张表", listOf("good"), names)
    }

    @Test
    fun `duplicate names resolve to the first plugin by id and never silently overwrite`() = runBlocking {
        every { manager.list() } returns listOf(plugin("b"), plugin("a"))
        every { manager.loadVerifiedPlugin("a") } returns verified("a", listOf(decl("daily", "run")))
        every { manager.loadVerifiedPlugin("b") } returns verified("b", listOf(decl("daily", "run")))

        val entry = registry().list().single()

        assertEquals("按插件 id 排序，先到先得", "a", entry.pluginId)
    }

    @Test
    fun `find is case insensitive and tolerates a leading slash`() = runBlocking {
        every { manager.list() } returns listOf(plugin("a"))
        every { manager.loadVerifiedPlugin("a") } returns verified("a", listOf(decl("daily-report", "run")))

        assertEquals("daily-report", registry().find("/DAILY-Report")?.name)
        assertNull(registry().find("/nope"))
    }

    @Test
    fun `validation states the rule instead of just refusing`() {
        assertTrue(
            "大写要说明规则",
            PluginCommandRegistry.validate(decl("Daily", "run"))?.contains("小写字母开头") == true,
        )
        assertTrue(
            "空 handler 要点名",
            PluginCommandRegistry.validate(decl("ok", ""))?.contains("缺 handler") == true,
        )
        val longDesc = decl("ok", "run").copy(description = "说".repeat(PluginCommandRegistry.MAX_DESCRIPTION_CHARS + 1))
        assertTrue(
            "说明超长要说明它会进列表",
            PluginCommandRegistry.validate(longDesc)?.contains("命令列表") == true,
        )
        assertNull("正常声明不该报错", PluginCommandRegistry.validate(decl("ok", "run")))
    }

    @Test
    fun `per plugin command count is capped`() = runBlocking {
        val many = (1..(PluginCommandRegistry.MAX_COMMANDS_PER_PLUGIN + 5)).map { decl("c$it", "run") }
        every { manager.list() } returns listOf(plugin("a"))
        every { manager.loadVerifiedPlugin("a") } returns verified("a", many)

        assertEquals(PluginCommandRegistry.MAX_COMMANDS_PER_PLUGIN, registry().list().size)
    }

    // ── 执行侧的拒绝（命令本身能列出来，但跑不动时要说清原因） ──────────────

    @Test
    fun `running a command on a disabled plugin is refused with its name`() = runBlocking {
        val tools = mediaTools()
        every { manager.findPlugin("a") } returns plugin("a", enabled = false)

        val text = tools.invokePluginCommand("a", "run", "x")

        assertTrue("要说明插件被禁用: $text", text.contains("已禁用"))
    }

    @Test
    fun `running an undeclared handler is refused`() = runBlocking {
        val tools = mediaTools()
        every { manager.findPlugin("a") } returns plugin("a")
        every { manager.loadVerifiedPlugin("a") } returns verified("a", emptyList())
        every { manager.loadVerifiedFunction("a", "run") } returns null

        val text = tools.invokePluginCommand("a", "run", "x")

        assertTrue("要点名函数未声明: $text", text.contains("未在清单中声明"))
    }

    private fun mediaTools() = SkillMediaToolsImpl(
        context = context,
        stickerLibraryRepository = null,
        imageService = null,
        pluginManager = manager,
    )

    private fun decl(name: String, handler: String) = PluginCommandDeclaration(name = name, handler = handler)
}
