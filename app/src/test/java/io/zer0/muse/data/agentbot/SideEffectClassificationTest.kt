package io.zer0.muse.data.agentbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 副作用分级：不能一律保守。
 *
 * 全标 NO_BLIND_REPLAY 会让每个任务都判 PARTIAL —— 读文件、查资料也会被算成
 * "已发生且无法回滚"，用户看到满屏"只完成了一部分"而实际上可能全做完了。
 * 过度保守和过度乐观一样有害。
 */
class SideEffectClassificationTest {

    private val scheduler: String by lazy {
        File("src/main/java/io/zer0/muse/data/agentbot/BotTaskScheduler.kt").readText()
    }

    @Test
    fun `safe tools are classified as retry-safe`() {
        assertTrue(
            "只读工具重放不会产生新副作用",
            scheduler.contains("ToolRiskLevel.SAFE -> StepSideEffect.RETRY_SAFE"),
        )
    }

    @Test
    fun `write-capable tools stay no-blind-replay`() {
        assertTrue(
            "会写东西的工具不能盲重放",
            scheduler.contains("else -> StepSideEffect.NO_BLIND_REPLAY"),
        )
    }

    @Test
    fun `unknown risk falls back to conservative`() {
        assertTrue(
            "不知道就不重放",
            scheduler.contains("null -> StepSideEffect.NO_BLIND_REPLAY"),
        )
    }

    @Test
    fun `classification uses the registry not a hardcoded list`() {
        assertTrue(scheduler.contains("getToolRiskLevel(toolName)"))
    }

    @Test
    fun `registry is optional so pure JVM tests still run`() {
        assertTrue(scheduler.contains("toolRegistryRef: io.zer0.muse.tools.ToolRegistry? = null"))
    }

    @Test
    fun `classification failure does not break step recording`() {
        assertTrue(
            "读风险失败要能降级，不能拖垮记账",
            scheduler.contains("读取工具风险失败"),
        )
    }

    @Test
    fun `wiring no longer hardcodes no-blind-replay`() {
        assertFalse(
            "旧的硬编码已废弃",
            scheduler.contains("sideEffect = StepSideEffect.NO_BLIND_REPLAY,\n                                );"),
        )
    }
}
