package io.zer0.muse.automation.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.zer0.muse.tools.BrowserManager
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolResultJudge
import io.zer0.muse.tools.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UiAgentToolTest {
    private fun registry() = ToolRegistry(
        context = ApplicationProvider.getApplicationContext<Context>(),
        browserManager = mockk<BrowserManager>(relaxed = true),
    )

    @Test
    fun `tool description discloses that screenshots are sent to the selected vision model`() {
        val registry = registry()
        UiAgentTool(mockk()).register(registry)

        val description = registry.listTools().first { it.name == "ui_agent" }.description

        assertTrue(description.contains("截图可能包含聊天或账号等敏感信息"))
        assertTrue(description.contains("发送到所选视觉模型分析"))
    }

    @Test
    fun `runner failure is an error outcome for the outer Agent loop`() = runBlocking {
        val runner = mockk<UiAgentRunner>()
        coEvery { runner.run(any(), any(), any(), any(), any()) } returns
            UiAgentRunner.RunResult(false, "视觉辅助未启用")
        val registry = registry()
        UiAgentTool(runner).register(registry)

        val outcome = registry.execute("ui_agent", mapOf("task" to "Observe the current screen"))
        val orchestratorText = registry.executeFromJson(
            "ui_agent",
            """{"task":"Observe the current screen"}""",
        )

        assertTrue(outcome.isError)
        assertTrue(outcome.content.contains("任务未完成"))
        assertEquals(false, outcome.details["finished"])
        assertTrue(orchestratorText.startsWith("Error:"))
        assertFalse(ToolResultJudge.isSuccess(orchestratorText))
        coVerify(exactly = 2) { runner.run(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `finished Agent interaction remains a successful outcome`() = runBlocking {
        val runner = mockk<UiAgentRunner>()
        coEvery { runner.run(any(), any(), any(), any(), any()) } returns
            UiAgentRunner.RunResult(true, "已观察并报告")
        val registry = registry()
        UiAgentTool(runner).register(registry)

        val outcome = registry.execute("ui_agent", mapOf("task" to "Observe the current screen"))
        val orchestratorText = registry.executeFromJson(
            "ui_agent",
            """{"task":"Observe the current screen"}""",
        )

        assertFalse(outcome.isError)
        assertTrue(outcome.content.contains("任务结束"))
        assertEquals(true, outcome.details["finished"])
        assertTrue(ToolResultJudge.isSuccess(orchestratorText))
    }

    @Test
    fun `invalid display options return an error without starting runner`() = runBlocking {
        val runner = mockk<UiAgentRunner>(relaxed = true)
        val registry = registry()
        UiAgentTool(runner).register(registry)

        val outcome = registry.execute("ui_agent", mapOf("task" to "Observe", "display" to "sideways"))

        assertTrue(outcome.isError)
        assertTrue(outcome.content.contains("display"))
        coVerify(exactly = 0) { runner.run(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `progress from the runner reaches the execution context`() = runBlocking {
        // 15 步的 ui_agent 跑起来要好几分钟，中途没有任何反馈时用户只能看到转圈。
        // 这条钉住：runner 的 onProgress 必须一路传到 ToolExecutionContext.onProgress。
        val runner = mockk<UiAgentRunner>()
        coEvery { runner.run(any(), any(), any(), any(), any()) } coAnswers {
            val onProgress = arg<(String) -> Unit>(2)
            onProgress("第 1 步:tap -> ok")
            UiAgentRunner.RunResult(true, "done")
        }
        val registry = registry()
        UiAgentTool(runner).register(registry)
        val seen = mutableListOf<String>()

        registry.executeFromJson(
            "ui_agent",
            """{"task":"打开设置"}""",
            ToolExecutionContext(scope = "main", spaceId = "default", onProgress = { seen += it }),
        )

        assertTrue("进度应流到上下文，实际: $seen", seen.contains("第 1 步:tap -> ok"))
    }
}
