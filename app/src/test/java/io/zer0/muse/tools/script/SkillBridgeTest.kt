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
        assertTrue((result as SkillBridge.HandleResult.Failure).message.contains("声明的能力"))
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
