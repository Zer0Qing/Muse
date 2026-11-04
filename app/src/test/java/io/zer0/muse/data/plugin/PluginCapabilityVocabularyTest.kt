package io.zer0.muse.data.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 能力名与运行时的**词表一致性**（阶段 0 的 ④）。
 *
 * 这里要修的是一个已经造成实际后果的自相矛盾：
 *
 *  - [PluginCapabilityGateway] 自己的规范能力名是 camelCase 的
 *    （`event.onMessage`、`event.onToolCall`、`hook.toolCallBefore`、`hook.systemPrompt`），
 *    [PluginSharedStorage] 还专门说明"作者写 `storage.shared:任务清单` 再自然不过"；
 *  - 而安装门的字法只收小写、还不收冒号 ⇒ **文档教作者写的名字，安装门判非法**；
 *    市场目录校验 ([PluginCatalog] 用同一个 `isValidCapabilityName`) 又是**整份目录一起拒**，
 *    一条这样的条目就能让市场在设备上全空。
 *  - 签名工具侧另有两份手抄表：`KNOWN_RUNTIMES` 没有 `node`（三个官方示例全是 node），
 *    `ALLOWED_CAPABILITIES` 缺 `storage` / `ui.interactive`（同样是示例声明的）。
 *
 * 所以两侧都要钉：App 接受什么、签名工具就得接受什么；任何一边单方面收紧/放宽都会红。
 */
class PluginCapabilityVocabularyTest {

    /** App 自己定义并使用的全部规范能力名（网关常量 + CAPABILITY_ACTIONS 的键）。 */
    private val appCanonicalCapabilities: List<String> = listOf(
        "resource.read", "resource.write", "network", "storage", "config.write",
        "host.tool.invoke", "chat.read", "host.model.call",
        "clipboard.read", "clipboard.write", "clipboard", "notify", "device.info",
        "app.navigate", "app.messages", "app.theme", "app.settings",
        PluginCapabilityGateway.UI_INTERACTIVE,
        PluginCapabilityGateway.EVENT_ON_MESSAGE,
        PluginCapabilityGateway.EVENT_ON_TOOL_CALL,
        PluginCapabilityGateway.HOOK_TOOL_CALL_BEFORE,
        PluginCapabilityGateway.HOOK_SYSTEM_PROMPT,
        PluginCapabilityGateway.SCHEDULE,
    )

    @Test
    fun `网关自己在用的能力名必须能通过安装门`() {
        val rejected = appCanonicalCapabilities.filterNot { PluginSecurityGate.isValidCapabilityName(it) }
        assertTrue(
            "这些名字是 App 自己定义/使用的，被安装门拒掉就意味着作者无法声明它们：$rejected",
            rejected.isEmpty(),
        )
    }

    @Test
    fun `共享空间声明可带中文名字空间`() {
        assertTrue(
            "PluginSharedStorage 明确允许中文名字空间，安装门不能反过来挡它",
            PluginSecurityGate.isValidCapabilityName("storage.shared:任务清单"),
        )
        assertTrue(PluginSecurityGate.isValidCapabilityName("storage.shared:notes"))
        assertFalse("空名字空间不算声明", PluginSecurityGate.isValidCapabilityName("storage.shared:"))
        assertFalse("路径分隔符仍然要拒", PluginSecurityGate.isValidCapabilityName("storage.shared:a/b"))
        assertFalse(".. 仍然要拒", PluginSecurityGate.isValidCapabilityName("storage.shared:../x"))
    }

    @Test
    fun `字法仍然拒绝真正的垃圾`() {
        listOf("", "Uppercase", "1leading", "a b", "a/b", "a\\b", "a".repeat(65), "尾随 ").forEach { bad ->
            assertFalse("不该被接受：${"[$bad]"}", PluginSecurityGate.isValidCapabilityName(bad))
        }
    }

    @Test
    fun `签名工具接受的能力名必须也在 App 的接受集内`() {
        val signAllowed = parsePythonSet(readSignPy(), "ALLOWED_CAPABILITIES")
        assertTrue("解析不到 sign.py 的 ALLOWED_CAPABILITIES，测试会失去意义", signAllowed.isNotEmpty())
        val drifted = signAllowed.filterNot { PluginSecurityGate.isValidCapabilityName(it) }
        assertTrue("签名工具允许、App 却拒的名字（作者能签名却装不上）：$drifted", drifted.isEmpty())
    }

    @Test
    fun `App 定义的每个能力名都必须能被签名工具签出`() {
        val signAllowed = parsePythonSet(readSignPy(), "ALLOWED_CAPABILITIES")
        val missing = appCanonicalCapabilities.filterNot { signAllowed.contains(it) }
        assertTrue(
            "签名工具白名单缺这些 App 真实存在的能力，作者照文档写就会被自家工具拒：$missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `三档运行时两侧一致`() {
        val signRuntimes = parsePythonTuple(readSignPy(), "KNOWN_RUNTIMES")
        listOf("js", "node", "python").forEach { rt ->
            assertTrue(
                "App 的 PluginFunctionInvoker 会分派 $rt，签名工具却不认就签不出包",
                signRuntimes.contains(rt),
            )
        }
    }

    // ── 辅助 ────────────────────────────────────────────────────────────

    private fun readSignPy(): String {
        val candidates = listOf(
            File("../tools/market-signer/sign.py"),
            File("tools/market-signer/sign.py"),
            File("../../tools/market-signer/sign.py"),
        )
        return requireNotNull(candidates.firstOrNull { it.exists() }) {
            "找不到 sign.py（工作目录=${File(".").absolutePath}）—— 词表一致性测试失去意义"
        }.readText()
    }

    private fun parsePythonSet(src: String, name: String): Set<String> = parsePythonCollection(src, name)

    private fun parsePythonTuple(src: String, name: String): Set<String> = parsePythonCollection(src, name)

    /**
     * 抓 `NAME = { ... }` 或 `NAME = ( ... )`。
     *
     * 集合里可能混着**标识符**（`KNOWN_RUNTIMES = (RUNTIME_JS, RUNTIME_NODE, ...)`），
     * 所以先解析文件里所有 `SOMETHING = "字面量"` 的常量，再把标识符换成它的值。
     * 第一版我只取字符串字面量，于是运行时的断言"找不到 js"——那是量具错，不是产品错。
     */
    private fun parsePythonCollection(src: String, name: String): Set<String> {
        val start = src.indexOf("$name =")
        assertTrue("sign.py 里找不到 $name", start >= 0)
        val open = src.indexOfFirstOf('(', '{', from = start)
        val close = if (src[open] == '{') '}' else ')'
        val end = src.indexOf(close, open)
        val body = src.substring(open + 1, end)

        val stringConstants = Regex("^(\\w+)\\s*=\\s*\"([^\"]*)\"", RegexOption.MULTILINE)
            .findAll(src)
            .associate { it.groupValues[1] to it.groupValues[2] }

        return Regex("\"([^\"]+)\"|(\\w+)").findAll(body).mapNotNull { m ->
            m.groupValues[1].ifEmpty { stringConstants[m.groupValues[2]] }
        }.filter { it.isNotEmpty() }.toSet()
    }

    private fun String.indexOfFirstOf(vararg chars: Char, from: Int): Int {
        for (i in from until length) if (chars.contains(this[i])) return i
        error("找不到起始括号")
    }
}
