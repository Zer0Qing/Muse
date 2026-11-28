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
        assertTrue("必须有启动入口，否则 tick 永远不会被调", scheduler.contains("fun start("))
        assertTrue(scheduler.contains("fun stop("))
    }

    @Test
    fun `the loop calls tick and sweep and approval expiry`() {
        val startAt = scheduler.indexOf("fun start(")
        val body = scheduler.substring(startAt, minOf(startAt + 1600, scheduler.length))
        assertTrue("循环里要领任务", body.contains("tick()"))
        assertTrue("循环里要巡检超时", body.contains("sweepOverdue()"))
        assertTrue("循环里要扫过期审批", body.contains("expireApprovals()"))
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
        val startAt = scheduler.indexOf("fun start(")
        val body = scheduler.substring(startAt, minOf(startAt + 1600, scheduler.length))
        // 三处都必须是 runCatching 包住 —— 单次失败不能让整个循环退出。
        val guarded = Regex("runCatching \\{").findAll(body).count()
        assertTrue("每一轮都要能扛住单次异常，实际只包了 " + guarded + " 处", guarded >= 3)
    }

    @Test
    fun `app actually starts the loop at boot`() {
        assertTrue(
            "MuseApp 必须真的调用 start()，否则前面的实现全是死代码",
            app.contains("botTaskScheduler.start()"),
        )
    }

    @Test
    fun `conversation wide tool allowance list is absent from the screen model`() {
        assertFalse(viewModel.contains("val allowedTools: kotlinx.coroutines.flow.StateFlow"))
        assertFalse(viewModel.contains("fun revokeAllowance("))
    }

    @Test
    fun `tool permission flow uses individual approval decisions`() {
        assertTrue(viewModel.contains("approvals.approve("))
        assertFalse(viewModel.contains("allowances.grant("))
    }

    @Test
    fun `binding a conversation does not load stale tool allowances`() {
        val bindAt = viewModel.indexOf("fun bind(")
        assertTrue(bindAt > 0)
        val bindEnd = Regex("\\R    \\}\\R").find(viewModel, bindAt)?.range?.first
            ?: error("找不到 bind 函数结尾")
        val body = viewModel.substring(bindAt, bindEnd)
        assertFalse(body.contains("refreshAllowances()"))
    }

    @Test
    fun `foreground cleanup and draft callbacks are bound to captured identities`() {
        assertTrue(viewModel.contains("activeForegroundTurnIdentity == identity"))
        assertTrue(
            Regex("if\\s*\\(isCurrentScope\\(\\)\\)\\s*\\{\\s*ui\\.setTyping\\(false\\)\\s*ui\\.clearStreaming\\(\\)")
                .containsMatchIn(viewModel),
        )
        assertTrue(viewModel.contains("scopeGenerationAtStart"))
        assertTrue(viewModel.contains("draftGenerationAtStart"))
        assertTrue(viewModel.contains("isCurrentComposerDraft("))
    }

    @Test
    fun `accepted request revision prevents an older suspended submission from replacing a newer turn`() {
        assertTrue(viewModel.contains("acceptedRevisionByScope"))
        assertTrue(viewModel.contains("accepted.requestRevision < latest"))
        assertTrue(viewModel.contains("if (!isCurrentScope || !isNewestAccepted) return@launch"))
    }
}
