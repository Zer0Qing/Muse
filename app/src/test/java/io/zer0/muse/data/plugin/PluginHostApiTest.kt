package io.zer0.muse.data.plugin

import io.zer0.common.AppJson
import io.zer0.muse.tools.ToolCallGate
import io.zer0.muse.tools.script.SkillBridge
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `config_set`（插件写回自己的配置）的契约测试。
 *
 * 要守住的不是"能不能写"，而是**边界在哪**：
 *  1. 只能写清单里声明过的键（否则等于给自己开任意键）；
 *  2. 值必须符合声明类型，下拉值必须落在候选里；
 *  3. 敏感项（凭据）插件**永远不能自己改** —— 允许写入就意味着插件可以给自己换钥匙；
 *  4. 没授予 `config.write` 时必须在写之前就拒。
 */
class PluginHostApiTest {

    private val manifest = PluginManifest(
        id = "demo",
        name = "示例",
        capabilities = listOf("config.write"),
        contributes = PluginContributes(
            configuration = listOf(
                ConfigItem(key = "city", description = "城市"),
                ConfigItem(key = "verbose", type = ConfigItemType.boolean),
                ConfigItem(key = "limit", type = ConfigItemType.number),
                ConfigItem(
                    key = "mode",
                    type = ConfigItemType.select,
                    options = listOf(SelectOption("fast", "快速"), SelectOption("deep", "深度")),
                ),
                ConfigItem(key = "api_key", secret = true),
            ),
        ),
    )

    /** 只记录写了什么，方便断言"被拒时绝不能落盘"。 */
    private fun api(sink: MutableList<Pair<String, JsonElement?>>) =
        PluginHostApi(writeConfig = { id, _, value -> sink += id to value })

    private fun request(action: String, params: String): String =
        """{"__bridge__":true,"action":"$action","params":$params}"""

    private fun handle(api: PluginHostApi, valueJson: String, allowed: Set<String> = setOf("config_set")) =
        runBlocking { api.handle("demo", manifest, valueJson, allowed) }

    @Test
    fun `declared key with matching type is written`() {
        val sink = mutableListOf<Pair<String, JsonElement?>>()

        val result = handle(api(sink), request("config_set", """{"key":"city","value":"杭州"}"""))

        assertTrue("应成功: $result", result is SkillBridge.HandleResult.Output)
        assertEquals(1, sink.size)
        assertEquals("demo", sink[0].first)
        assertEquals("\"杭州\"", sink[0].second.toString())
    }

    @Test
    fun `undeclared key is rejected and the allowed keys are listed`() {
        val sink = mutableListOf<Pair<String, JsonElement?>>()

        val result = handle(api(sink), request("config_set", """{"key":"shell","value":"rm -rf"}"""))

        val message = (result as SkillBridge.HandleResult.Failure).message
        assertTrue("要说明只能写声明过的键: $message", message.contains("声明"))
        assertTrue("要列出可写的键: $message", message.contains("api_key"))
        assertTrue("绝不能真的落盘", sink.isEmpty())
    }

    @Test
    fun `secret item can never be written by the plugin`() {
        val sink = mutableListOf<Pair<String, JsonElement?>>()

        val result = handle(api(sink), request("config_set", """{"key":"api_key","value":"sk-new"}"""))

        assertTrue(
            "敏感项必须拒绝: ${result?.let { (it as? SkillBridge.HandleResult.Failure)?.message } }",
            (result as? SkillBridge.HandleResult.Failure)?.message?.contains("敏感") == true,
        )
        assertTrue("不得落盘", sink.isEmpty())
    }

    @Test
    fun `value type must match the declared type`() {
        val sink = mutableListOf<Pair<String, JsonElement?>>()
        val api = api(sink)

        val wrongString = handle(api, request("config_set", """{"key":"city","value":123}"""))
        val wrongBool = handle(api, request("config_set", """{"key":"verbose","value":"yes"}"""))
        val wrongNumber = handle(api, request("config_set", """{"key":"limit","value":"abc"}"""))
        val okBool = handle(api, request("config_set", """{"key":"verbose","value":true}"""))

        assertTrue("字符串项拒绝数字: $wrongString", wrongString is SkillBridge.HandleResult.Failure)
        assertTrue("布尔项拒绝 'yes': $wrongBool", wrongBool is SkillBridge.HandleResult.Failure)
        assertTrue("数字项拒绝 'abc': $wrongNumber", wrongNumber is SkillBridge.HandleResult.Failure)
        assertTrue("布尔项接受 true: $okBool", okBool is SkillBridge.HandleResult.Output)
    }

    @Test
    fun `select values must be one of the declared options`() {
        val sink = mutableListOf<Pair<String, JsonElement?>>()
        val api = api(sink)

        val outside = handle(api, request("config_set", """{"key":"mode","value":"turbo"}"""))
        val inside = handle(api, request("config_set", """{"key":"mode","value":"deep"}"""))

        val message = (outside as SkillBridge.HandleResult.Failure).message
        assertTrue("要列出候选值: $message", message.contains("fast") && message.contains("deep"))
        assertTrue("合法候选应通过: $inside", inside is SkillBridge.HandleResult.Output)
    }

    @Test
    fun `unauthorized action is refused before touching storage`() {
        val sink = mutableListOf<Pair<String, JsonElement?>>()

        val result = handle(api(sink), request("config_set", """{"key":"city","value":"苏州"}"""), allowed = emptySet())

        val message = (result as SkillBridge.HandleResult.Failure).message
        assertTrue("要指出缺哪个能力: $message", message.contains("config.write"))
        assertTrue("要给授予路径: $message", message.contains("权限"))
        assertTrue("不得落盘", sink.isEmpty())
    }

