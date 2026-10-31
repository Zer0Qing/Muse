package io.zer0.muse.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆状态徽章的判定规则。
 *
 * 列表里「已合并 / 已出场 / 被取代」三条各自独立，一条记忆可能同时命中；
 * 判定写错只有两种代价：漏标（用户以为它还在用）和误标（用户以为它已经不用了），
 * 所以这里把三种来源和一次跨分库同号的陷阱都钉住。
 */
class MemoryFactStatusTest {

    private val noneSuperseded = emptySet<String>()

    private fun statusOf(
        scope: String? = "main",
        id: Long = 7L,
        mergedIntoId: Long? = null,
        suppressedAt: String? = null,
        supersededKeys: Set<String> = noneSuperseded,
    ): MemoryFactStatus = memoryFactStatusOf(
        scope = scope,
        id = id,
        mergedIntoId = mergedIntoId,
        suppressedAt = suppressedAt,
        supersededKeys = supersededKeys,
    )

    @Test
    fun healthyRowIsNotInactive() {
        val status = statusOf()

        assertFalse(status.merged)
        assertFalse(status.suppressed)
        assertFalse(status.superseded)
        assertFalse("一条正常记忆不该被标成不生效", status.isInactive)
    }

    @Test
    fun mergedIntoAnotherRowMarksMerged() {
        val status = statusOf(mergedIntoId = 42L)

        assertTrue(status.merged)
        assertFalse(status.suppressed)
        assertFalse(status.superseded)
        assertTrue(status.isInactive)
    }

    @Test
    fun suppressedAtMarksFadedOut() {
        val status = statusOf(suppressedAt = "2026-10-01T08:00:00Z")

        assertTrue(status.suppressed)
        assertFalse(status.merged)
        assertFalse(status.superseded)
        assertTrue(status.isInactive)
    }

    @Test
    fun beingPointedAtByAnotherRowMarksSuperseded() {
        val status = statusOf(supersededKeys = setOf(memoryFactKey("main", 7L)))

        assertTrue(status.superseded)
        assertTrue(status.isInactive)
    }

    @Test
    fun sameIdInAnotherScopeIsNotSuperseded() {
        // 主库与子助手分库的 fact.id 各自自增,7 号在两库里都存在。
        // 只有子助手库里那条被取代时,主库 7 号不能跟着被标成「被取代」。
        val keys = setOf(memoryFactKey("assistant-a", 7L))

        assertFalse("跨分库同号不能误判成被取代", statusOf(scope = "main", supersededKeys = keys).superseded)
        assertTrue(statusOf(scope = "assistant-a", supersededKeys = keys).superseded)
    }

    @Test
    fun threeStatesAreIndependentAndCanAllHit() {
        val status = statusOf(
            mergedIntoId = 42L,
            suppressedAt = "2026-10-01T08:00:00Z",
            supersededKeys = setOf(memoryFactKey("main", 7L)),
        )

        assertTrue(status.merged)
        assertTrue(status.suppressed)
        assertTrue(status.superseded)
        assertTrue(status.isInactive)
    }

    @Test
    fun memoryItemProjectsSameFlags() {
        val item = MemoryItem(
            id = "7",
            title = "喜欢咖啡",
            content = "喜欢咖啡",
            source = "Fact",
            scope = "main",
            mergedIntoId = 42L,
            suppressed = true,
            superseded = true,
        )

        assertEquals(MemoryFactStatus(merged = true, suppressed = true, superseded = true), item.status)
        assertTrue(item.status.isInactive)
    }

    @Test
    fun itemsWithoutFactStatusStayQuiet() {
        // Summary / Compile / Experience 层没有这三个概念,默认值不能凭空造出徽章。
        val item = MemoryItem(id = "s1", title = "会话摘要", content = "…", source = "Summary")

        assertFalse(item.status.isInactive)
        assertNull(item.mergedIntoId)
    }

    @Test
    fun supersedeKeyKeepsScopeApart() {
        assertEquals("main:7", memoryFactKey("main", 7L))
        assertEquals(":7", memoryFactKey(null, 7L))
        assertNotEquals(memoryFactKey("main", 7L), memoryFactKey("assistant-a", 7L))
    }
}
