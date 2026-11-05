package io.zer0.muse.data.plugin

import io.zer0.common.AppJson
import io.zer0.muse.web.HostEvent
import io.zer0.muse.web.HostMemoryEntry
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 三端共享夹具的 **App 侧消费者**（DSHGO 阶段 3 的收尾）。
 *
 * 夹具 [docs/plugin/parity/protocol-vocabulary.json] 不是文档，而是**可执行的对账表**：
 * 这一条测 App 真值是否等于表里的值，`sign.py` 启动时自己核对同一张表，
 * 客户端那侧由本仓既有的线契约测试读 TS 源码钉住。过去几轮修的漂移
 * （能力字法、`items/bases/system` 键名、`createdAt` 类型、commands 上限、签名工具不认 node）
 * 全部是同一种病的不同部位：**规则被手抄了第二份，然后各自演化**。
 */
class PluginParityVocabularyTest {

    private val fixture: JsonObject by lazy {
        val candidates = listOf(
            File("../docs/plugin/parity/protocol-vocabulary.json"),
            File("docs/plugin/parity/protocol-vocabulary.json"),
            File("../../docs/plugin/parity/protocol-vocabulary.json"),
        )
        val file = requireNotNull(candidates.firstOrNull { it.isFile }) {
            "找不到共享夹具（工作目录=${File(".").absolutePath}）—— 没有它这条对账就失去意义"
        }
        AppJson.parseToJsonElement(file.readText()).jsonObject
    }

    private fun SerialDescriptor.wireNames(): Set<String> = (0 until elementsCount).map { getElementName(it) }.toSet()

    private fun obj(vararg path: String): JsonObject =
        path.fold(fixture as Any) { acc, key -> (acc as JsonObject).getValue(key) } as JsonObject

    private fun strs(container: JsonObject, key: String): List<String> =
        (container.getValue(key) as JsonArray).map { (it as JsonPrimitive).content }

    private fun nums(container: JsonObject, key: String): Map<String, Int> =
        (container.getValue(key) as JsonObject).mapValues { (_, v) -> (v as JsonPrimitive).content.toInt() }

    @Test
    fun `夹具里每个能力名都必须过 App 的安装门`() {
        val rejected = strs(obj("plugins"), "capabilities").filterNot { PluginSecurityGate.isValidCapabilityName(it) }
        assertTrue("这些名字 App 自己不收，却写进了共享夹具：$rejected", rejected.isEmpty())
    }

    @Test
    fun `App 定义的能力必须全部收录在夹具里`() {
        val declared = strs(obj("plugins"), "capabilities").toSet()
        val fromActions = PluginCapabilityGateway.CAPABILITY_ACTIONS.keys
        val fromConsts = setOf(
            PluginCapabilityGateway.UI_INTERACTIVE,
            PluginCapabilityGateway.UI_SLOT,
            PluginCapabilityGateway.NETWORK,
            PluginCapabilityGateway.NETWORK_LOCAL,
            PluginCapabilityGateway.APP_NAVIGATE,
            PluginCapabilityGateway.APP_MESSAGES,
            PluginCapabilityGateway.APP_THEME,
            PluginCapabilityGateway.APP_SETTINGS,
            PluginCapabilityGateway.RUNTIME_LONG,
            PluginCapabilityGateway.RUNTIME_SERVICE,
            PluginCapabilityGateway.RUNTIME_PYTHON,
            PluginCapabilityGateway.SCHEDULE,
            PluginCapabilityGateway.SCHEDULE_BACKGROUND,
            PluginCapabilityGateway.EVENT_ON_MESSAGE,
            PluginCapabilityGateway.EVENT_ON_TOOL_CALL,
            PluginCapabilityGateway.HOOK_TOOL_CALL_BEFORE,
            PluginCapabilityGateway.HOOK_SYSTEM_PROMPT,
            PluginSecurityGate.UI_SKIN_CAPABILITY,
        )
        val missing = (fromActions + fromConsts).filterNot { declared.contains(it) }
        assertTrue("App 真实存在的能力没进夹具，签名工具与客户端就会漏：$missing", missing.isEmpty())
    }

