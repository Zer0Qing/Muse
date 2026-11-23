package io.zer0.muse.data.agentbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务状态机的不变量。
 *
 * 这个类只测纯函数，不起 Room —— 状态转移规则是中枢最容易被"顺手改一下"
 * 的地方，而它的错误表现是**用户已经看到"已完成"，几秒后又变成"失败"**。
 */
class BotTaskStatusMachineTest {

    @Test
    fun `happy path runs queued to succeeded through reviewing`() {
        var s = BotTaskStatus.QUEUED
        s = requireNotNull(BotTaskStatusMachine.next(s, BotTaskStatusMachine.Event.START))
        assertEquals(BotTaskStatus.RUNNING, s)
        s = requireNotNull(BotTaskStatusMachine.next(s, BotTaskStatusMachine.Event.EXECUTE_DONE))
        assertEquals(BotTaskStatus.REVIEWING, s)
        s = requireNotNull(BotTaskStatusMachine.next(s, BotTaskStatusMachine.Event.REVIEW_ACCEPT))
        assertEquals(BotTaskStatus.SUCCEEDED, s)
    }

    /**
     * 终态不可被迟到事件改写。
     *
     * 这是中枢最要命的一条：后台任务跑完并汇报之后，一个迟到的
     * TaskFailed 事件如果还能把它改成 FAILED，用户会看到"做好了"又变"失败"。
     */
    @Test
    fun `terminal states ignore every event`() {
        // NEEDS_USER 不在这个列表里：它不是终态。
        // 它等用户点"继续"（USER_CONFIRMED_CONTINUE）回队列，或取消。
        val terminals = listOf(
            BotTaskStatus.SUCCEEDED,
            BotTaskStatus.PARTIAL,
            BotTaskStatus.FAILED,
            BotTaskStatus.CANCELLED,
            BotTaskStatus.UNKNOWN,
        )
        BotTaskStatusMachine.Event.entries.forEach { event ->
            terminals.forEach { terminal ->
                assertNull(
                    "终态 $terminal 不该被 $event 改写",
                    BotTaskStatusMachine.next(terminal, event),
                )
            }
        }
    }

    @Test
    fun `review rework returns to queue with same task`() {
        assertEquals(
            BotTaskStatus.QUEUED,
            BotTaskStatusMachine.next(BotTaskStatus.REVIEWING, BotTaskStatusMachine.Event.REVIEW_REWORK),
        )
    }

    @Test
    fun `resource wait goes back to queue not to running`() {
        // 资源阻塞解除后必须重新排队:直接跳 RUNNING 会绕过 worker 的
        // 租约与围栏校验,两个 worker 可能同时拿到同一任务。
        assertEquals(
            BotTaskStatus.QUEUED,
            BotTaskStatusMachine.next(
                BotTaskStatus.WAITING_RESOURCE,
                BotTaskStatusMachine.Event.RESUME_FROM_RESOURCE,
            ),
        )
    }

    @Test
    fun `approval resume goes to needs-user instead of queue`() {
        // 审批与资源不同：审批通过后重跑会重复已执行的步骤（含 NO_BLIND_REPLAY 的），
        // 所以必须停在 NEEDS_USER 等用户确认，不能自动回队列。
        assertEquals(
            BotTaskStatus.NEEDS_USER,
            BotTaskStatusMachine.next(
                BotTaskStatus.WAITING_APPROVAL,
                BotTaskStatusMachine.Event.RESUME_FROM_APPROVAL,
            ),
        )
    }

    @Test
    fun `denied expired and invalidated approvals return the task to needs-user`() {
        assertEquals(
            BotTaskStatus.NEEDS_USER,
            BotTaskStatusMachine.next(
                BotTaskStatus.WAITING_APPROVAL,
                BotTaskStatusMachine.Event.REVIEW_NEEDS_USER,
            ),
        )
    }

    @Test
    fun `cancel with side effects becomes partial not cancelled`() {
        assertEquals(
            BotTaskStatus.PARTIAL,
            BotTaskStatusMachine.next(BotTaskStatus.CANCEL_REQUESTED, BotTaskStatusMachine.Event.CANCEL_TO_PARTIAL),
        )
    }

