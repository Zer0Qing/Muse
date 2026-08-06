package io.zer0.muse.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R-TEST-15: 群聊轮转账本解析与辩论角色分配纯逻辑。
 */
class GroupChatSchedulerPureLogicTest {

    @Test
    fun `ledger member ids preserve rotation order`() {
        assertEquals(
            listOf("a", "b", "c"),
            GroupChatScheduler.parseLedgerMemberIds("""["a","b","c"]"""),
        )
    }

    @Test
    fun `blank or empty ledger returns null`() {
        assertNull(GroupChatScheduler.parseLedgerMemberIds(null))
        assertNull(GroupChatScheduler.parseLedgerMemberIds(""))
        assertNull(GroupChatScheduler.parseLedgerMemberIds("[]"))
    }

    @Test
    fun `invalid ledger json returns null`() {
        assertNull(GroupChatScheduler.parseLedgerMemberIds("not-json"))
    }

    @Test
    fun `debate roles cover two three and more members`() {
        assertEquals(listOf("提出方案", "质疑挑战"), GroupChatScheduler.generateDebateRoles(2))
        assertEquals(
            listOf("提出方案", "质疑挑战", "改进优化"),
            GroupChatScheduler.generateDebateRoles(3),
        )
        assertEquals(
            listOf("提出方案", "质疑挑战", "改进优化", "补充扩展", "提出方案"),
            GroupChatScheduler.generateDebateRoles(5),
        )
    }
}
