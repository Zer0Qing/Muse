package io.zer0.muse.knowledge

import io.zer0.muse.knowledge.integration.KnowledgeReadMode
import io.zer0.muse.knowledge.integration.KnowledgeReadModeLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R9 读模式回退台账(计划 R9「验证同新 schema 的兼容读开关回退；
 * 不以旧 APK 覆盖安装充当数据库回退」)。
 */
class KnowledgeReadModeRollbackTest {

    private fun ledger() = KnowledgeReadModeLedger(clock = { 1000L })

    @Test
    fun `default is legacy and not rolled back`() {
        val l = ledger()
        assertEquals(KnowledgeReadMode.LEGACY, l.current)
        assertFalse(l.wasRolledBack)
        assertTrue(l.history.isEmpty())
    }

    @Test
    fun `switch to active records entry and marks activated`() {
        val l = ledger()
        val result = l.switchTo(KnowledgeReadMode.V2_ACTIVE, v2Available = true, cutoverApproved = true)
        assertEquals(KnowledgeReadMode.V2_ACTIVE, result)
        assertEquals(1, l.history.size)
        assertFalse("正常启用不是降级", l.history.single().downgrade)
        assertFalse("仍在 ACTIVE 不算回退", l.wasRolledBack)
    }

    @Test
    fun `rejected active request is recorded with reason`() {
        val l = ledger()
        val result = l.switchTo(KnowledgeReadMode.V2_ACTIVE, v2Available = true, cutoverApproved = false)
        assertEquals("未批准必须保持 LEGACY", KnowledgeReadMode.LEGACY, result)
        assertTrue("拒绝必须留痕", l.history.isNotEmpty())
        assertTrue(l.history.last().reason.contains("未获"))
    }

    @Test
    fun `rollback from active marks downgrade and wasRolledBack`() {
        val l = ledger()
        l.switchTo(KnowledgeReadMode.V2_ACTIVE, v2Available = true, cutoverApproved = true)
        val result = l.rollbackToLegacy("发现 V2 漏召回,人工回退")
        assertEquals(KnowledgeReadMode.LEGACY, result)
        assertTrue(l.wasRolledBack)
        val last = l.history.last()
        assertTrue("降级必须标记", last.downgrade)
        assertEquals(KnowledgeReadMode.V2_ACTIVE, last.from)
        assertEquals(KnowledgeReadMode.LEGACY, last.to)
        assertTrue(last.reason.contains("漏召回"))
    }

    @Test
    fun `rollback requires non blank reason`() {
        val l = ledger()
        l.switchTo(KnowledgeReadMode.V2_ACTIVE, v2Available = true, cutoverApproved = true)
        val failed = runCatching { l.rollbackToLegacy("  ") }.isFailure
        assertTrue("空原因必须拒绝", failed)
        assertEquals("拒绝后模式不变", KnowledgeReadMode.V2_ACTIVE, l.current)
    }

    @Test
    fun `same mode switch does not append history`() {
        val l = ledger()
        l.switchTo(KnowledgeReadMode.LEGACY, v2Available = true, cutoverApproved = true)
        assertEquals("相同模式不记账", 0, l.history.size)
    }

    @Test
    fun `ledger is bounded`() {
        val l = KnowledgeReadModeLedger(clock = { 1000L }, maxEntries = 3)
        repeat(10) { index ->
            if (index % 2 == 0) {
                l.switchTo(KnowledgeReadMode.V2_SHADOW, v2Available = true, cutoverApproved = false)
            } else {
                l.rollbackToLegacy("tick $index")
            }
        }
        assertTrue("台账必须有界", l.history.size <= 3)
    }
}
