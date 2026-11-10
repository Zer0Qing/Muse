package io.zer0.muse.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 暂停点没批下来时，用户看到的那句话（P4 协作语义）。
 *
 * 缺陷原样：`PausePolicy` 默认等 300 秒就自动拒绝，而四处委派入口只看 `decision`，
 * 于是超时也被写成「用户拒绝委派」/「用户取消团队工作流」。
 * 人只是离开了一会儿，回来看见"你拒绝了"—— 既不知道真因是超时，
 * 也不知道可以直接重试。**把系统行为算到用户头上，是最伤信任的文案错。**
 */
class DelegationPauseWordingTest {

    private val manager = DelegationPauseManager()

    private fun response(decision: DelegationPauseManager.PauseDecision, outcome: DelegationPauseManager.PauseOutcome) =
        DelegationPauseManager.PauseResponse(decision = decision, outcome = outcome)

    private val policy = DelegationPauseManager.PausePolicy(autoTimeoutSec = 300)

    @Test
    fun `超时不能写成用户拒绝`() {
        val text = manager.rejectionText(
            response(DelegationPauseManager.PauseDecision.REJECT, DelegationPauseManager.PauseOutcome.TIMED_OUT),
            policy,
            subject = "委派",
            userRejected = "用户拒绝委派",
            userCancelled = "用户取消委派",
        )

        assertFalse("不能把系统行为算到用户头上: $text", text.contains("用户"))
        assertTrue("要说明是自动拒绝: $text", text.contains("自动拒绝"))
        assertTrue("要给出等了多久: $text", text.contains("5 分钟"))
        assertTrue("要给出下一步: $text", text.contains("重试"))
    }

    @Test
    fun `用户真的点了拒绝时保留各处原本的说法`() {
        val text = manager.rejectionText(
            response(DelegationPauseManager.PauseDecision.REJECT, DelegationPauseManager.PauseOutcome.DECIDED),
            policy,
            subject = "中间结果",
            userRejected = "用户拒绝中间结果",
            userCancelled = "用户取消委派",
        )

        assertTrue(text == "用户拒绝中间结果")
    }

    @Test
    fun `用户真的点了取消时也是原说法`() {
        val text = manager.rejectionText(
            response(DelegationPauseManager.PauseDecision.CANCEL, DelegationPauseManager.PauseOutcome.DECIDED),
            policy,
            subject = "团队工作流",
            userRejected = "用户拒绝团队工作流",
            userCancelled = "用户取消团队工作流",
        )

        assertTrue(text == "用户取消团队工作流")
    }

    @Test
    fun `等待秒数换算成分钟向上取整`() {
        // 90 秒说「2 分钟」比「1 分钟」诚实：不足一分钟的部分不该被抹掉
        val text = manager.rejectionText(
            response(DelegationPauseManager.PauseDecision.REJECT, DelegationPauseManager.PauseOutcome.TIMED_OUT),
            DelegationPauseManager.PausePolicy(autoTimeoutSec = 90),
            subject = "该节点",
            userRejected = "用户拒绝执行该节点",
            userCancelled = "用户取消团队工作流",
        )

        assertTrue("应写 2 分钟: $text", text.contains("2 分钟"))
    }

    @Test
    fun `配成不等待时也不写出零分钟的怪话`() {
        val text = manager.rejectionText(
            response(DelegationPauseManager.PauseDecision.REJECT, DelegationPauseManager.PauseOutcome.TIMED_OUT),
            DelegationPauseManager.PausePolicy(autoTimeoutSec = 0),
            subject = "委派",
            userRejected = "用户拒绝委派",
            userCancelled = "用户取消委派",
        )

        assertTrue("0 秒配置下仍要能读通: $text", text.contains("超时") && !text.contains("0 分钟"))
    }
}
