package io.zer0.muse.data.agentbot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 步骤账本必须真的有写入点。
 *
 * 上一轮汇总逻辑写好了，但 recordStep 没有生产调用方 —— 步骤表永远是空的，
 * 汇总永远拿到空列表，V23 的副作用永远汇报不出来。
 */
class StepRecordingWiringTest {

    private val runner: String by lazy {
        File("src/main/java/io/zer0/muse/tools/SubagentRunner.kt").readText()
    }

    private val scheduler: String by lazy {
        File("src/main/java/io/zer0/muse/data/agentbot/BotTaskScheduler.kt").readText()
    }

    private val accessibilityService: String by lazy {
        File("src/main/java/io/zer0/muse/automation/executors/MuseAccessibilityService.kt").readText()
    }

    @Test
    fun `runner exposes tool lifecycle callbacks`() {
        assertTrue(runner.contains("onToolStarted"))
        assertTrue(runner.contains("onToolFinished"))
    }

    @Test
    fun `callbacks default to null so old callers are unchanged`() {
        assertTrue(
            "旧 subagent_run / subagent_task 不传回调，行为不变（V25）",
            runner.contains("val onToolStarted: (suspend (toolName: String, argsJson: String) -> String)? = null"),
        )
    }

    @Test
    fun `callback fires before the tool executes`() {
        val startAt = runner.indexOf("params.onToolStarted")
        val execAt = runner.indexOf("executeAllowedTool(", startAt)
        assertTrue(
            "记账要早于执行，否则工具失败时这一步就丢了",
            startAt > 0 && startAt < execAt,
        )
        assertTrue("宿主工具上下文必须跟随执行", runner.contains("params.toolExecutionContext"))
    }

    @Test
    fun `callback failure does not break the tool loop`() {
        val startAt = runner.indexOf("params.onToolStarted")
        val body = runner.substring(startAt, minOf(startAt + 600, runner.length))
        assertTrue("回调异常不能拖垮整个工具循环", body.contains("runCatching"))
    }

    @Test
    fun `scheduler records every tool call into the step ledger`() {
        assertTrue(
            "调度器必须真的调 recordStep，否则步骤表永远空",
            scheduler.contains("repository.recordStep("),
        )
    }

    @Test
    fun `step index is per-run not global`() {
        assertTrue(
            "两个 run 并行时各自计数，不能互相干扰",
            scheduler.contains("var stepCounter = 0"),
        )
    }

    @Test
    fun `accessibility decisions pass only sanitized structured fields to recorder`() {
        assertTrue(accessibilityService.contains("recorder.recordStructuredStep("))
        listOf("decision.targetPackage", "decision.targetText", "decision.contentDesc", "decision.resourceIdHint", "decision.actionKind")
            .forEach { field -> assertTrue("missing $field mapping", accessibilityService.contains(field)) }
    }

    @Test
    fun `step side effect is classified by tool risk`() {
        // 曾经硬编码 NO_BLIND_REPLAY，导致只读工具也被算成副作用，
        // 每个任务都判 PARTIAL。改为按风险分级。
        assertTrue(
            "必须按工具风险分级，不能一律保守",
            scheduler.contains("sideEffect = classifySideEffect(toolName)"),
        )
    }
}