    @Test
    fun `unknown is reachable from every non terminal running state`() {
        listOf(
            BotTaskStatus.RUNNING,
            BotTaskStatus.REVIEWING,
            BotTaskStatus.CANCEL_REQUESTED,
        ).forEach { state ->
            assertEquals(
                "$state 必须能标记为 UNKNOWN（副作用已发生但结果没写回）",
                BotTaskStatus.UNKNOWN,
                BotTaskStatusMachine.next(state, BotTaskStatusMachine.Event.MARK_UNKNOWN),
            )
        }
    }

    @Test
    fun `blocked states are the ones waiting on a human or a resource`() {
        assertTrue(BotTaskStatusMachine.isBlocked(BotTaskStatus.WAITING_APPROVAL))
        assertTrue(BotTaskStatusMachine.isBlocked(BotTaskStatus.WAITING_RESOURCE))
        assertTrue(BotTaskStatusMachine.isBlocked(BotTaskStatus.NEEDS_USER))
        assertFalse(BotTaskStatusMachine.isBlocked(BotTaskStatus.RUNNING))
        assertFalse(BotTaskStatusMachine.isBlocked(BotTaskStatus.QUEUED))
    }

    @Test
    fun `status constants are unique and cover the documented set`() {
        val all = listOf(
            BotTaskStatus.QUEUED, BotTaskStatus.RUNNING, BotTaskStatus.WAITING_RESOURCE,
            BotTaskStatus.WAITING_APPROVAL, BotTaskStatus.REVIEWING, BotTaskStatus.CANCEL_REQUESTED,
            BotTaskStatus.SUCCEEDED, BotTaskStatus.PARTIAL, BotTaskStatus.FAILED,
            BotTaskStatus.CANCELLED, BotTaskStatus.NEEDS_USER, BotTaskStatus.UNKNOWN,
        )
        assertEquals("状态常量不得重复", all.size, all.toSet().size)
        // 五个：SUCCEEDED / PARTIAL / FAILED / CANCELLED / UNKNOWN。
        // NEEDS_USER 曾被误列进来，但它有"继续"与"取消"两个出口，不是终态。
        assertEquals("终态集合必须是五个", 5, BotTaskStatus.TERMINAL.size)
        assertNotNull(BotTaskStatus.TERMINAL.firstOrNull())
    }
    @Test
    fun `approval resume goes to needs-user not straight back to queue`() {
        // 自动回 QUEUED 会让 tick 开新 attempt 从头跑 —— 已执行的步骤会再来一遍，
        // 包括标了 NO_BLIND_REPLAY 的。那是在用户不知情下重复副作用（违反 V23）。
        assertEquals(
            BotTaskStatus.NEEDS_USER,
            BotTaskStatusMachine.next(
                BotTaskStatus.WAITING_APPROVAL,
                BotTaskStatusMachine.Event.RESUME_FROM_APPROVAL,
            ),
        );
    }

    @Test
    fun `needs-user is not auto-resumed by anything`() {
        // 到了 NEEDS_USER 就该停在那儿等用户，不能被别的事件悄悄拉走。
        val status = BotTaskStatus.NEEDS_USER;
        assertNull(
            BotTaskStatusMachine.next(status, BotTaskStatusMachine.Event.START),
        );
        assertNull(
            BotTaskStatusMachine.next(status, BotTaskStatusMachine.Event.EXECUTE_DONE),
        );
    }
    @Test
    fun `user confirmation moves needs-user back to queue`() {
        assertEquals(
            BotTaskStatus.QUEUED,
            BotTaskStatusMachine.next(
                BotTaskStatus.NEEDS_USER,
                BotTaskStatusMachine.Event.USER_CONFIRMED_CONTINUE,
            ),
        );
    }

