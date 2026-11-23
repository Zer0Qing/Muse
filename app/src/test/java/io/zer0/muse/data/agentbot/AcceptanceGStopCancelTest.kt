package io.zer0.muse.data.agentbot;

import org.junit.Assert.assertTrue;
import org.junit.Test;
import java.io.File;

/**
 * 验收清单 G 组（停止与取消）的可静态验证部分。

 * 清单原话：

 * > G1 与 G2 必须是两个不同的操作。若界面上只有一个"停止"按钮，记为不通过。

 * 查证发现：`stopReply()` 早就实现了（V11），但**界面上一直没有入口**。
 * 用户想让它别说了，只能去取消任务 —— 结果把正在做的事也丢了。
 */
class AcceptanceGStopCancelTest {

    private val screen: String by lazy {
        File("src/main/java/io/zer0/muse/ui/agentbot/AgentBotScreen.kt").readText();
    }

    private val vm: String by lazy {
        File("src/main/java/io/zer0/muse/ui/agentbot/AgentBotViewModel.kt").readText();
    }

    private val rows: String by lazy {
        File("src/main/java/io/zer0/muse/ui/agentbot/AgentBotRowSection.kt").readText();
    }

    @Test
    fun `G1 stop reply has a UI entry`() {
        assertTrue(
            "停止回复必须有按钮 —— 否则用户只能取消任务",
            screen.contains("viewModel::stopReply"),
        );
    }

    @Test
    fun `G1 and G2 are two different actions`() {
        // 两个动作必须调用不同的方法，不能共用一个入口。
        assertTrue("停止回复走 stopReply", screen.contains("stopReply"));
        assertTrue("取消任务走 onCancel/cancelTask", rows.contains("onCancel(row.taskId)"));
        assertTrue(
            "两者不能是同一个方法",
            !screen.contains("stopReply(row.taskId)"),
        );
    }

    @Test
    fun `stop reply does not touch background tasks`() {
        val at = vm.indexOf("fun stopReply()");
        assertTrue("stopReply 必须存在", at > 0);
        val body = vm.substring(at, minOf(at + 400, vm.length));
        assertTrue(
            "只停前台表达，不能碰后台任务",
            !body.contains("scheduler.cancel"),
        );
    }

    @Test
    fun `cancel task goes through the scheduler`() {
        val at = vm.indexOf("fun cancelTask(");
        assertTrue(at > 0);
        val body = vm.substring(at, minOf(at + 400, vm.length));
        assertTrue("取消任务必须走调度器（它要判副作用）", body.contains("scheduler.cancel"));
    }

    @Test
    fun `G3 cancellation does not pretend to roll back`() {
        // 清单 G3：已产生的改动不被回滚，也不假装回滚。
        val at = vm.indexOf("fun cancelTask(");
        val body = vm.substring(at, minOf(at + 400, vm.length));
        assertTrue(
            "取消不能做任何回滚动作",
            !body.contains("rollback") && !body.contains("revert"),
        );
    }
}
