package io.zer0.muse.data.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 插件能力总线（P0-1）的契约测试。
 *
 * 核心要守住的不是"映射对不对"，而是三条语义：
 *  1. 默认拒绝 —— 没声明/没授权就是调不到；
 *  2. 撤销立即生效 —— 不缓存在"注册工具列表"那一刻；
 *  3. 升级扩权不自动放行 —— 否则账本就白做了。
 */
class PluginCapabilityGatewayTest {

    private fun newFile(): File = Files.createTempDirectory("plugin-perms").resolve("permissions.json").toFile()

    private fun gateway(file: File = newFile()) = PluginCapabilityGateway(file) { 1_000L }

    @Test
    fun `first sight grants exactly what the manifest declares`() {
        val gw = gateway()
        val declared = listOf("network", "resource.read")

        val actions = gw.allowedActions("p1", declared)

        assertTrue("声明 network 应给出 http_get", actions.contains("http_get"))
        assertTrue("声明 resource.read 应给出 fs_read", actions.contains("fs_read"))
        assertFalse("未声明 resource.write 不能有 fs_write，实际: $actions", actions.contains("fs_write"))
        assertTrue("echo 属通路自检，始终可用", actions.contains("echo"))
    }

    @Test
    fun `undeclared capability is denied with an actionable reason`() {
        val gw = gateway()
        val declared = listOf("network")

        val decision = gw.check("p1", declared, "fs_write") as PluginCapabilityGateway.Decision.Deny

        assertEquals("应指回缺失的能力名", "resource.write", decision.capability)
        assertTrue("应说明是清单没声明，实际: ${decision.reason}", decision.reason.contains("未声明"))
    }

    @Test
    fun `revoking a capability takes effect on the very next call`() {
        val gw = gateway()
        val declared = listOf("network")
        assertTrue(gw.check("p1", declared, "http_get") == PluginCapabilityGateway.Decision.Allow)

        gw.grant("p1", capability = "network", allowed = false, declared = declared)

        assertTrue(
            "撤销后下次调用必须被拒",
            gw.check("p1", declared, "http_get") is PluginCapabilityGateway.Decision.Deny,
        )
    }

    @Test
    fun `once tier allows exactly one call`() {
        val file = newFile()
        val gw = gateway(file)
        val declared = listOf("network")
        gw.grant("p1", capability = "network", allowed = false, declared = declared)

        gw.grant("p1", capability = "network", allowed = true, declared = declared, tier = PluginPermissionTier.ONCE)

        assertTrue(gw.check("p1", declared, "http_get") == PluginCapabilityGateway.Decision.Allow)
        assertTrue(
            "ONCE 应该只放行一次",
            gw.check("p1", declared, "http_get") is PluginCapabilityGateway.Decision.Deny,
        )
    }

    @Test
    fun `session tier survives calls within the process but not across restarts`() {
        val file = newFile()
        val gw = gateway(file)
        val declared = listOf("network")
        gw.grant("p1", capability = "network", allowed = false, declared = declared)
        gw.grant("p1", capability = "network", allowed = true, declared = declared, tier = PluginPermissionTier.SESSION)

        assertTrue(gw.check("p1", declared, "http_get") == PluginCapabilityGateway.Decision.Allow)
        assertTrue(gw.check("p1", declared, "http_get") == PluginCapabilityGateway.Decision.Allow)

        // 新实例 = 进程重启：会话档不得复活
        val restarted = gateway(file)
        assertTrue(
            "SESSION 档不应落盘",
            restarted.check("p1", declared, "http_get") is PluginCapabilityGateway.Decision.Deny,
        )
    }

    @Test
    fun `added capability after upgrade is not auto granted and asks for review`() {
        val gw = gateway()
        gw.approveDeclaration("p1", listOf("network"))

        val upgraded = listOf("network", "resource.write")

        assertTrue("新增能力应触发重审", gw.addedCapabilities("p1", upgraded).isNotEmpty())
        assertEquals(setOf("resource.write"), gw.addedCapabilities("p1", upgraded))
        assertTrue(
            "新增能力在批准前不可用",
            gw.check("p1", upgraded, "fs_write") is PluginCapabilityGateway.Decision.Deny,
        )

        gw.approveDeclaration("p1", upgraded)
        assertTrue("批准后不再需要重审", gw.addedCapabilities("p1", upgraded).isEmpty())
        assertTrue(gw.check("p1", upgraded, "fs_write") == PluginCapabilityGateway.Decision.Allow)
    }

    @Test
    fun `shrinking the declaration never asks for review`() {
        val gw = gateway()
        gw.approveDeclaration("p1", listOf("network", "resource.read"))

        assertTrue(gw.addedCapabilities("p1", listOf("network")).isEmpty())
        assertTrue("缩权后多余能力不该还能用", gw.allowedActions("p1", listOf("network")).intersect(setOf("fs_read")).isEmpty())
    }

    @Test
    fun `always grants survive a process restart`() {
        val file = newFile()
        gateway(file).approveDeclaration("p1", listOf("network"))

        val restarted = gateway(file)

        assertTrue(restarted.check("p1", listOf("network"), "http_post") == PluginCapabilityGateway.Decision.Allow)
    }

    @Test
    fun `revoking everything clears the plugin record`() {
        val file = newFile()
        val gw = gateway(file)
        gw.approveDeclaration("p1", listOf("network"))

        gw.revokeAll("p1")

        assertTrue("账本记录应被清空", gw.permissionView("p1").declaredCapabilities.isEmpty())
        // 重装（同一 id 再次出现）会按新声明重新播种 —— 与"安装即授权"语义一致，
        // 撤销历史不复活，因此这里期望重新可用。
        assertTrue(gw.check("p1", listOf("network"), "http_get") == PluginCapabilityGateway.Decision.Allow)
    }

