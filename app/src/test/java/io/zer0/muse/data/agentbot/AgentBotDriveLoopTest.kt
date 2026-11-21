package io.zer0.muse.data.agentbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 中枢必须"真的在转"—— 驱动循环与状态刷新都要有调用方。
 *
 * 这两个都是典型的"实现完整但没人调"缺陷：
 *  - tick()/sweepOverdue() 没有循环 → 队列里的任务永远不被领走；
 *  - allowedTools 是普通字段 → 收回后界面不刷新，用户以为按钮坏了。
 */
class AgentBotDriveLoopTest {

    private val scheduler: String by lazy {
        File("src/main/java/io/zer0/muse/data/agentbot/BotTaskScheduler.kt").readText()
    }

    private val viewModel: String by lazy {
        File("src/main/java/io/zer0/muse/ui/agentbot/AgentBotViewModel.kt").readText()
    }

    private val app: String by lazy {
        File("src/main/java/io/zer0/muse/MuseApp.kt").readText()
    }

    @Test
    fun `scheduler exposes a start loop`() {
        assertTrue("必须有启动入口，否则 tick 永远不会被调", scheduler.contains("fun start("));
        assertTrue(scheduler.contains("fun stop("));
    }

    @Test
    fun `the loop calls tick and sweep and approval expiry`() {
        val startAt = scheduler.indexOf("fun start(");
        val body = scheduler.substring(startAt, minOf(startAt + 1600, scheduler.length));
        assertTrue("循环里要领任务", body.contains("tick()"));
        assertTrue("循环里要巡检超时", body.contains("sweepOverdue()"));
        assertTrue("循环里要扫过期审批", body.contains("expireApprovals()"));
    }

    @Test
    fun `starting twice does not create a second loop`() {
        assertTrue(
            "重复调用不能起第二个循环",
            scheduler.contains("if (driveJob?.isActive == true) return"),
        )
    }

    @Test
    fun `a failing round does not kill the loop`() {
        val startAt = scheduler.indexOf("fun start(");
        val body = scheduler.substring(startAt, minOf(startAt + 1600, scheduler.length));
        // 三处都必须是 runCatching 包住 —— 单次失败不能让整个循环退出。
        val guarded = Regex("runCatching \\{").findAll(body).count();
        assertTrue("每一轮都要能扛住单次异常，实际只包了 " + guarded + " 处", guarded >= 3);
    }

    @Test
    fun `app actually starts the loop at boot`() {
        assertTrue(
            "MuseApp 必须真的调用 start()，否则前面的实现全是死代码",
            app.contains("botTaskScheduler.start()"),
        )
    }

    @Test
    fun `allowance list is a state flow so revoke refreshes the screen`() {
        assertTrue(
            "必须是 StateFlow，普通字段不会触发重组",
            viewModel.contains("val allowedTools: kotlinx.coroutines.flow.StateFlow"),
        )
        assertFalse(
            "旧的普通字段写法已废弃",
            viewModel.contains("fun allowedToolsThisConversation()"),
        )
    }

    @Test
    fun `revoking an allowance refreshes the list immediately`() {
        val revokeAt = viewModel.indexOf("fun revokeAllowance(");
        assertTrue(revokeAt > 0);
        val body = viewModel.substring(revokeAt, minOf(revokeAt + 700, viewModel.length));
        assertTrue("收回后要立刻重读，不能等下次进页面", body.contains("refreshAllowances()"));
    }

    @Test
    fun `binding a conversation loads its allowance list`() {
        val bindAt = viewModel.indexOf("fun bind(");
        assertTrue(bindAt > 0);
        val body = viewModel.substring(bindAt, minOf(bindAt + 600, viewModel.length));
        assertTrue(body.contains("refreshAllowances()"));
    }
}
