package io.zer0.muse.runtime

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.zer0.muse.tools.script.NodePluginEngine
import io.zer0.muse.tools.script.SkillEngineResult
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P0-6 的核心验收：**跑飞的插件不得影响别的插件**（真机，JVM 证不了）。
 *
 * 对照组是 `JsSandboxInstancePoolInstrumentedTest`：同一台模拟器上，WebView 档的死循环
 * 让新建实例耗时 12,001ms 仍未返回、预热用例直接卡死（见 PERF_LEDGER.md §12.8）。
 * Node 档的两个调用是**两个独立进程**，理应互不影响。
 */
@RunWith(AndroidJUnit4::class)
class NodePluginEngineIsolationInstrumentedTest {

    @Test
    fun runawayLoopKilledWhilePeerStillSucceeds() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        MuseRuntime.ensureData(context).getOrThrow()
        val engine = NodePluginEngine(context)

        val startedAt = System.currentTimeMillis()
        val runaway = async {
            engine.callPluginFunction(
                entryCode = "function go(){ while(true){} return 1; }",
                functionName = "go",
                argsJson = "[]",
                timeoutMs = RUNAWAY_TIMEOUT_MS,
                scopeKey = "runaway",
            )
        }
        // 同伴同时在自己的进程里算一道乘法
        val peer = engine.callPluginFunction(
            entryCode = "function go(){ return 21 * 2; }",
            functionName = "go",
            argsJson = "[]",
            timeoutMs = PEER_TIMEOUT_MS,
            scopeKey = "peer",
        )
        val peerMs = System.currentTimeMillis() - startedAt
        val slow = runaway.await()
        val totalMs = System.currentTimeMillis() - startedAt

        Log.i(
            TAG,
            "NODE peer=${peerMs}ms value=${(peer as? SkillEngineResult.Success)?.valueJson} | " +
                "runaway=${(slow as? SkillEngineResult.Error)?.message} total=${totalMs}ms",
        )

        assertEquals("同伴插件必须算对", "42", (peer as? SkillEngineResult.Success)?.valueJson)
        assertTrue("同伴插件被跑飞插件拖慢：peer=${peerMs}ms", peerMs < RUNAWAY_TIMEOUT_MS)
        assertTrue("跑飞插件必须失败而不是返回假成功", slow is SkillEngineResult.Error)
    }

    private companion object {
        const val TAG = "NodeEngineTest"
        const val RUNAWAY_TIMEOUT_MS = 6_000L
        const val PEER_TIMEOUT_MS = 5_000L
    }
}