    @Test
    fun `declaration hash ignores order and duplicates`() {
        val a = PluginCapabilityGateway.declarationHash(listOf("network", "resource.read", "network"))
        val b = PluginCapabilityGateway.declarationHash(listOf("resource.read", "network"))

        assertEquals(a, b)
    }

    @Test
    fun `unknown bridge action is denied rather than silently allowed`() {
        val gw = gateway()

        val decision = gw.check("p1", listOf("network"), "rm_rf_root")

        assertTrue("未登记的动作必须 fail-closed", decision is PluginCapabilityGateway.Decision.Deny)
    }

    @Test
    fun `audit log records denials for the permission page`() {
        val gw = gateway()

        gw.check("p1", listOf("network"), "fs_write")

        val audit = gw.recentAudit()
        assertTrue("应有拒绝记录", audit.any { !it.allowed && it.capability == "resource.write" })
    }

    @Test
    fun `ledger-only path stays locked until something is seeded`() {
        // Hook 与启动激活拿不到"已重验的 manifest"，只查账：没播种过就必须只剩 echo。
        val gw = gateway()

        assertEquals(setOf("echo"), gw.approvedActions("never-seen"))
        assertFalse(gw.isApproved("never-seen", "network"))
    }

    @Test
    fun `seeding from a verified manifest unlocks the ledger path`() {
        val gw = gateway()
        gw.seedFromDeclaration("p1", listOf("network"))

        assertTrue(gw.isApproved("p1", "network"))
        assertTrue(gw.approvedActions("p1").contains("http_post"))
    }

    @Test
    fun `seeding an existing record never grants the new capability`() {
        val gw = gateway()
        gw.seedFromDeclaration("p1", listOf("network"))

        val upgraded = listOf("network", "resource.write")
        gw.seedFromDeclaration("p1", upgraded)

        assertTrue("播种不得自动放行新增能力", gw.addedCapabilities("p1", upgraded) == setOf("resource.write"))
        assertFalse(gw.isApproved("p1", "resource.write"))
    }

    @Test
    fun `storage write alias grants the same file actions as resource write`() {
        // 仓库里两套拼写并存（内置清单与 sign.py 用 resource.*，文档与 author_plugin 用 storage.*）；
        // 只认一套会让真实存在的包被新闸门静默挡死。
        val gw = gateway()

        assertTrue(gw.allowedActions("p1", listOf("storage.write")).contains("fs_delete"))
        assertTrue(gw.allowedActions("p2", listOf("storage.read")).contains("fs_list"))
        assertEquals(
            gw.allowedActions("pA", listOf("resource.write")),
            gw.allowedActions("pB", listOf("storage.write")),
        )
    }

    @Test
    fun `non capability declaration grants no bridge action at all`() {
        val gw = gateway()

        val actions = gw.allowedActions("p1", listOf("ui", "ui.skin"))

        assertEquals("UI 类能力不该带来任何文件/网络动作", setOf("echo"), actions)
    }

    @Test
    fun `python runtime is off by default`() {
        val gw = gateway()
        gw.seedFromDeclaration("p1", listOf("network"))

        assertFalse(gw.isApproved("p1", PluginCapabilityGateway.RUNTIME_PYTHON))
    }

    @Test
    fun `a manifest declaring runtime python never self grants it`() {
        // 这是本档存在的理由：如果清单能自我声明，Python 后门就只是换了个写法。
        val gw = gateway()
        gw.seedFromDeclaration("p1", listOf("network", PluginCapabilityGateway.RUNTIME_PYTHON))

        assertFalse(
            "清单声明不得等于授权",
            gw.isApproved("p1", PluginCapabilityGateway.RUNTIME_PYTHON),
        )
        assertFalse(gw.grantedCapabilities("p1", listOf("network", "runtime.python")).contains("runtime.python"))
    }

    @Test
    fun `only an explicit user grant turns python on`() {
        val gw = gateway()
        gw.seedFromDeclaration("p1", listOf("network"))

        gw.grant("p1", PluginCapabilityGateway.RUNTIME_PYTHON, allowed = true, declared = listOf("network"))

        assertTrue(gw.isApproved("p1", PluginCapabilityGateway.RUNTIME_PYTHON))
        val view = gw.permissionView("p1")
        assertEquals(
            PluginCapabilityGrant.DECIDED_BY_USER,
            view.grants[PluginCapabilityGateway.RUNTIME_PYTHON]?.decidedBy,
        )
        assertFalse("Python 不该混进可声明能力集合", view.declaredCapabilities.contains("runtime.python"))
    }

    @Test
    fun `python cannot be allowed merely for the session`() {
        // 会话档是给普通能力用的"临时放行"；Python 等于整台应用的数据目录，不允许临时。
        val gw = gateway()
        gw.seedFromDeclaration("p1", listOf("network"))

        gw.grant(
            pluginId = "p1",
            capability = PluginCapabilityGateway.RUNTIME_PYTHON,
            allowed = true,
            declared = listOf("network"),
            tier = PluginPermissionTier.SESSION,
        )

        assertFalse(gw.isApproved("p1", PluginCapabilityGateway.RUNTIME_PYTHON))
    }

    @Test
    fun `storage capability grants exactly the kv actions`() {
        val gw = gateway()

        val actions = gw.allowedActions("p1", listOf("storage"))

        assertEquals(setOf("storage_get", "storage_set", "storage_delete", "storage_list"), actions - setOf("echo"))
        assertFalse("storage 不该顺带给出 fs_write", actions.contains("fs_write"))
    }
}
