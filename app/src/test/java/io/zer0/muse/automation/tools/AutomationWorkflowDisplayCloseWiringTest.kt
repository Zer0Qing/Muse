package io.zer0.muse.automation.tools

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class AutomationWorkflowDisplayCloseWiringTest {

    @Test
    fun `stale display close is idempotent without re-acquiring a dead lease`() {
        val source =
            listOf(
                Path.of("src/main/java/io/zer0/muse/automation/tools/AutomationWorkflow.kt"),
                Path.of("app/src/main/java/io/zer0/muse/automation/tools/AutomationWorkflow.kt"),
            )
                .firstOrNull(Files::exists)
                ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
                ?: error("AutomationWorkflow.kt not found")
        // 关闭逻辑在重构（第 36 轮）里搬进了 closeVirtualDisplay()，断言跟着走。
        // 要点不变：① 服务重启导致的老 display 已消失时，要能识别出来（读 lastDisplayId 对比）；
        //           ② 死掉的屏幕不得重新登记 lease。
        val closeBody = source.substringAfter("private suspend fun closeVirtualDisplay(")
            .substringBefore("private suspend fun executeNodeScript")
        assertTrue(
            "closeVirtualDisplay 应比对 lastDisplayId 以识别已消失的屏幕",
            closeBody.contains("displayManager.lastDisplayId"),
        )
        assertTrue(
            "closeVirtualDisplay 应在必要时重新登记 lease（且条件排除死屏幕）",
            closeBody.contains("displayLeases.acquire(runId, displayId)"),
        )
    }
}
