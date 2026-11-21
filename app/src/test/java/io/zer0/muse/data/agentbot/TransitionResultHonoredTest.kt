package io.zer0.muse.data.agentbot;

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 状态机拒绝转移时，不得继续暂存与应用。

 * 独立审查发现的 P0：`onRunFinished` 里 `transition(EXECUTE_DONE)` 的返回值被丢弃。
 * 当本轮因审批挂起转到 `WAITING_APPROVAL` 时，`EXECUTE_DONE` 非法、返回 null，
 * 但原实现继续往下走 —— 产物照样进暂存区、照样被审核、照样**写进目标文件**。
 *
 * 也就是「用户还没批准，文件已经改了」。
 */
class TransitionResultHonoredTest {

    private val scheduler: String by lazy {
        File("src/main/java/io/zer0/muse/data/agentbot/BotTaskScheduler.kt").readText()
    }

    @Test
    fun `EXECUTE_DONE result is captured not discarded`() {
        // 必须是 val x = transition(...)，不能是裸调用。
        val bare = Regex("(?m)^\\s*repository\\.transition\\(\\s*task\\.taskId,\\s*BotTaskStatusMachine\\.Event\\.EXECUTE_DONE");
        assertFalse(
            "EXECUTE_DONE 的返回值不能丢弃 —— 被拒后仍会应用产物",
            bare.containsMatchIn(scheduler),
        );
        assertTrue(
            "必须捕获转移结果",
            scheduler.contains("val moved = repository.transition("),
        );
    }

    @Test
    fun `a rejected transition returns early before staging`() {
        val at = scheduler.indexOf("val moved = repository.transition(");
        assertTrue(at > 0);
        // 早退必须在 staging.stage( 之前。
        val stageAt = scheduler.indexOf("staging?.stage(", at);
        val guardAt = scheduler.indexOf("if (moved == null)", at);
        assertTrue("必须有 moved == null 的早退", guardAt > 0);
        assertTrue("早退必须发生在暂存之前", guardAt < stageAt);
        // 早退块里必须有 return。
        val block = scheduler.substring(guardAt, minOf(guardAt + 500, scheduler.length));
        assertTrue("早退块必须真的 return", block.contains("return"));
    }
}
