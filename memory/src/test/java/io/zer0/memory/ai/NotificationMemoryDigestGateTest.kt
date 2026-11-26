package io.zer0.memory.ai

import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.Model
import io.zer0.common.ProcessWriteGate
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.runtime.DefaultMemoryWriteGate
import io.zer0.memory.runtime.MemoryScopeKey
import io.zer0.memory.runtime.MemoryWriteGate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 通知梳理的写闸行为。
 *
 * 锁死三件事:
 *  1. 恢复窗口内不调模型;
 *  2. 被闸拦下的批次不记「已梳理」指纹(否则这批通知永远丢失);
 *  3. 模型调用期间恢复窗口完整开合过,返回的结果也不落库（栅栏失效）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationMemoryDigestGateTest {

    @Volatile
    private var llmCalls: Int = 0

    private val recordingLlm = object : MemoryLlmClient {
        override suspend fun callText(
            systemPrompt: String,
            userContent: String,
            model: Model?,
            temperature: Float,
            maxTokens: Int,
            timeoutMs: Long,
        ): String {
            llmCalls++
            return """{"facts": []}"""
        }
    }

    private val blockingGate = object : MemoryWriteGate {
        override fun isBlocked(scope: MemoryScopeKey): Boolean = true
        override suspend fun <T> withWritesPaused(scope: MemoryScopeKey, block: suspend () -> T): T = block()
    }

    private fun digest(gate: MemoryWriteGate?): NotificationMemoryDigest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return NotificationMemoryDigest(
            factDbProvider = FactDbProvider(context),
            llmClient = recordingLlm,
            writeGate = gate,
        )
    }

    private fun notifications() = listOf(
        NotificationMemoryDigest.NotificationInput(
            packageName = "com.example.shop",
            appLabel = "购物",
            title = "快递已到驿站",
            text = "请凭取件码 1234 领取",
            timestamp = 1_700_000_000_000L,
        ),
    )

    @After
    fun resetGate() {
        ProcessWriteGate.end()
        DefaultMemoryWriteGate.shared.close()
    }

    @Test
    fun `blocked gate skips the model call`() = runTest {
        val result = digest(blockingGate).digest(notifications = notifications())

        assertTrue("应被闸拦下", result.gated)
        assertEquals("恢复窗口内不得调模型", 0, llmCalls)
    }

    @Test
    fun `gated batch is not remembered as processed`() = runTest {
        val d = digest(blockingGate)
        val first = d.digest(notifications = notifications())
        val second = d.digest(notifications = notifications())

        assertTrue(first.gated)
        assertTrue(second.gated)
        assertEquals("被拦下就不能记成「已梳理」", "备份恢复中", second.reason)
    }

    @Test
    fun `restore window during the model call aborts the write`() = runTest {
        val gate = DefaultMemoryWriteGate()
        val restoreDuringCall = object : MemoryLlmClient {
            override suspend fun callText(
                systemPrompt: String,
                userContent: String,
                model: Model?,
                temperature: Float,
                maxTokens: Int,
                timeoutMs: Long,
            ): String {
                // 模型调用期间恢复窗口完整开合:返回时 restoring 已经是 false。
                ProcessWriteGate.begin()
                ProcessWriteGate.end()
                return """{"facts": ["快递已到驿站，凭取件码领取"]}"""
            }
        }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val d = NotificationMemoryDigest(
            factDbProvider = FactDbProvider(context),
            llmClient = restoreDuringCall,
            writeGate = gate,
        )

        val result = d.digest(notifications = notifications())

        assertTrue("跨过恢复窗口的结果不得落库", result.gated)
        assertFalse(result.extracted > 0)
    }
}