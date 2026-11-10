package io.zer0.muse.data.plugin

import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.plugin.ProviderPluginRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * provider 插件并入供应商注册表（插件计划）。
 *
 * 要防的是"两套生命周期各管一套"：用户卸了插件，供应商还留在列表里；
 * 或者插件启用了，供应商还得再手工丢一个 JSON。
 */
class PluginProviderSyncTest {

    private lateinit var pluginsRoot: File
    private lateinit var registry: ProviderPluginRegistry
    private lateinit var manager: PluginManager
    private lateinit var sync: PluginProviderSync

    @Before
    fun setUp() {
        pluginsRoot = kotlin.io.path.createTempDirectory("muse-provider-plugins").toFile()
        // 内存模式注册表：不落盘，测试之间互不影响
        registry = ProviderPluginRegistry(storageDir = null)
        manager = mockk(relaxed = true)
        every { manager.pluginDirFor(any()) } answers { File(pluginsRoot, firstArg<String>()) }
        sync = PluginProviderSync(manager, registry)
    }

    @After
    fun tearDown() {
        pluginsRoot.deleteRecursively()
    }

    private fun plugin(id: String, enabled: Boolean = true): PluginManager.InstalledPlugin {
        val p = mockk<PluginManager.InstalledPlugin>(relaxed = true)
        every { p.id } returns id
        every { p.name } returns id
        every { p.enabled } returns enabled
        every { p.kind } returns "provider"
        return p
    }

    private fun writeDeclaration(pluginId: String, content: String) {
        val dir = File(pluginsRoot, pluginId).apply { mkdirs() }
        File(dir, "provider.json").writeText(content)
    }

    private fun validJson(name: String = "中转站") = """{"id":"ignored","displayName":"$name","baseUrl":"https://relay.example.com/v1"}"""

    @Test
    fun `an enabled provider plugin is registered under its package id`() = runBlocking {
        writeDeclaration("relay", validJson())
        every { manager.list() } returns listOf(plugin("relay"))

        val report = sync.sync()

        assertEquals(1, report.registered)
        val registered = registry.list().single()
        assertEquals("id 必须用包 id，避免两个插件顶掉彼此", "relay", registered.id)
        assertEquals("中转站", registered.displayName)
    }

    @Test
    fun `a disabled plugin is unregistered so no orphan provider remains`() = runBlocking {
        writeDeclaration("relay", validJson())
        every { manager.list() } returns listOf(plugin("relay"))
        sync.sync()

        every { manager.list() } returns listOf(plugin("relay", enabled = false))
        val report = sync.sync()

        assertEquals(1, report.removed)
        assertTrue("禁用后不该留下供应商", registry.list().isEmpty())
    }

    @Test
    fun `a broken declaration removes the provider instead of keeping a stale one`() = runBlocking {
        writeDeclaration("relay", validJson())
        every { manager.list() } returns listOf(plugin("relay"))
        sync.sync()

        writeDeclaration("relay", "{ not json")
        val report = sync.sync()

        assertEquals(1, report.removed)
        assertTrue(registry.list().isEmpty())
    }

    @Test
    fun `a non https base url is refused`() = runBlocking {
        writeDeclaration("relay", """{"id":"x","displayName":"x","baseUrl":"http://relay.example.com/v1"}""")
        every { manager.list() } returns listOf(plugin("relay"))

        sync.sync()

        assertTrue("明文地址不该被注册", registry.list().isEmpty())
    }

    @Test
    fun `an uninstalled plugin leaves no provider behind`() = runBlocking {
        writeDeclaration("relay", validJson())
        every { manager.list() } returns listOf(plugin("relay"))
        sync.sync()

        every { manager.list() } returns emptyList()
        val report = sync.sync()

        assertEquals(1, report.removed)
        assertTrue(registry.list().isEmpty())
    }

    @Test
    fun `a plugin without a declaration file registers nothing`() = runBlocking {
        every { manager.list() } returns listOf(plugin("plain"))

        val report = sync.sync()

        assertEquals(0, report.registered)
        assertTrue(registry.list().isEmpty())
    }

    @Test
    fun `sync is idempotent`() = runBlocking {
        writeDeclaration("relay", validJson())
        every { manager.list() } returns listOf(plugin("relay"))

        sync.sync()
        sync.sync()

        assertEquals(1, registry.list().size)
    }
}
