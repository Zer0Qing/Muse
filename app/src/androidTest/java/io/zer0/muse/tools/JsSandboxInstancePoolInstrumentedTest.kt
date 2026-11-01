package io.zer0.muse.tools

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P0-4 验收：一个插件死循环时，另一个插件必须照常跑通。
 *
 * 为什么必须在设备上（不是偏好，是硬性）：Robolectric 的 WebView 是影子实现，不真的执行 V8 ——
 * "共享单实例 + 全局锁"与"按插件实例池"在 JVM 上表现完全一样，单元测试证不了这件事。
 *
 * 拆成三个问题，因为第一轮的失败**不足以定位原因**：
 *  1. [warmInstanceNotBlockedByHangingPeer]：两个实例都热了以后，慢的还能堵住快的吗？
 *     堵住 = **渲染进程被饿**（同 App 内所有 WebView 默认共用一个 renderer 进程）。
 *  2. [coldInstanceStartWhilePeerHanging]：新建实例（建页 + 两次 JS 往返 + 自检）撞上死循环要多久？
 *     那是"冷启动被拖累"，与锁无关，量级不同，必须分开测。
 *  3. [sameScopeReusesInstance]：池子是否真在复用（第二次不该比第一次慢）。
 */
@RunWith(AndroidJUnit4::class)
class JsSandboxInstancePoolInstrumentedTest {

    private val tag = "JsPoolTest"

    @Test
    fun warmInstanceNotBlockedByHangingPeer() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        JsSandbox.init(context)

        // 预热：先把两个实例都建好，别把"建实例的成本"混进"会不会被连坐"的判断里
        warmUp(context, SLOW_SCOPE)
        warmUp(context, FAST_SCOPE)

        val hanging = async(Dispatchers.Default) {
            JsSandbox.execute("while(true){}", timeoutMs = HANG_TIMEOUT_MS, scopeKey = SLOW_SCOPE)
        }
        delay(1_200)

        val startedAt = System.currentTimeMillis()
        val fast = JsSandbox.execute("21 * 2", timeoutMs = FAST_JS_TIMEOUT_MS, scopeKey = FAST_SCOPE)
        val elapsedMs = System.currentTimeMillis() - startedAt
        val slow = hanging.await()

        val fastResult = fast.getOrNull()
        Log.i(tag, "WARM fast elapsed=${elapsedMs}ms error=${fastResult?.error} value=${fastResult?.value}")
        Log.i(tag, "WARM slow error=${slow?.getOrNull()?.error}")

        assertNull(
            "热实例被死循环同伴连坐（elapsed=${elapsedMs}ms，快插件错误=${fastResult?.error}）",
            fastResult?.error,
        )
        assertEquals("快插件结果不对", "42", fastResult?.value?.toString())
        assertTrue("慢插件应被自己的超时终止", slow?.getOrNull()?.error?.contains("超时") == true)
        assertFalse("熔断不得连坐", JsSandbox.isCircuitBrokenFor(FAST_SCOPE))
    }

    @Test
    fun coldInstanceStartWhilePeerHanging() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        JsSandbox.init(context)

        // 只预热慢的那个；这次走**新建实例**路径
        warmUp(context, SLOW_SCOPE)
        val coldScope = "cold-" + System.nanoTime()
        val hanging = async(Dispatchers.Default) {
            JsSandbox.execute("while(true){}", timeoutMs = HANG_TIMEOUT_MS, scopeKey = SLOW_SCOPE)
        }
        delay(1_000)

        val startedAt = System.currentTimeMillis()
        val cold = withTimeoutOrNull(COLD_BUDGET_MS) {
            JsSandbox.execute("6 * 7", timeoutMs = FAST_JS_TIMEOUT_MS, scopeKey = coldScope)
        }
        val elapsedMs = System.currentTimeMillis() - startedAt
        hanging.await()

        Log.i(tag, "COLD elapsed=${elapsedMs}ms value=${cold?.getOrNull()?.value} error=${cold?.getOrNull()?.error}")
        assertTrue(
            "新建实例在同伴死循环期间耗时 ${elapsedMs}ms（预算 ${COLD_BUDGET_MS}ms）。" +
                "若 WARM 很快而这里很慢，瓶颈是建实例而不是锁",
            cold != null,
        )
    }

    @Test
    fun sameScopeReusesInstance() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        JsSandbox.init(context)
        val scope = "pool-reuse-scope"

        val coldStartMs = timed(context, scope)
        val warmStartMs = timed(context, scope)
        Log.i(tag, "REUSE cold=${coldStartMs}ms warm=${warmStartMs}ms")

        assertTrue("同一插件二次调用应复用实例（cold=$coldStartMs warm=$warmStartMs）", warmStartMs <= coldStartMs)
    }

    private suspend fun warmUp(context: Context, scopeKey: String) {
        val result = JsSandbox.execute("1", timeoutMs = 8_000L, scopeKey = scopeKey)
        assertTrue("预热失败: ${result.exceptionOrNull()?.message ?: result.getOrNull()?.error}", result.isSuccess)
    }

    private suspend fun timed(context: Context, scopeKey: String): Long {
        val t0 = System.currentTimeMillis()
        val result = JsSandbox.execute("1+1", timeoutMs = 8_000L, scopeKey = scopeKey)
        val cost = System.currentTimeMillis() - t0
        assertTrue("执行失败: ${result.exceptionOrNull()?.message ?: result.getOrNull()?.error}", result.isSuccess)
        return cost
    }

    private companion object {
        const val SLOW_SCOPE = "slow-plugin"
        const val FAST_SCOPE = "fast-plugin"
        const val HANG_TIMEOUT_MS = 8_000L
        const val FAST_JS_TIMEOUT_MS = 3_000L
        const val COLD_BUDGET_MS = 12_000L
    }
}
