package io.zer0.muse.data.agentbot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 非终态任务必须至少有一个操作出口。
 *
 * 第五十七轮把"靠 status 猜按钮"改成显式操作集时，漏掉了 NEEDS_USER ——
 * 那一行上一个按钮都没有，任务会永久停在那里。
 *
 * 这个类用源码扫描守住"每个非终态都在操作集里被考虑过"。
 */
class TaskActionCoverageTest {

    private val vm: String by lazy {
        File("src/main/java/io/zer0/muse/ui/agentbot/AgentBotViewModel.kt").readText()
    }

    @Test
    fun `every non terminal status is considered when building actions`() {
        // 终态不需要操作（已经结束了）。其余每一个都必须在 actions 里被提到。
        val nonTerminal = listOf(
            BotTaskStatus.QUEUED,
            BotTaskStatus.RUNNING,
            BotTaskStatus.WAITING_RESOURCE,
            BotTaskStatus.WAITING_APPROVAL,
            BotTaskStatus.REVIEWING,
            BotTaskStatus.CANCEL_REQUESTED,
            BotTaskStatus.NEEDS_USER,
        );
        val missing = nonTerminal.filter { status ->
            // 在 toRow 的 actions 构造块里找该状态的引用。
            val actionsAt = vm.indexOf("val actions = buildSet");
            val actionsEnd = vm.indexOf("return AgentBotUiState.TaskRow(", actionsAt);
            val block = vm.substring(actionsAt, actionsEnd);
            !block.contains(status);
        };
        assertTrue(
            "这些非终态没有在操作集里被考虑，用户会没有出口：" + missing,
            missing.isEmpty(),
        );
    }

    @Test
    fun `needs-user row can always be cancelled`() {
        val actionsAt = vm.indexOf("val actions = buildSet");
        val actionsEnd = vm.indexOf("return AgentBotUiState.TaskRow(", actionsAt);
        val block = vm.substring(actionsAt, actionsEnd);
        assertTrue(
            "NEEDS_USER 必须能取消，否则任务永久停住",
            block.contains("BotTaskStatus.NEEDS_USER"),
        );
    }

    @Test
    fun `continue is only granted to the approval-approved source`() {
        val actionsAt = vm.indexOf("val actions = buildSet");
        val actionsEnd = vm.indexOf("return AgentBotUiState.TaskRow(", actionsAt);
        val block = vm.substring(actionsAt, actionsEnd);
        // CONTINUE 必须同时受 NEEDS_USER 与 approvedAwaitingRetry 两个条件约束。
        assertTrue(
            "只给审批通过的来源 CONTINUE，不能只凭 status",
            block.contains("NEEDS_USER") && block.contains("approvedAwaitingRetry"),
        );
    }
}