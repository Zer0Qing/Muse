package io.zer0.muse.data.agentbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 审批不能因为「界面传错参数」而永久卡住。

 * 独立审查发现的 P1-4：界面批准时传空 `argumentsHash`，服务端拿它和建卡时的
 * 哈希比对 → 不一致 → 判 INVALIDATED → 任务停在 WAITING_APPROVAL。
 *
 * 更糟的是那三条早退分支**都不调 `onSettled`**，于是任务永远出不来：
 * `expireApprovals` 只扫 PENDING，卡已结算，扫不到。
 */
class ApprovalWiringIntegrityTest {

    private val screen: String by lazy {
        File("src/main/java/io/zer0/muse/ui/agentbot/AgentBotScreen.kt").readText()
    }

    private val service: String by lazy {
        File("src/main/java/io/zer0/muse/data/agentbot/ApprovalService.kt").readText()
    }

    @Test
    fun `ui passes the card hash and resource version`() {
        assertFalse(
            "不能传空串 —— 那会让每次批准都判 INVALIDATED",
            screen.contains("viewModel.approve(row.approvalId, \"\", \"\")"),
        )
        assertTrue(
            "必须回传卡片上的 hash",
            screen.contains("row.argumentsHash"),
        )
        assertTrue(
            "必须回传卡片上的资源版本",
            screen.contains("row.resourceVersion"),
        )
    }

    @Test
    fun `early exit branches notify so the task can leave WAITING_APPROVAL`() {
        // 过期、参数漂移、资源漂移三条早退都必须回调。
        val count = Regex("settleAndNotify\\(").findAll(service).count()
        assertTrue(
            "三条早退分支 + 一处定义，至少 4 处引用，实际 " + count,
            count >= 4,
        )
    }

    @Test
    fun `settle helper tolerates a failing callback`() {
        val at = service.indexOf("private suspend fun settleAndNotify(")
        assertTrue(at > 0)
        val body = service.substring(at, minOf(at + 400, service.length))
        assertTrue(
            "回调失败不能让审批看起来失败 —— 它已经结算了",
            body.contains("catch (e: Exception)") && body.contains("catch (e: CancellationException)"),
        )
    }
}
