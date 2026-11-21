package io.zer0.muse.data.agentbot;

import org.junit.Assert.assertTrue;
import org.junit.Test;
import java.io.File;

/**
 * 巡检必须真的清理已消费事件。

 * `pruneEvents` 此前从未被调用 —— 已消费的事件永久留在库里。
 * 事件是中枢写入最频繁的表（每轮对话、每个工具步骤都追加），
 * 不清会无界增长，拖慢每一次 `pendingEvents` 查询。
 *
 * 这和第九十一轮的 `priorityOf` 是同一类缺陷：定义了、注释写得很好、但没人调用。
 */
class EventPruneWiringTest {

    private val scheduler: String by lazy {
        File("src/main/java/io/zer0/muse/data/agentbot/BotTaskScheduler.kt").readText();
    }

    @Test
    fun `drive loop prunes consumed events`() {
        val at = scheduler.indexOf("fun start(intervalMs: Long");
        assertTrue("start 必须存在", at > 0);
        val end = scheduler.indexOf("fun stop(", at);
        val body = scheduler.substring(at, if (end > at) end else scheduler.length);
        assertTrue(
            "巡检循环必须清理事件，否则表会无界增长",
            body.contains("pruneEvents()"),
        );
    }

    @Test
    fun `prune failure does not break the loop`() {
        // 清理失败不能中断巡检 —— 它只是清理，不是关键路径。
        val at = scheduler.indexOf("repository.pruneEvents()");
        assertTrue(at > 0);
        val around = scheduler.substring(maxOf(0, at - 120), minOf(at + 200, scheduler.length));
        assertTrue("要包在 runCatching 里", around.contains("runCatching"));
    }
}
