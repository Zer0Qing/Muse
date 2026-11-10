package io.zer0.muse.tools.script

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** 外部插件桥接能力门测试。 */
class SkillBridgeTest {

    @Test
    fun networkBridgeIsRejectedWhenPluginHasNoNetworkCapability() = runBlocking {
        val result = SkillBridge.tryHandle(
            """{"__bridge__":true,"action":"http_get","params":{"url":"https://example.com"}}""",
            allowedActions = setOf("echo"),
        )

        assertTrue(result is SkillBridge.HandleResult.Failure)
        val message = (result as SkillBridge.HandleResult.Failure).message
        // P0-2 起拒绝文案必须可行动：只说"不在放行集内"模型只会原样重试。
        assertTrue("应说明是未授权，实际: $message", message.contains("未获授权"))
        assertTrue("应指出去哪儿批准，实际: $message", message.contains("插件"))
        assertTrue("应给出动作名便于自查，实际: $message", message.contains("http_get"))
    }

    @Test
    fun allActionsAllowsNetworkBridgeByDefault() = runBlocking {
        // v2.x（沙盒放开）：默认全放行，http_get 不再需要插件声明能力。
        // 注意：不实际发网络请求，仅验证门不拦（url 为非法时会被 SSRF/协议校验挡在下一层）。
        val result = SkillBridge.tryHandle(
            """{"__bridge__":true,"action":"device_info","params":{}}""",
            allowedActions = SkillBridge.ALL_ACTIONS,
        )

        assertTrue(result is SkillBridge.HandleResult.Output)
    }

    @Test
    fun allActionsIncludesEveryBridgeAction() {
        // 防止将来误把动作从全放行集里去掉。
        val expected = setOf(
            "echo", "http_get", "http_post", "fs_list", "fs_read", "fs_write", "fs_delete",
            "clipboard_read", "clipboard_write", "notify", "device_info",
        )
        assertTrue(SkillBridge.ALL_ACTIONS.containsAll(expected))
    }

    @Test
    fun echoBridgeRemainsAvailableForSandboxedPlugin() = runBlocking {
        val result = SkillBridge.tryHandle(
            """{"__bridge__":true,"action":"echo","params":{"value":"ok"}}""",
            allowedActions = setOf("echo"),
        )

        assertTrue(result is SkillBridge.HandleResult.Output)
        assertTrue((result as SkillBridge.HandleResult.Output).json.contains("ok"))
    }

    @Test
    fun `storage kv round trip respects value limit`(): Unit = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("muse-kv").toFile()
        val host = SkillBridge.Host(context = null, fsRoot = root)
        val allowed = setOf("storage_get", "storage_set", "storage_delete", "storage_list")

        val set = SkillBridge.tryHandle(
            """{"__bridge__":true,"action":"storage_set","params":{"key":"a.b","value":{"n":1}}}""",
            allowed,
            host,
        )
        val read = SkillBridge.tryHandle(
            """{"__bridge__":true,"action":"storage_get","params":{"key":"a.b"}}""",
            allowed,
            host,
        )
        val missing = SkillBridge.tryHandle(
            """{"__bridge__":true,"action":"storage_get","params":{"key":"nope"}}""",
            allowed,
            host,
        )
        val list = SkillBridge.tryHandle(
            """{"__bridge__":true,"action":"storage_list","params":{}}""",
            allowed,
            host,
        )

        assertTrue("set 应成功: $set", set is SkillBridge.HandleResult.Output)
        assertTrue("读回应带值: $read", (read as? SkillBridge.HandleResult.Output)?.json?.contains("\"n\"") == true)
        assertTrue("未存过的键 found=false: $missing", (missing as? SkillBridge.HandleResult.Output)?.json?.contains("\"found\":false") == true)
        assertTrue("list 要回 limitBytes: $list", (list as? SkillBridge.HandleResult.Output)?.json?.contains("limitBytes") == true)

        val denied = SkillBridge.tryHandle(
            """{"__bridge__":true,"action":"storage_set","params":{"key":"x","value":"y"}}""",
            setOf("echo"),
            host,
        )
        assertTrue("未授权必须被拒", denied is SkillBridge.HandleResult.Failure)

        val bigValue = "z".repeat(300 * 1024)
        val tooBig = SkillBridge.tryHandle(
            """{"__bridge__":true,"action":"storage_set","params":{"key":"x","value":"$bigValue"}}""",
            allowed,
            host,
        )
        assertTrue("单项超上限要给出可行动提示: $tooBig", (tooBig as? SkillBridge.HandleResult.Failure)?.message?.contains("fs_write") == true)

        val badKey = SkillBridge.tryHandle(
            """{"__bridge__":true,"action":"storage_set","params":{"key":"../escape","value":1}}""",
            allowed,
            host,
        )
        assertTrue("非法键必须拒: $badKey", badKey is SkillBridge.HandleResult.Failure)
        root.deleteRecursively()
    }
}
