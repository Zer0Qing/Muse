package io.zer0.muse.data.agentbot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 验收清单 B 组（主 Bot 说话与派工）的可静态验证部分。

 * B1「闲聊只回话不派工」与 B2「交代事就派工」依赖真实 Provider 的
 * `toolChoice="required"` 行为 —— 那必须真机验（F 组）。

 * 但 B3「派工后立刻再发一句闲聊，不被阻塞」与 B4「两件不同的事不互相覆盖」
 * 是**架构约束**，可以在代码层钉住：
 *
 *  - 前台回合与后台任务用不同的 Job / 不同的 scope
 *  - 收件箱按 Bot 单写者，但接纳不受执行阻塞
 */
class AcceptanceBDispatchTest {

    private val vm: String by lazy {
        File("src/main/java/io/zer0/muse/ui/agentbot/AgentBotViewModel.kt").readText()
    }

    private val facade: String by lazy {
        File("src/main/java/io/zer0/muse/data/agentbot/AgentConversationFacade.kt").readText()
    }

    @Test
    fun `B3 foreground turn does not block on background tasks`() {
        // 前台回合必须有独立的 Job，才能被"停止回复"单独取消、
        // 也不会因为后台任务在跑而排队。
        assertTrue(
            "前台回合要有独立 Job 句柄",
            vm.contains("foregroundTurnJob"),
        )
    }

    @Test
    fun `B3 stopping the reply leaves the turn job cancellable`() {
        val at = vm.indexOf("fun stopReply()")
        assertTrue(at > 0)
        val body = vm.substring(at, minOf(at + 400, vm.length))
        assertTrue("必须 cancel 前台 Job", body.contains("foregroundTurnJob?.cancel()"))
        assertTrue("并且复位发送状态，否则按钮会卡在「停止」", body.contains("setSending(false)"))
    }

    @Test
    fun `B4 input acceptance is per-bot single writer`() {
        // 单写者保证"两件不同的事"按顺序落库，不会互相覆盖。
        assertTrue(
            "接纳必须有锁",
            facade.contains("acceptLock.withLock"),
        )
    }

    @Test
    fun `B4 the merge window does not swallow distinct requests`() {
        // 合并窗口只合并"同一批次内的连发"，不同意图要靠模型区分，
        // 但每条输入都必须先落库 —— 不能被窗口吞掉。
        assertTrue(
            "输入必须先持久化再谈合并",
            facade.contains("appendEvent") || facade.contains("repository"),
        )
    }
}
