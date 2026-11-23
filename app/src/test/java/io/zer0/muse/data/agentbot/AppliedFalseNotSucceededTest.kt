package io.zer0.muse.data.agentbot;

import org.junit.Assert.assertTrue;
import org.junit.Test;
import java.io.File;

/**
 * 「没应用」不能落 SUCCEEDED。

 * 独立审查发现的 P1-10：`BotResultApplier` 在三条「审核通过但没写成功」的路径上
 * 仍然调 `applyTransition(decision)`，把任务转成 SUCCEEDED ——
 * 用户看到「完成」，目标文件一个字没变。
 *
 * 那正是 `BotTaskScheduler` 自己点名的「声称做了却没做」。
 */
class AppliedFalseNotSucceededTest {

    private val applier: String by lazy {
        File("src/main/java/io/zer0/muse/data/agentbot/BotResultApplier.kt").readText();
    }

    @Test
    fun `no candidate path does not transition to succeeded`() {
        val at = applier.indexOf("if (candidates.isEmpty()) {");
        assertTrue(at > 0);
        val end = applier.indexOf("if (writeTarget == null && workspaceManager == null)", at);
        val block = applier.substring(at, if (end > at) end else applier.length);
        assertTrue(
            "没有候选时不能落 SUCCEEDED",
            !block.contains("transition = BotTaskStatusMachine.Event.REVIEW_ACCEPT"),
        );
        assertTrue(
            "要转成待处理，让用户知道结果没落地",
            block.contains("REVIEW_NEEDS_USER"),
        );
    }

    @Test
    fun `no write capability path does not transition to succeeded`() {
        val at = applier.indexOf("if (writeTarget == null && workspaceManager == null) {");
        assertTrue(at > 0);
        val end = applier.indexOf("val store = staging", at);
        val block = applier.substring(at, if (end > at) end else applier.length);
        assertTrue(
            "没有写入能力时不能落 SUCCEEDED",
            !block.contains("applyTransition(task.taskId, decision)"),
        );
        assertTrue("要转成待处理", block.contains("REVIEW_NEEDS_USER"));
    }

    @Test
    fun `scheduler observes the review settlement flag`() {
        val scheduler = File("src/main/java/io/zer0/muse/data/agentbot/BotTaskScheduler.kt").readText();
        assertTrue(
            "process 的返回值不能丢弃",
            scheduler.contains("val reviewOutcome = runCatching {"),
        );
        assertTrue(
            "未结算的审核结果要留痕",
            scheduler.contains("reviewOutcome.accepted"),
        );
    }
}
