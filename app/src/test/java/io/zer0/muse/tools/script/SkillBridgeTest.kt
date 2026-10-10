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
        // v2.x: 默认用 ALL_ACTIONS 全放行；此处显式传入受限动作集，验证机制仍在（message 已改为新文案）。
        assertTrue((result as SkillBridge.HandleResult.Failure).message.contains("不在放行动作集内"))
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
}