    @Test
    fun `夹具声明的签名工具遗留名确实不是 App 能力`() {
        // 这一条防的是反向漂移：把 App 有的东西标成"遗留"，等于给删除它开绿灯。
        val extras = strs(obj("plugins"), "signerOnlyNamesNotDefinedByApp")
        val appNames = PluginCapabilityGateway.CAPABILITY_ACTIONS.keys +
            strs(obj("plugins"), "capabilities")
        val wrong = extras.filter { it in PluginCapabilityGateway.CAPABILITY_ACTIONS.keys }
        assertTrue("这些其实是 App 的能力键，却被标成签名工具遗留：$wrong", wrong.isEmpty())
        assertTrue("遗留名不得出现在能力表里", extras.none { appNames.contains(it) })
    }

    @Test
    fun `同义归一表一致`() {
        val declared = obj("plugins", "capabilityAliases").mapValues { (_, v) -> (v as JsonPrimitive).content }
        assertEquals(
            "夹具与 App 的 CAPABILITY_ALIASES 必须一模一样（这里刻意不复制第二份映射）",
            PluginCapabilityGateway.CAPABILITY_ALIASES.entries.map { "${it.key}->${it.value}" }.sorted(),
            declared.entries.map { "${it.key}->${it.value}" }.sorted(),
        )
    }

    @Test
    fun `限制数字与 App 常量逐一对齐`() {
        val limits = nums(obj("plugins"), "limits")
        assertEquals("命令条数上限", PluginCommandRegistry.MAX_COMMANDS_PER_PLUGIN, limits.getValue("maxCommandsPerPlugin"))
        assertEquals(
            "命令说明上限",
            PluginCommandRegistry.MAX_DESCRIPTION_CHARS,
            limits.getValue("maxCommandDescriptionChars"),
        )
        assertEquals("定时任务间隔下限", PluginScheduleDue.MIN_INTERVAL_MINUTES, limits.getValue("minScheduleIntervalMinutes"))
        assertEquals("工具数上限", PluginAuthoringRules.MAX_TOOLS, limits.getValue("maxTools"))
        assertEquals("代码体积上限", PluginAuthoringRules.MAX_CODE_BYTES, limits.getValue("maxCodeBytes"))
    }

    @Test
    fun `命令名字法与 App 同一个正则`() {
        val grammar = obj("plugins", "regexes").getValue("commandName")
        assertEquals(
            "夹具抄的必须是 App 那条正则本身，而不是「看起来一样」的另一条",
            PluginCommandRegistry.NAME_REGEX.pattern,
            (grammar as JsonPrimitive).content,
        )
    }

    @Test
    fun `host WS 的线上键改名与序列化器一致`() {
        val renames = obj("hostWs", "wireKeyRenames").mapValues { (_, v) -> (v as JsonPrimitive).content }
        val eventNames = HostEvent.serializer().descriptor.wireNames()
        renames.forEach { (kotlinName, wireName) ->
            assertTrue(
                "客户端读的线上键 $wireName 必须由序列化器吐出（事件名集合=$eventNames）",
                eventNames.contains(wireName),
            )
            assertFalse("Kotlin 属性名 $kotlinName 不得再泄漏到线上", eventNames.contains(kotlinName))
        }
    }

    @Test
    fun `记忆条目的 createdAt 在线上是数字`() {
        assertEquals(
            "number",
            (obj("hostWs", "memoryEntry").getValue("createdAtWireType") as JsonPrimitive).content,
        )
        val json = AppJson.encodeToString(
            HostEvent.serializer(),
            HostEvent(type = "memory.list", memory = listOf(HostMemoryEntry(id = "1", content = "x", createdAt = 1_700_000_000_000L))),
        )
        assertTrue("实际线上形状：$json", json.contains("\"createdAt\":1700000000000"))
    }

    @Test
    fun `官方示例目录真实存在`() {
        val samples = strs(fixture, "samples")
        assertTrue("夹具里没写示例", samples.isNotEmpty())
        samples.forEach { name ->
            val manifest = File("../docs/plugin/samples/$name/manifest.json")
            assertTrue("示例 $name 的清单不存在：${manifest.absolutePath}", manifest.isFile)
        }
    }

    @Test
    fun `运行时三档一致`() {
        assertEquals(
            "App 分派的运行时必须与夹具相同（签名工具那边由 sign.py 自己核对同一张表）",
            setOf("js", "node", "python").sorted(),
            strs(obj("plugins"), "runtimes").sorted(),
        )
    }
}
