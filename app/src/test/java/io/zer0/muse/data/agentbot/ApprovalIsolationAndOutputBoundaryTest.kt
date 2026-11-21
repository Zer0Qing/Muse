package io.zer0.muse.data.agentbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V07 / V11 / V21 三条边界。
 *
 * V07：两个审批并行，拒绝一个只影响对应 run。
 * V11："停止回复"只停本轮表达，不动后台任务。
 * V21：内部事件、工具结果、审核草稿不得进入公共消息。
 */
class ApprovalIsolationAndOutputBoundaryTest {

    @Test
    fun `approvals are scoped by run and step`() {
        val entity = File("src/main/java/io/zer0/muse/data/agentbot/ApprovalRequestEntity.kt").readText();
        assertTrue("审批必须绑 runId", entity.contains("val runId: String"));
        assertTrue("审批必须绑 stepId", entity.contains("val stepId: String"));
        assertTrue("审批必须绑 taskRevision，否则旧批准会作用于新约束", entity.contains("val taskRevision: Int"));
    }

    @Test
    fun `settling one approval cannot touch another`() {
        val repo = File("src/main/java/io/zer0/muse/data/agentbot/AgentBotRepository.kt").readText();
        assertTrue(
            "结算必须按 approvalId 精确定位",
            repo.contains("approvalDao.settle(approvalId"),
        )
        assertFalse(
            "不得按 taskId 批量结算 —— 那会把并行的另一个审批一起结掉",
            repo.contains("settleByTask"),
        )
    }

    @Test
    fun `stop reply only cancels the foreground turn`() {
        val vm = File("src/main/java/io/zer0/muse/ui/agentbot/AgentBotViewModel.kt").readText();
        val stopAt = vm.indexOf("fun stopReply()");
        assertTrue("必须存在独立的停止回复入口", stopAt > 0);
        // 精确切到函数闭合处：从函数体起点找到第一个独占一行的右花括号。
        val bodyStart = vm.indexOf('{', stopAt);
        val closeMarker = Character.toString(10.toChar()) + "    }";
        val bodyEnd = vm.indexOf(closeMarker, bodyStart);
        require(bodyStart in 0 until bodyEnd) { "无法定位 stopReply 函数体边界" };
        val body = vm.substring(bodyStart, bodyEnd);
        assertTrue("只取消前台 Job", body.contains("foregroundTurnJob?.cancel()"));
        assertFalse(
            "停止回复不得动后台任务 —— 用户说别说了不等于放弃已交代的活",
            body.contains("scheduler.cancel"),
        )
    }

    @Test
    fun `foreground turn job is tracked so it can be stopped`() {
        val vm = File("src/main/java/io/zer0/muse/ui/agentbot/AgentBotViewModel.kt").readText();
        assertTrue(vm.contains("foregroundTurnJob = viewModelScope.launch"));
    }

    @Test
    fun `internal kinds never overlap with publishable kinds`() {
        val overlap = PublicationKind.all intersect PublicationKind.INTERNAL_ONLY;
        assertEquals(
            "内部信息不得出现在对外发布类型里，实际重叠: " + overlap,
            emptySet<String>(),
            overlap,
        )
    }

    @Test
    fun `publication rejects internal kinds at the boundary`() {
        // 类型校验在仓储层，不在调用方 —— 那样漏一处就漏一片。
        val repo = File("src/main/java/io/zer0/muse/data/agentbot/AgentBotRepository.kt").readText();
        assertTrue(repo.contains("require(kind in PublicationKind.all)"));
    }

    @Test
    fun `model raw text has no path into public messages`() {
        val kinds = PublicationKind.all + PublicationKind.INTERNAL_ONLY;
        assertTrue("MODEL_RAW_TEXT 必须被显式列为内部类型", kinds.contains("MODEL_RAW_TEXT"));
        assertFalse(
            "它不能同时是合法发布类型",
            PublicationKind.all.contains("MODEL_RAW_TEXT"),
        )
    }
}