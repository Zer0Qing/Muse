package io.zer0.muse.knowledge

import io.zer0.muse.knowledge.retrieval.MultiSpaceCoordinator
import io.zer0.muse.knowledge.retrieval.ScoredChunk
import io.zer0.muse.knowledge.retrieval.SpaceOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R5 步骤 7(计划 R5:「多库按 space 分组，分别编码与召回，最后融合排名；
 * 一个空间不可用时报告 Partial 与缺失空间，不影响其它授权词法结果」)。
 */
class KnowledgeMultiSpaceTest {

    @Test
    fun `merge fuses by rank without comparing raw scores across spaces`() {
        val spaceA = SpaceOutcome.Found(
            listOf(
                ScoredChunk(spaceId = "space-a", chunkId = "a1", rawScore = 0.99f, rank = 0),
                ScoredChunk(spaceId = "space-a", chunkId = "a2", rawScore = 0.10f, rank = 1),
            ),
        )
        // space-b 的原始分数量纲完全不同(0.001 级),但排名第一仍是第一。
        val spaceB = SpaceOutcome.Found(
            listOf(
                ScoredChunk(spaceId = "space-b", chunkId = "b1", rawScore = 0.001f, rank = 0),
                ScoredChunk(spaceId = "space-b", chunkId = "b2", rawScore = 0.0009f, rank = 1),
            ),
        )

        val merged = MultiSpaceCoordinator.merge(mapOf("space-a" to spaceA, "space-b" to spaceB), finalK = 4)

        assertEquals(4, merged.merged.size)
        assertFalse("无失败空间", merged.partial)
        // 两空间的第一名应排在前两位(RRF 相同),不因原始分数差 900 倍而颠倒。
        assertEquals(setOf("a1", "b1"), merged.merged.take(2).map { it.chunkId }.toSet())
    }

    @Test
    fun `unavailable space reports partial with reason but keeps other results`() {
        val spaceA = SpaceOutcome.Found(
            listOf(ScoredChunk(spaceId = "space-a", chunkId = "a1", rawScore = 0.5f, rank = 0)),
        )
        val spaceB = SpaceOutcome.Unavailable("模型缺失")
        val spaceC = SpaceOutcome.Failed("超时")

        val merged = MultiSpaceCoordinator.merge(mapOf("space-a" to spaceA, "space-b" to spaceB, "space-c" to spaceC))

        assertTrue("有空间未完成必须标 Partial", merged.partial)
        assertEquals(2, merged.missingSpaces.size)
        assertEquals(setOf("space-b", "space-c"), merged.missingSpaces.map { it.spaceId }.toSet())
        assertEquals("其它空间结果不受影响", listOf("a1"), merged.merged.map { it.chunkId })
    }

    @Test
    fun `empty found outcome is not a failure`() {
        val empty = SpaceOutcome.Found(emptyList())
        val merged = MultiSpaceCoordinator.merge(mapOf("space-a" to empty))
        assertFalse("零命中不是失败", merged.partial)
        assertTrue(merged.merged.isEmpty())
    }

    @Test
    fun `finalK bounds merged output`() {
        val results = (0 until 50).map { ScoredChunk("s", "c$it", 1f, it) }
        val merged = MultiSpaceCoordinator.merge(mapOf("s" to SpaceOutcome.Found(results)), finalK = 10)
        assertEquals(10, merged.merged.size)
    }
}
