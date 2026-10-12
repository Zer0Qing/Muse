package io.zer0.memory.ai

import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.UIMessage
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.llm.MemoryLlmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 回归测试：LLM 显式返回 null 不应让整份 auto-save 分析结果失效。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemoryAutoSaveSchedulerParseTest {

    @Test
    fun `fingerprint includes the full LLM input window instead of only the first 200 chars`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scheduler = MemoryAutoSaveScheduler(
            factDbProvider = FactDbProvider(context),
            llmClient = object : MemoryLlmClient {
                override suspend fun callText(
                    systemPrompt: String,
                    userContent: String,
                    model: Model?,
                    temperature: Float,
                    maxTokens: Int,
                    timeoutMs: Long,
                ): String = "{}"
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val prefix = "x".repeat(250)
        val first = UIMessage(role = MessageRole.USER, content = prefix + "A")
        val second = UIMessage(role = MessageRole.USER, content = prefix + "B")

        assertEquals(false, scheduler.fingerprintHistory(listOf(first)) == scheduler.fingerprintHistory(listOf(second)))
    }

    @Test
    fun `fingerprint changes when an older message changes outside the recent extraction window`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scheduler = MemoryAutoSaveScheduler(
            factDbProvider = FactDbProvider(context),
            llmClient = object : MemoryLlmClient {
                override suspend fun callText(
                    systemPrompt: String,
                    userContent: String,
                    model: Model?,
                    temperature: Float,
                    maxTokens: Int,
                    timeoutMs: Long,
                ): String = "{}"
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val history = List(31) { index ->
            UIMessage(role = MessageRole.USER, content = "message-$index")
        }
        val edited = history.toMutableList().apply {
            this[0] = UIMessage(role = MessageRole.USER, content = "edited-old-message")
        }

        assertEquals(false, scheduler.fingerprintHistory(history) == scheduler.fingerprintHistory(edited))
    }

    @Test
    fun `explicit null optional object fields are coerced to defaults`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scheduler = MemoryAutoSaveScheduler(
            factDbProvider = FactDbProvider(context),
            llmClient = object : MemoryLlmClient {
                override suspend fun callText(
                    systemPrompt: String,
                    userContent: String,
                    model: Model?,
                    temperature: Float,
                    maxTokens: Int,
                    timeoutMs: Long,
                ): String = error("test must not call LLM")
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        val result = scheduler.parseAnalysisResult(
            """{
                "mainProblem": {"title": null, "content": null},
                "extractedEntities": [{"title": null, "content": "用户喜欢 Kotlin"}],
                "links": [{"sourceTitle": null, "targetTitle": null}]
            }""",
        )

        assertNotNull(result)
        assertEquals("", result?.mainProblem?.title)
        assertEquals("", result?.mainProblem?.content)
        assertEquals("用户喜欢 Kotlin", result?.extractedEntities?.single()?.content)
        assertEquals("", result?.links?.single()?.sourceTitle)
    }

    @Test
    fun `analysis preserves speaker attribution for extracted facts`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scheduler = MemoryAutoSaveScheduler(
            factDbProvider = FactDbProvider(context),
            llmClient = object : MemoryLlmClient {
                override suspend fun callText(
                    systemPrompt: String,
                    userContent: String,
                    model: Model?,
                    temperature: Float,
                    maxTokens: Int,
                    timeoutMs: Long,
                ): String = "{}"
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        val result = scheduler.parseAnalysisResult(
            """{"extractedEntities":[{"title":"assistant preference","content":"I like tea","speaker":"ASSISTANT"}]}""",
        )

        assertEquals("ASSISTANT", result?.extractedEntities?.single()?.speaker)
        assertEquals(emptyList<ParsedEntity>(), result?.userFactsOnly())
    }

    @Test
    fun `memory extraction keeps system and tool messages out of assistant speaker bucket`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        var capturedHistory = ""
        val scheduler = MemoryAutoSaveScheduler(
            factDbProvider = FactDbProvider(context),
            llmClient = object : MemoryLlmClient {
                override suspend fun callText(
                    systemPrompt: String,
                    userContent: String,
                    model: Model?,
                    temperature: Float,
                    maxTokens: Int,
                    timeoutMs: Long,
                ): String {
                    capturedHistory = userContent
                    return "{}"
                }
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        scheduler.extractEntities(
            history = listOf(
                UIMessage(role = MessageRole.SYSTEM, content = "system instruction"),
                UIMessage(role = MessageRole.USER, content = "user statement"),
                UIMessage(role = MessageRole.ASSISTANT, content = "assistant statement"),
                UIMessage(role = MessageRole.TOOL, content = "tool result"),
            ),
            existingFactsPreview = null,
            model = null,
        )

        assertTrue(capturedHistory.contains("**系统**: system instruction"))
        assertTrue(capturedHistory.contains("**用户**: user statement"))
        assertTrue(capturedHistory.contains("**助手**: assistant statement"))
        assertTrue(capturedHistory.contains("**工具**: tool result"))
        assertFalse(capturedHistory.contains("**助手**: system instruction"))
        assertFalse(capturedHistory.contains("**助手**: tool result"))
    }
}
