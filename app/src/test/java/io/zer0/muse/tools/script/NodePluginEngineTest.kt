package io.zer0.muse.tools.script

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Node 插件运行时（P0-6）的协议与包装脚本测试。
 *
 * 这里**不测真实执行**（真机上跑 Node 才有意义，见 androidTest）；
 * 测的是两条最容易悄悄坏掉的东西：结果协议解析、以及包装脚本给插件的边界。
 */
@RunWith(RobolectricTestRunner::class)
class NodePluginEngineTest {

    private fun engine(): NodePluginEngine = NodePluginEngine(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `success protocol keeps valueJson as raw json text`() {
        val result = engine().interpret(
            stdout = """{"ok":true,"valueJson":"42","logs":["[log] hi"]}""",
            stderr = "",
            exitCode = 0,
            timedOut = false,
        )

        val success = result as SkillEngineResult.Success
        // 数字不能被包成字符串，否则插件返回的 JSON 结构会整体变形
        assertEquals("42", success.valueJson)
        assertEquals(listOf("[log] hi"), success.consoleLogs)
    }

    @Test
    fun `bridge sentinel survives the protocol untouched`() {
        val json = """{"ok":true,"valueJson":"{\"__bridge__\":true,\"action\":\"http_get\",\"params\":{}}","logs":[]}"""

        val success = engine().interpret(json, "", 0, false) as SkillEngineResult.Success

        assertTrue("桥接哨兵必须原样交给调用方判定: ${success.valueJson}", success.valueJson.contains("__bridge__"))
    }

    @Test
    fun `timeout reports that the process was killed`() {
        val result = engine().interpret(stdout = "", stderr = "node: out of memory", exitCode = 137, timedOut = true)

        val error = result as SkillEngineResult.Error
        assertTrue("应说明子进程已终止: ${error.message}", error.message.contains("超时"))
        assertTrue("应带上 stderr 片段: ${error.message}", error.message.contains("out of memory"))
    }

    @Test
    fun `missing payload is an error not a silent null`() {
        val result = engine().interpret(stdout = "some noise\n", stderr = "", exitCode = 1, timedOut = false)

        assertTrue("没有结果协议时必须报错", result is SkillEngineResult.Error)
    }

    @Test
    fun `ok false carries the plugin error message`() {
        val result = engine().interpret(
            stdout = """{"ok":false,"error":"入口里没有该函数: foo","logs":[]}""",
            stderr = "",
            exitCode = 0,
            timedOut = false,
        )

        assertEquals("入口里没有该函数: foo", (result as SkillEngineResult.Error).message)
    }

    @Test
    fun `wrapper injects timers and a ledger-backed fetch shim`() {
        val wrapper = engine().buildWrapper("function f(){return 1}", "f", "[]", "p9", null, 4_000L)

        assertTrue("要注入 setTimeout", wrapper.contains("setTimeout: function"))
        assertTrue("要注入 fetch", wrapper.contains("fetch: __fetch"))
        // fetch 不能是原生 fetch：必须走同一条桥接 RPC，否则换 runtime 就等于绕过 network 授权
        assertTrue("fetch 必须经桥接而不是原生网络", wrapper.contains("__bridgeRaw(action, JSON.stringify(params))"))
        assertTrue("桥接动作按方法选 http_get / http_post", wrapper.contains("'http_get' : 'http_post'"))
        assertTrue("定时器延迟要夹进本次预算", wrapper.contains("__capDelay(ms)"))
    }

    @Test
    fun `wrapper runs user code in a vm context without network globals`() {
        val wrapper = engine().buildWrapper(
            entryCode = "function foo(){return 1}",
            functionName = "foo",
            argsJson = "[]",
            scopeKey = "p1",
            pluginConfigJson = """{"k":"v"}""",
            timeoutMs = 5_000L,
        )

        assertTrue("必须用 vm 上下文", wrapper.contains("vm.createContext"))
        assertTrue("同步死循环要能被 vm timeout 打断", wrapper.contains("timeout: 5000"))
        assertTrue("能力只能经桥接出口", wrapper.contains("muse: {"))
        // 真正的不变量不是"没有 fetch 这个名字"，而是**没有原生网络出口**：
        // 沙盒不 require net/http/undici，也没有 XMLHttpRequest；fetch 只允许是指向桥接的垫片
        // （由 wrapper injects timers... 那条测试正面锁定它必须经 __bridgeRaw）。
        assertFalse("不该 require 原生网络模块", wrapper.contains("require('net')"))
        assertFalse("不该 require http 模块", wrapper.contains("require('http')"))
        assertFalse("沙盒里不该有 XMLHttpRequest", wrapper.contains("XMLHttpRequest:"))
        assertTrue("插件 id 要能拿到", wrapper.contains("\"p1\""))
    }

    @Test
    fun `bridge request line parses into action and params`() {
        val request = engine().parseBridgeRequest(
            """{"kind":"bridge","id":3,"action":"fs_write","params":{"path":"a.txt"}}""",
        )

        assertEquals(3, request?.id)
        assertEquals("\"fs_write\"", request?.actionJson)
        assertTrue("参数要原样带走: ${request?.paramsJson}", request!!.paramsJson.contains("a.txt"))
    }

    @Test
    fun `result line is not mistaken for a bridge request`() {
        val request = engine().parseBridgeRequest("""{"ok":true,"valueJson":"42","logs":[]}""")

        assertNull("结果行不能当请求处理", request)
    }

    @Test
    fun `malformed line is ignored rather than thrown`() {
        assertNull(engine().parseBridgeRequest("not json at all"))
        assertNull(engine().parseBridgeRequest("{"))
        assertNull(engine().parseBridgeRequest("""{"kind":"bridge"}"""))
    }

    @Test
    fun `wrapper speaks the line protocol`() {
        val wrapper = engine().buildWrapper("function f(){return 1}", "f", "[]", "p9", null, 4_000L)

        // 多轮桥接的三要件：发 request、同步读 reply、按序号配对
        assertTrue("必须发 kind=bridge", wrapper.contains("kind: 'bridge'"))
        assertTrue("必须从 fd 0 同步读回包", wrapper.contains("__fs.readSync(0,"))
        assertTrue("必须校验序号，防宿主串台", wrapper.contains("reply.id !== id"))
        assertTrue("被拒必须抛异常而不是返回错误对象", wrapper.contains("throw new Error(reply.error"))
        assertTrue("残留字节要留到下一次读", wrapper.contains("__inBuf.slice(nl + 1)"))
    }

    @Test
    fun `empty args become an empty array`() {
        val wrapper = engine().buildWrapper("function f(){}", "f", "", "p1", null, 3_000L)

        assertTrue(wrapper.contains("f.apply(undefined, [])"))
    }

    @Test
    fun `concurrency guard fails fast instead of queueing`() = runBlocking {
        val engine = engine()
        // 额度是进程级共享的，用完必须还得干干净净，否则后面的测试会莫名少一格
        val held = mutableListOf<NodePluginEngine>()
        try {
            repeat(NodePluginEngine.MAX_CONCURRENT_PROCESSES) {
                check(engine.tryEnterSlot())
                held += engine
            }

            assertFalse(
                "满了要直接失败（排队会让调用方以为还在跑，超时更难归因）",
                engine.tryEnterSlot(),
            )
            held.removeAt(0).exitSlot()
            assertTrue("让出一个槽位后应能立刻进", engine.tryEnterSlot())
        } finally {
            repeat(NodePluginEngine.MAX_CONCURRENT_PROCESSES) { engine.exitSlot() }
            held.forEach { runCatching { it.exitSlot() } }
        }
    }

    @Test
    fun `runtime-not-ready path must not leak a concurrency slot`() = runBlocking {
        // 回归：`isReady` 检查曾经写在 try 外面，早退绕过 finally，
        // 于是运行时没装好的设备上两次调用就把插件并发额度永久吃光。
        val engine = engine()

        repeat(NodePluginEngine.MAX_CONCURRENT_PROCESSES) {
            val result = engine.callPluginFunction("function f(){return 1}", "f", "[]", scopeKey = "leak-check")
            assertTrue("JVM 环境本就没有 Node，应给可读的未就绪错误: $result", result is SkillEngineResult.Error)
        }

        assertTrue("未就绪不该占用槽位", engine.tryEnterSlot())
        engine.exitSlot()
    }
}
