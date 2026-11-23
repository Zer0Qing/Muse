package io.zer0.muse.data.agentbot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V08 / V14 / V24 三条的回归断言。
 *
 * 这三条在前几轮已实现并有行为测试，但**没有一条断言把"实现还在"钉住**。
 * 源码扫描的断言能防"被删"，行为测试能防"改错"，两者都需要。
 */
class ApprovalAndBatchContractTest {

    private fun source(rel: String) = File("src/main/java/" + rel).readText();

    // ── V08：改参数后点旧批准，工具不执行 ─────────────────────

    @Test
    fun `approval compares the argument hash before settling`() {
        val svc = source("io/zer0/muse/data/agentbot/ApprovalService.kt");
        assertTrue(
            "参数哈希必须比对：卡片展示 A、实际执行 B 是最危险的错配",
            svc.contains("approval.argumentsHash != currentArgumentsHash"),
        );
        assertTrue(
            "不一致要标 INVALIDATED 而不是直接拒绝 —— 用户需要知道是参数变了",
            svc.contains("ApprovalStatus.INVALIDATED"),
        );
    }

    @Test
    fun `approval compares the resource version too`() {
        val svc = source("io/zer0/muse/data/agentbot/ApprovalService.kt");
        assertTrue(
            "资源也变了的话，旧批准同样不该生效",
            svc.contains("approval.resourceVersion != currentResourceVersion"),
        );
    }

    @Test
    fun `argument check happens before the CAS settle`() {
        val svc = source("io/zer0/muse/data/agentbot/ApprovalService.kt");
        val checkAt = svc.indexOf("approval.argumentsHash != currentArgumentsHash");
        val settleAt = svc.indexOf("repository.settleApproval(approvalId, target");
        assertTrue(
            "先核验再结算：反序会让不该生效的批准把状态占掉",
            checkAt in 0 until settleAt,
        );
    }

    // ── V14：新输入到达时未发旧气泡暂停 ───────────────────────

    @Test
    fun `new input abandons the whole pending batch`() {
        val svc = source("io/zer0/muse/data/agentbot/PublicationService.kt");
        assertTrue(
            "输入版本变了就不能继续发旧批次",
            svc.contains("latestRevision != requestRevision") &&
                svc.contains("latestUserInputSequence(botId, originSessionId)"),
        );
    }

    @Test
    fun `resume only resends pending items`() {
        val svc = source("io/zer0/muse/data/agentbot/PublicationService.kt");
        assertTrue(
            "已发出的不能重投，否则用户会看到重复消息",
            svc.contains("it.status == PublicationStatus.PENDING"),
        );
        // COMMITTED / SUPERSEDED 天然不动：filter 只挑 PENDING，
        // 另外两种不会被选中。这条断言守的是"filter 是唯一入口"。
        assertTrue(
            "恢复必须且只能从 PENDING 里挑",
            svc.contains("listByPublication(publicationId)") &&
                svc.contains(".filter { it.status == PublicationStatus.PENDING }"),
        );
    }

    // ── V24：审批卡独立立即可读，重复不生成第二张 ─────────────

    @Test
    fun `approval section renders independently of the chat flow`() {
        val screen = source("io/zer0/muse/ui/agentbot/AgentBotScreen.kt");
        val workbench = source("io/zer0/muse/ui/agentbot/AgentWorkbenchSheet.kt");
        assertTrue(
            "审批卡要在主对话流上独立呈现，不依赖主 Bot 正在说话",
            screen.contains("items(pendingApprovals, key = { \"approval:\${it.approvalId}\" })") &&
                screen.contains("AgentInlineApprovalCard("),
        );
        val liveTabAt = workbench.indexOf("private fun WorkbenchLiveTab(");
        val approvalAt = workbench.indexOf("projection?.pendingApprovals", liveTabAt);
        val activeTasksAt = workbench.indexOf("val activeTasks =", approvalAt);
        assertTrue(
            "工作台实况页中待审批区必须排在普通任务列表前",
            liveTabAt >= 0 && approvalAt in (liveTabAt + 1) until activeTasksAt,
        );
    }

    @Test
    fun `approval creation is idempotent per step`() {
        val repo = source("io/zer0/muse/data/agentbot/AgentBotRepository.kt");
        assertTrue(
            "审批按 stepId 建，同一步不该生成两张卡",
            repo.contains("stepId = stepId"),
        );
    }

    @Test
    fun `expired approval is reported as expired not denied`() {
        val svc = source("io/zer0/muse/data/agentbot/ApprovalService.kt");
        assertTrue(
            "过期不是拒绝：主 Bot 不能说\"你拒绝了\"",
            svc.contains("Outcome.Expired"),
        );
        assertTrue(svc.contains("ACTOR_SYSTEM"));
    }
}
