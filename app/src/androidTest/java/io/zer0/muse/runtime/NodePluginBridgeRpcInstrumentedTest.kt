package io.zer0.muse.runtime

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.zer0.muse.tools.script.NodePluginEngine
import io.zer0.muse.tools.script.SkillBridge
import io.zer0.muse.tools.script.SkillEngineResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * P0-7-1 验收：**一次工具调用里能做多次外部动作**。
 *
 * 旧语义（WebView 时代留下的）是"插件 `return` 一个 `__bridge__` 哨兵，宿主执行后当工具结果"，
 * 于是一次调用只能做一次动作 —— 这是"插件什么都能做"的真实天花板，不是安全问题。
 * 现在走 stdin/stdout 行协议，插件可以连着调；被拒的那一次必须以异常形式回到插件里，
 * 否则它会拿着一个 error 对象继续算，症状是"结果莫名奇怪"而不是"权限不够"。
 *
 * 必须在真机跑：协议的另一端是真实 Node 子进程 + 真 pipe，JVM 侧模拟不出 `fs.readSync(0, ...)`
 * 的阻塞语义。
 */
@RunWith(AndroidJUnit4::class)
class NodePluginBridgeRpcInstrumentedTest {

    @Test
    fun pluginCanBridgeSeveralTimesInOneCall() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        MuseRuntime.ensureData(context).getOrThrow()
        val engine = NodePluginEngine(context)
        val calls = AtomicInteger(0)
        val seenActions = mutableListOf<String>()

        val result = engine.callPluginFunction(
            entryCode = """
                function go() {
                  const a = muse.bridge('fs_write', { path: 'a.txt', content: 'first' });
                  const b = muse.bridge('fs_read', { path: 'a.txt' });
                  return { wrote: a.ok, readBack: b.content, count: 2 };
                }
            """.trimIndent(),
            functionName = "go",
            argsJson = "[]",
            timeoutMs = 15_000L,
            scopeKey = "rpc-plugin",
        ) { requestJson ->
            calls.incrementAndGet()
            val action = Regex("\"action\":\"([a-z_]+)\"").find(requestJson)?.groupValues?.get(1) ?: "?"
            seenActions += action
            when (action) {
                "fs_write" -> SkillBridge.HandleResult.Output("""{"ok":true,"path":"a.txt"}""")
                "fs_read" -> SkillBridge.HandleResult.Output("""{"content":"first"}""")
                else -> SkillBridge.HandleResult.Failure("未授权 $action 能力")
            }
        }

        Log.i(TAG, "RPC calls=${calls.get()} actions=$seenActions result=$result")
        val success = result as? SkillEngineResult.Success
        assertTrue("多轮桥接应成功，实际: $result", success != null)
        assertEquals("两次桥接都应送到宿主", 2, calls.get())
        assertEquals(listOf("fs_write", "fs_read"), seenActions)
        // 插件把第二次桥接拿到的内容原样带回来，证明它真的"拿到结果继续算"了
        assertTrue("结果应含第二次桥接的读回值: ${success?.valueJson}", success?.valueJson?.contains("first") == true)
        assertTrue("结果应含第一次桥接的 ok: ${success?.valueJson}", success?.valueJson?.contains("wrote") == true)
    }

    @Test
    fun deniedBridgeSurfacesAsAnExceptionNotAQuietErrorObject() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        MuseRuntime.ensureData(context).getOrThrow()
        val engine = NodePluginEngine(context)

        val result = engine.callPluginFunction(
            entryCode = """
                function go() {
                  muse.bridge('clipboard_read', {});
                  return '不该走到这里';
                }
            """.trimIndent(),
            functionName = "go",
            argsJson = "[]",
            timeoutMs = 15_000L,
            scopeKey = "rpc-denied",
        ) { SkillBridge.HandleResult.Failure("未授权 clipboard.read 能力；可在 设置 → 插件 → 权限 中授予") }

        val error = result as? SkillEngineResult.Error
        assertTrue("被拒的桥接必须让插件失败而不是静默返回，实际: $result", error != null)
        assertTrue("错误里要带授予指引: ${error?.message}", error?.message?.contains("设置") == true)
    }

@Test
    fun fetchWithoutNetworkCapabilityIsBlockedNotSilent() = runBlocking {
        // 探针实测：内置 Node **本身有全局 fetch**（`fetchType":"function"`）。
        // 所以沙盒里的 fetch 必须是我们注入的、走桥接的垫片 —— 否则插件一句 fetch 就绕开了 network 授权，
        // 前面所有的能力账本都白做。这条测试正面锁住这一点。
        val context = ApplicationProvider.getApplicationContext<Context>()
        MuseRuntime.ensureData(context).getOrThrow()
        val engine = NodePluginEngine(context)
        val asked = mutableListOf<String>()

        val result = engine.callPluginFunction(
            entryCode = """
                async function go() {
                  const res = await fetch('https://example.com/api');
                  return '拿到正文: ' + (await res.text());
                }
            """.trimIndent(),
            functionName = "go",
            argsJson = "[]",
            timeoutMs = 15_000L,
            scopeKey = "fetch-gated",
        ) { requestJson ->
            asked += Regex("\"action\":\"([a-z_]+)\"").find(requestJson)?.groupValues?.get(1) ?: "?"
            SkillBridge.HandleResult.Failure("能力 network 未授权；可在 设置 → 工具与扩展 → 插件 → 权限 中授予")
        }

        val error = result as? SkillEngineResult.Error
        assertTrue("fetch 未授权必须失败，实际: $result", error != null)
        assertTrue("宿主确实收到了 http_get 请求（说明 fetch 走的是桥接），实到: $asked", asked.contains("http_get"))
        assertTrue("错误要带授予路径: ${error?.message}", error?.message?.contains("设置") == true)
    }

    private companion object {
        const val TAG = "NodeRpcTest"
    }
}
