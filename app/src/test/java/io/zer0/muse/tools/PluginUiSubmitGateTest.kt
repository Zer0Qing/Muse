package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.data.plugin.PluginBridgeRouter
import io.zer0.muse.data.plugin.PluginCapabilityGateway
import io.zer0.muse.data.plugin.PluginCapabilityLedgers
import io.zer0.muse.data.plugin.PluginFunctionInvoker
import io.zer0.muse.data.plugin.PluginManager
import io.zer0.muse.data.plugin.PluginManifest
import io.zer0.muse.tools.script.SkillBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 面板提交（`submit` handler）的门控回归。
 *
 * 这里测的不是"能不能跑起来"，而是**什么情况下必须不跑**：
 * 一次表单提交会改插件自己的数据，所以默认关的 `ui.interactive` 必须真的挡住它，
 * 而且每条拒绝都要说清去哪儿开 —— 只回"未授权"等于把人赶回黑盒。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PluginUiSubmitGateTest {

    private lateinit var context: Context
    private lateinit var manager: PluginManager
    private lateinit var ledger: PluginCapabilityGateway

    private val manifest = PluginManifest(
        id = "pnl",
        name = "面板插件",
        runtime = PluginFunctionInvoker.RUNTIME_NODE,
        capabilities = listOf("storage", PluginCapabilityGateway.UI_INTERACTIVE),
        entry = "main.js",
    )

    private val plugin = mockk<PluginManager.InstalledPlugin>(relaxed = true)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 账本是**进程级静态单例**，且把 filesDir 钉在第一个调用者身上。
        // 不重置的话：本用例会读到上一个用例的授权状态，甚至往已被删除的旧目录写盘 ——
        // 这正是本仓库那族"单跑必过、全量偶发红"的一个来源。必须每个用例从零开始。
        PluginCapabilityLedgers.resetForTests()
        manager = mockk(relaxed = true)
        ledger = PluginCapabilityLedgers.shared(context)
        every { manager.findPlugin("pnl") } returns plugin
        every { plugin.enabled } returns true
        every { plugin.name } returns "面板插件"
        every { manager.loadVerifiedPlugin("pnl") } returns mockk(relaxed = true) {
            every { this@mockk.manifest } returns this@PluginUiSubmitGateTest.manifest
        }
        every { manager.loadVerifiedFunction("pnl", "save") } returns mockk(relaxed = true) {
            every { this@mockk.entryCode } returns "function save(){}"
        }
        every { manager.getPluginConfigJson(any()) } returns "{}"
    }

    private fun tools() = SkillMediaToolsImpl(
        context = context,
        stickerLibraryRepository = null,
        imageService = null,
        pluginManager = manager,
    )

    @Test
    fun `submit is refused when the interactive capability has not been approved`() {
        // 播种按 install-auto 放行大部分能力，ui.interactive 是例外：默认必须关。
        ledger.seedFromDeclaration("pnl", manifest.capabilities)

        val result = runBlocking { tools().invokeUiSubmitHandler("pnl", "save", "{}") }

        val reason = (result as? SkillMediaToolsImpl.UiSubmitResult.Rejected)?.reason.orEmpty()
        assertTrue("必须拒绝未批准的提交，实际: $result", reason.isNotEmpty())
        assertTrue("要点名缺哪条能力: $reason", reason.contains("ui.interactive"))
        assertTrue("要给出去哪儿开: $reason", reason.contains("能力与权限"))
    }

    @Test
    fun `seeding leaves it off and only an explicit grant turns it on`() {
        ledger.seedFromDeclaration("pnl", manifest.capabilities)
        val afterSeed = ledger.isApproved("pnl", PluginCapabilityGateway.UI_INTERACTIVE)

        ledger.grant("pnl", PluginCapabilityGateway.UI_INTERACTIVE, true, manifest.capabilities)
        val afterGrant = ledger.isApproved("pnl", PluginCapabilityGateway.UI_INTERACTIVE)

        assertTrue("播种后必须是关的", !afterSeed)
        assertTrue("用户显式授予后必须是开的", afterGrant)
    }

    @Test
    fun `disabled plugin is refused with its name`() {
        every { plugin.enabled } returns false

        val result = runBlocking { tools().invokeUiSubmitHandler("pnl", "save", "{}") }

        assertTrue("要说明插件被禁用: $result", (result as? SkillMediaToolsImpl.UiSubmitResult.Rejected)?.reason?.contains("已禁用") == true)
    }

    @Test
    fun `js runtime is refused with the reason instead of failing silently`() {
        // js 档单轮桥接：handler 存完数据就回不来了，与其静默什么都不做，不如说明为什么
        val jsManifest = manifest.copy(
            runtime = "js",
        )
        every { manager.loadVerifiedPlugin("pnl") } returns mockk(relaxed = true) {
            every { this@mockk.manifest } returns jsManifest
        }
        ledger.grant("pnl", PluginCapabilityGateway.UI_INTERACTIVE, true, manifest.capabilities)

        val result = runBlocking { tools().invokeUiSubmitHandler("pnl", "save", "{}") }

        assertTrue(
            "要说清需要 node: $result",
            (result as? SkillMediaToolsImpl.UiSubmitResult.Rejected)?.reason?.contains("runtime=node") == true,
        )
    }

    @Test
    fun `undeclared handler is refused`() {
        every { manager.loadVerifiedFunction("pnl", "save") } returns null
        ledger.grant("pnl", PluginCapabilityGateway.UI_INTERACTIVE, true, manifest.capabilities)

        val result = runBlocking { tools().invokeUiSubmitHandler("pnl", "save", "{}") }

        assertTrue("要点名函数未在清单中声明: $result", (result as? SkillMediaToolsImpl.UiSubmitResult.Rejected)?.reason?.contains("未在清单中声明") == true)
    }

    @Test
    fun `missing plugin manager refuses rather than pretending there is no plugin`() {
        val tools = SkillMediaToolsImpl(
            context = context,
            stickerLibraryRepository = null,
            imageService = null,
            pluginManager = null,
        )

        val result = runBlocking { tools.invokeUiSubmitHandler("pnl", "save", "{}") }

        assertTrue("缺依赖要拒绝: $result", result is SkillMediaToolsImpl.UiSubmitResult.Rejected)
    }

    @Test
    fun `router denies an action the ledger has not granted`() = runBlocking {
        // 面板提交与工具路径共用这一个路由器：它必须自己就 fail-closed，
        // 而不是靠调用方"记得"先查一遍权限。
        ledger.seedFromDeclaration("pnl", manifest.capabilities)
        val router = PluginBridgeRouter(context = context, writeConfig = { _, _, _ -> })

        val result = router.handle(
            """{"__bridge__":true,"action":"http_get","params":{"url":"https://example.com"}}""",
            "pnl",
            manifest,
        )

        assertTrue("未声明的 network 动作必须被拒，实际: $result", result is SkillBridge.HandleResult.Failure)
    }
}
