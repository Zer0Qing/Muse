package io.zer0.memory.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MemoryRuntime 领域合同定点测试。
 *
 * 这些断言负责锁住所有调用方都依赖的同名类型和规范化语义。
 * 任何后续代理在实现后续任务时，不得另起一套 scope/revision 合同。
 */
class MemoryRuntimeContractsTest {

    @Test
    fun `normalized scope requires non empty assistant scope and space`() {
        val valid = MemoryScopeKey(assistantId = "a", scope = "main", spaceId = "default")
        assertTrue(valid.normalized() is MemoryScopeResult.Valid)

        val invalidAssistant = MemoryScopeKey(assistantId = " ", scope = "main", spaceId = "default")
        assertTrue(invalidAssistant.normalized() is MemoryScopeResult.Invalid)

        val invalidScope = MemoryScopeKey(assistantId = "a", scope = "", spaceId = "default")
        assertTrue(invalidScope.normalized() is MemoryScopeResult.Invalid)

        val invalidSpace = MemoryScopeKey(assistantId = "a", scope = "main", spaceId = "\t")
        assertTrue(invalidSpace.normalized() is MemoryScopeResult.Invalid)
    }

    @Test
    fun `normalized scope trims surrounding whitespace`() {
        val result = MemoryScopeKey(assistantId = " a ", scope = " main ", spaceId = " default ").normalized()
        assertTrue(result is MemoryScopeResult.Valid)
        val normalized = (result as MemoryScopeResult.Valid).value
        assertEquals("a", normalized.assistantId)
        assertEquals("main", normalized.scope)
        assertEquals("default", normalized.spaceId)
    }

    @Test
    fun `summary revision requires non negative revision`() {
        val ok = SummaryRevisionToken(
            sessionId = "s",
            revision = 0,
            messageCount = 1,
            sourceFingerprint = "f",
            assistantId = "a",
            spaceId = "default",
        )
        assertTrue(ok.isValid())

        val bad = ok.copy(revision = -1)
        assertFalse(bad.isValid())
    }

    @Test
    fun `fingerprint is stable for identical input`() {
        val token = SummaryRevisionToken(
            sessionId = "s",
            revision = 3,
            messageCount = 2,
            sourceFingerprint = "abc",
            assistantId = "a",
            spaceId = "default",
        )
        assertEquals(
            token.sourceFingerprint,
            SummaryRevisionToken(
                sessionId = "s",
                revision = 3,
                messageCount = 2,
                sourceFingerprint = "abc",
                assistantId = "a",
                spaceId = "default",
            ).sourceFingerprint,
        )
    }

    @Test
    fun `projection status enumerates expected states`() {
        val values = MemoryProjectionStatus.entries
        assertEquals(
            setOf(
                MemoryProjectionStatus.READY,
                MemoryProjectionStatus.STALE,
                MemoryProjectionStatus.FAILED,
                MemoryProjectionStatus.ORPHANED,
            ),
            values.toSet(),
        )
    }

    @Test
    fun `global pinned and session summary are opt in`() {
        val request = MemoryReadRequest(
            scope = MemoryScopeKey("assistant", "main", "default"),
            tokenBudget = 32,
        )
        assertFalse(request.includeGlobalPinned)
        assertFalse(request.includeSessionSummary)
        assertEquals(null, request.sessionId)
    }
}
