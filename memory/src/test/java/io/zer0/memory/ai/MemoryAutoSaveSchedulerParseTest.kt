package io.zer0.memory.ai

import io.zer0.ai.core.Model
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.llm.MemoryLlmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.test.core.app.ApplicationProvider

/** 回归测试：LLM 显式返回 null 不应让整份 auto-save 分析结果失效。 */
@RunWith(RobolectricTestRunner::class)
class MemoryAutoSaveSchedulerParseTest {

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
}