    @Test
    fun `needs-user can be cancelled`() {
        assertEquals(
            BotTaskStatus.CANCEL_REQUESTED,
            BotTaskStatusMachine.next(
                BotTaskStatus.NEEDS_USER,
                BotTaskStatusMachine.Event.CANCEL_REQUEST,
            ),
        );
    }
    @Test
    fun `needs-user is not in the terminal set`() {
        // 它有两个出口（继续 / 取消），列进 TERMINAL 会让那两个动作永远不生效。
        assertFalse(
            "NEEDS_USER 是阻塞态不是终态",
            BotTaskStatus.isTerminal(BotTaskStatus.NEEDS_USER),
        );
    }

    @Test
    fun `every terminal status rejects every event`() {
        // TERMINAL 集合与状态机的转移表必须一致：
        // 集合里有的，状态机必须全部返回 null；集合里没有的，至少有一个事件能走。
        val allStatuses = listOf(
            BotTaskStatus.QUEUED, BotTaskStatus.RUNNING, BotTaskStatus.WAITING_RESOURCE,
            BotTaskStatus.WAITING_APPROVAL, BotTaskStatus.REVIEWING, BotTaskStatus.CANCEL_REQUESTED,
            BotTaskStatus.SUCCEEDED, BotTaskStatus.PARTIAL, BotTaskStatus.FAILED,
            BotTaskStatus.CANCELLED, BotTaskStatus.NEEDS_USER, BotTaskStatus.UNKNOWN,
        );
        allStatuses.forEach { status ->
            val hasExit = BotTaskStatusMachine.Event.entries.any { event ->
                BotTaskStatusMachine.next(status, event) != null
            };
            val markedTerminal = BotTaskStatus.isTerminal(status);
            if (markedTerminal) {
                assertFalse(
                    "$status 被标为终态，却仍有事件能改写它",
                    hasExit,
                );
            }
        };
    }

    @Test
    fun `non terminal statuses all have at least one exit`() {
        val nonTerminal = listOf(
            BotTaskStatus.QUEUED, BotTaskStatus.RUNNING, BotTaskStatus.WAITING_RESOURCE,
            BotTaskStatus.WAITING_APPROVAL, BotTaskStatus.REVIEWING, BotTaskStatus.CANCEL_REQUESTED,
            BotTaskStatus.NEEDS_USER,
        );
        nonTerminal.forEach { status ->
            val hasExit = BotTaskStatusMachine.Event.entries.any { event ->
                BotTaskStatusMachine.next(status, event) != null
            };
            assertTrue("$status 没有出口，任务会永久停住", hasExit);
        };
    }
    @Test
    fun `needs-user branch is not shadowed by the terminal branch`() {
        // when 按顺序匹配。如果 NEEDS_USER 同时出现在终态分支和它自己的分支里，
        // 排在前面的那个生效 —— 用户点"继续"会静默无效。
        val src = java.io.File(
            "src/main/java/io/zer0/muse/data/agentbot/BotTaskStatusMachine.kt",
        ).readText()

        // 终态分支里不能有 NEEDS_USER。
        val terminalAt = src.indexOf("// 终态：任何事件都不再改写");
        assertTrue("终态分支必须存在", terminalAt > 0);
        // 切到 next() 函数结束，不要用固定偏移 —— 越界会把 isBlocked() 里的
        // NEEDS_USER 也扫进来，产生假失败。
        val fnEnd = src.indexOf("    /**", terminalAt);
        val terminalBlock = src.substring(terminalAt, if (fnEnd > terminalAt) fnEnd else src.length);
        // 只看代码行：注释里提到 NEEDS_USER 是在解释"为什么不在终态里"，
        // 不能把它当成违规。
        val codeLines = terminalBlock.lines().filterNot { it.trimStart().startsWith("//") };
        assertFalse(
            "NEEDS_USER 不能出现在终态分支里，那会遮蔽它自己的转移",
            codeLines.any { it.contains("BotTaskStatus.NEEDS_USER") },
        );

        // 但它必须有自己的分支。
        assertTrue(
            "NEEDS_USER 必须有独立的 when 分支",
            src.contains("BotTaskStatus.NEEDS_USER -> when (event)"),
        );
    }
}