    @Test
    fun `other actions fall through to the device bridge untouched`() {
        val sink = mutableListOf<Pair<String, JsonElement?>>()

        assertNull(handle(api(sink), request("fs_read", """{"path":"a.txt"}""")))
        assertNull(handle(api(sink), """{"notBridge":true}"""))
        assertNull(handle(api(sink), "不是 JSON"))
    }

    @Test
    fun `missing key or value gives its own reason`() {
        val sink = mutableListOf<Pair<String, JsonElement?>>()
        val api = api(sink)

        val noKey = handle(api, request("config_set", """{"value":"x"}"""))
        val noValue = handle(api, request("config_set", """{"key":"city"}"""))

        assertTrue("缺 key: $noKey", (noKey as SkillBridge.HandleResult.Failure).message.contains("key"))
        assertTrue("缺 value: $noValue", (noValue as SkillBridge.HandleResult.Failure).message.contains("value"))
    }

    @Test
    fun `secret flag stays out of serialized manifest for signature compatibility`() {
        // 存量已签名市场包不含 secret 字段：若默认值被编码，验签会整体失败。
        val json = AppJson.encodeToString(PluginManifest.serializer(), manifest)

        assertTrue("默认 false 不得出现在序列化结果里", !json.contains("\"secret\":false"))
        assertTrue(
            "敏感项本身仍要能被表达",
            AppJson.encodeToString(ConfigItem.serializer(), ConfigItem(key = "k", secret = true)).contains("\"secret\":true"),
        )
    }

    // ── tool_invoke：必须复用主会话审批链，且深度限 1 ─────────────────────

    /** 记录"审批被问了几次 / 执行跑了几次"，用来断言顺序与"被拒时绝不执行"。 */
    private class Counters {
        var gated = 0
        var executed = 0
    }

    private fun bridgeFor(
        counters: Counters,
        outcome: ToolCallGate.Outcome = ToolCallGate.Outcome(allowed = true),
        result: String = """{"ok":true}""",
    ) = PluginToolInvokeBridge(
        gateCall = { _, _, _ -> counters.gated++; outcome },
        executeTool = { _, _ -> counters.executed++; result },
    )

    private fun toolApi(bridge: PluginToolInvokeBridge?) =
        PluginHostApi(writeConfig = { _, _, _ -> }, toolBridge = bridge)

    private fun invokeRequest(params: String) =
        """{"__bridge__":true,"action":"tool_invoke","params":$params}"""

    @Test
    fun `tool_invoke without the capability never reaches the executor`() {
        val counters = Counters()
        val api = toolApi(bridgeFor(counters))

        val result = runBlocking { api.handle("demo", manifest, invokeRequest("""{"name":"read_file"}"""), emptySet()) }

        assertTrue("缺能力要指名缺哪个: $result", (result as SkillBridge.HandleResult.Failure).message.contains("host.tool.invoke"))
        assertEquals("缺能力时连审批都不该弹", 0, counters.gated)
        assertEquals("更不该执行", 0, counters.executed)
    }

    @Test
    fun `tool_invoke runs only after the shared gate approves it`() {
        val counters = Counters()
        val api = toolApi(bridgeFor(counters))

        val result = runBlocking {
            api.handle(
                "demo", manifest,
                invokeRequest("""{"name":"read_file","arguments":{"path":"a"}}"""),
                setOf("tool_invoke"),
            )
        }

        assertTrue("应执行: $result", result is SkillBridge.HandleResult.Output)
        assertEquals("审批问一次", 1, counters.gated)
        assertEquals("批准后执行一次", 1, counters.executed)
    }

    @Test
    fun `gate refusal is surfaced verbatim and the tool is not executed`() {
        var executed = false
        val api = PluginHostApi(
            writeConfig = { _, _, _ -> },
            toolBridge = PluginToolInvokeBridge(
                gateCall = { _, _, _ -> ToolCallGate.Outcome(allowed = false, reason = "工具 'x' 未获批准(用户拒绝)") },
                executeTool = { _, _ -> executed = true; "{}" },
            ),
        )

        val result = runBlocking {
            api.handle("demo", manifest, """{"__bridge__":true,"action":"tool_invoke","params":{"name":"x"}}""", setOf("tool_invoke"))
        }

        assertTrue("拒绝原因要原样给回模型: $result", (result as SkillBridge.HandleResult.Failure).message.contains("未获批准"))
        assertTrue("绝不能执行", !executed)
    }

    @Test
    fun `nested tool_invoke is refused by the depth marker`() {
        val api = toolApi(bridgeFor(Counters()))

        val result = runBlocking {
            withContext(PluginToolCallMarker("other-plugin")) {
                api.handle(
                    "demo", manifest,
                    """{"__bridge__":true,"action":"tool_invoke","params":{"name":"read_file"}}""",
                    setOf("tool_invoke"),
                )
            }
        }

        assertTrue("深度闸必须拦住: $result", (result as SkillBridge.HandleResult.Failure).message.contains("深度上限"))
    }

    @Test
    fun `missing bridge fails closed instead of pretending the action does not exist`() {
        val api = toolApi(null)

        val result = runBlocking {
            api.handle("demo", manifest, """{"__bridge__":true,"action":"tool_invoke","params":{"name":"x"}}""", setOf("tool_invoke"))
        }

        assertTrue("未接入要拒绝而不是放行: $result", (result as SkillBridge.HandleResult.Failure).message.contains("未接入"))
    }
}
