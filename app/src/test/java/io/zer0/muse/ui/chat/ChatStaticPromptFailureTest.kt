package io.zer0.muse.ui.chat

import io.zer0.common.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 静态 system prompt 不可用时的处理策略。
 *
 * 原实现 `resultOf { buildStaticSnapshot(...) }.getOrNull() ?: ""` 会把「构建抛错」
 * 与「构建结果为空」都降级成空提示词继续生成：模型丢掉人格、工具说明、记忆与格式约束，
 * 却仍然产出一条“看起来正常”的回复 —— 用户只会觉得“AI 忽然不像它”，而且这条回复会
 * 进入会话历史，污染后续轮次。这里锁住「判定为不可用」以及「调用方必须中止本轮」。
 */
class ChatStaticPromptFailureTest {

    @Test
    fun `build error is not degraded to an empty prompt`() {
        val outcome = staticPromptBuildOutcome(Result.Error("boom", IllegalStateException("boom")))

        assertTrue("构建抛错必须被判为 Failed", outcome is StaticPromptOutcome.Failed)
        assertEquals("boom", (outcome as StaticPromptOutcome.Failed).reason)
    }

    @Test
    fun `blank build result is treated as unavailable`() {
        assertEquals(StaticPromptOutcome.Empty, staticPromptBuildOutcome(Result.Success("")))
        assertEquals(StaticPromptOutcome.Empty, staticPromptBuildOutcome(Result.Success("   \n\t ")))
    }

    @Test
    fun `non blank build result is ready`() {
        val outcome = staticPromptBuildOutcome(Result.Success("人格定义\n工具说明"))

        assertTrue(outcome is StaticPromptOutcome.Ready)
        assertEquals("人格定义\n工具说明", (outcome as StaticPromptOutcome.Ready).prompt)
    }

    @Test
    fun `generation controller aborts the turn instead of continuing with an empty prompt`() {
        val source = controllerSource()
        // 只检查静态快照的构建块（代码里其他位置仍有 "失败就降级为空" 的合理用法）。
        val staticSnapshotBody = source.substringAfter("val staticSnapshot =")
            .substringBefore("val dynamicSection")

        assertFalse(
            "静态提示词构建失败不得静默降级为空串继续生成",
            staticSnapshotBody.contains(".getOrNull() ?: \"\""),
        )
        assertTrue(
            "静态提示词不可用时必须提前返回",
            staticSnapshotBody.contains("return false"),
        )
        assertTrue(
            "提示词构建函数需要把可用性回传给调用方",
            source.contains("suspend fun buildSystemPromptForStream(state: StreamRunState): Boolean"),
        )
        assertTrue(
            "静态提示词不可用必须中止本轮",
            source.contains("if (!buildSystemPromptForStream(state))"),
        )
        assertTrue(
            "中止路径必须走既有可见错误上报",
            source.contains("R.string.err_chat_prompt_unavailable"),
        )
        assertTrue(
            "中止路径必须有可诊断的终态轨迹",
            source.contains("recordDebugSummary(state, \"prompt_unavailable\")"),
        )
    }

    @Test
    fun `prompt failure is reported before the request is sent`() {
        val source = controllerSource()
        val streamBody = source.substringAfter("fun launchStream(")
            .substringBefore("suspend fun finalizeResponse")

        // 中止必须发生在真正发起请求（runToolLoop）之前，否则等于让模型带着空提示词作答。
        val abortIndex = streamBody.indexOf("err_chat_prompt_unavailable")
        val requestIndex = streamBody.indexOf("deps.runToolLoop(state)")
        assertTrue("中止分支必须存在", abortIndex >= 0)
        assertTrue("本轮请求必须在提示词就绪后才发出", requestIndex > abortIndex)
    }

    private fun controllerSource(): String = listOf(
        Path.of("src/main/java/io/zer0/muse/ui/chat/ChatGenerationController.kt"),
        Path.of("app/src/main/java/io/zer0/muse/ui/chat/ChatGenerationController.kt"),
    )
        .firstOrNull(Files::exists)
        ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
        ?: error("ChatGenerationController.kt not found")
}
