package io.zer0.memory.ai

import io.zer0.memory.fact.FactStore
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.2（用户反馈）: 记忆星座补边构建器的回归测试。
 *
 * 用内存 fake 的 MemoryLinkDao 验证三层补边（实体键/同会话/同分类）确实产出边，
 * 且无向去重、幂等（重复跑不重复插入）。
 */
class MemoryEdgeBuilderTest {

    /** 记录插入的最小 fake DAO（只实现补边用到的两个方法）。 */
    private class FakeLinkDao : MemoryLinkDao {
        val inserted = mutableListOf<MemoryLinkEntity>()
        override suspend fun insert(link: MemoryLinkEntity): Long {
            inserted.add(link)
            return inserted.size.toLong()
        }

        override suspend fun insertBatch(links: List<MemoryLinkEntity>): List<Long> {
            inserted.addAll(links)
            return links.indices.map { it.toLong() }
        }

        override suspend fun listBySpaceAndScope(spaceId: String, scope: String): List<MemoryLinkEntity> =
            inserted.filter { it.spaceId == spaceId && it.scope == scope }

        override suspend fun listBySpace(spaceId: String): List<MemoryLinkEntity> = inserted.filter { it.spaceId == spaceId }
        override fun observeBySpaceAndScope(spaceId: String, scope: String): kotlinx.coroutines.flow.Flow<List<MemoryLinkEntity>> =
            kotlinx.coroutines.flow.flowOf(emptyList())
        override suspend fun listByFactId(factId: Long): List<MemoryLinkEntity> = emptyList()
        override suspend fun deleteByFactId(factId: Long): Int = 0
        override suspend fun deleteBySpaceId(spaceId: String): Int = 0
        override suspend fun migrateBySpaceId(sourceSpaceId: String, targetSpaceId: String): Int = 0
        override suspend fun countBySpaceAndScope(spaceId: String, scope: String): Int = 0
        override suspend fun deleteById(id: Long): Int = 0
        override suspend fun deleteAll(): Int = 0
        override suspend fun deleteOrphans(): Int = 0
    }

    @Suppress("LongParameterList")
    private fun fact(
        id: Long,
        text: String,
        entityKey: String? = null,
        sessionId: String? = null,
        category: String = "general",
        createdAt: String = "2026-10-16T10:00:00Z",
    ) = FactStore.Fact(
        id = id,
        fact = text,
        entityKey = entityKey,
        sessionId = sessionId,
        category = category,
        createdAt = createdAt,
    )

    @Test
    fun `same entity key produces edges`() = kotlinx.coroutines.runBlocking {
        val dao = FakeLinkDao()
        val builder = MemoryEdgeBuilder(dao)
        val facts = listOf(
            fact(1, "张三喜欢咖啡", entityKey = "张三"),
            fact(2, "张三住在北京", entityKey = "张三"),
        )
        val result = builder.buildEdges(newFacts = listOf(facts[0]), existingFacts = facts, scope = "main", spaceId = "default")
        assertTrue("同实体键应产生边", result.entityEdges >= 1)
        assertTrue(dao.inserted.isNotEmpty())
    }

    @Test
    fun `same session produces edges`() = kotlinx.coroutines.runBlocking {
        val dao = FakeLinkDao()
        val builder = MemoryEdgeBuilder(dao)
        val facts = listOf(
            fact(1, "今天开会讨论了预算", sessionId = "s1", category = "event"),
            fact(2, "预算定为十万", sessionId = "s1", category = "event"),
        )
        val result = builder.buildEdges(newFacts = listOf(facts[0]), existingFacts = facts, scope = "main", spaceId = "default")
        assertTrue("同会话应产生边", result.sessionEdges >= 1)
    }

    @Test
    fun `same category within window produces edges`() = kotlinx.coroutines.runBlocking {
        val dao = FakeLinkDao()
        val builder = MemoryEdgeBuilder(dao)
        val facts = listOf(
            fact(1, "买了个键盘", category = "shopping"),
            fact(2, "买了个鼠标", category = "shopping"),
        )
        val result = builder.buildEdges(newFacts = listOf(facts[0]), existingFacts = facts, scope = "main", spaceId = "default")
        assertTrue("同分类 + 时间接近应产生边", result.categoryEdges >= 1)
    }

    @Test
    fun `is idempotent`() = kotlinx.coroutines.runBlocking {
        val dao = FakeLinkDao()
        val builder = MemoryEdgeBuilder(dao)
        val facts = listOf(
            fact(1, "张三喜欢咖啡", entityKey = "张三"),
            fact(2, "张三住在北京", entityKey = "张三"),
        )
        builder.buildEdges(newFacts = listOf(facts[0]), existingFacts = facts, scope = "main", spaceId = "default")
        val afterFirst = dao.inserted.size
        // 第二次跑：已有边应被去重跳过
        builder.buildEdges(newFacts = listOf(facts[0]), existingFacts = facts, scope = "main", spaceId = "default")
        assertTrue("重复补边不应新增", dao.inserted.size == afterFirst)
    }

    @Test
    fun `no edges when single fact`() = kotlinx.coroutines.runBlocking {
        val dao = FakeLinkDao()
        val builder = MemoryEdgeBuilder(dao)
        val facts = listOf(fact(1, "唯一事实"))
        val result = builder.buildEdges(newFacts = facts, existingFacts = facts, scope = "main", spaceId = "default")
        assertTrue(result.total == 0)
    }

    @Test
    fun `different category far apart no edge`() = kotlinx.coroutines.runBlocking {
        val dao = FakeLinkDao()
        val builder = MemoryEdgeBuilder(dao)
        val facts = listOf(
            fact(1, "买了键盘", category = "shopping", createdAt = "2026-10-10T10:00:00Z"),
            fact(2, "开会讨论", category = "work", createdAt = "2026-10-16T10:00:00Z"),
        )
        val result = builder.buildEdges(newFacts = listOf(facts[0]), existingFacts = facts, scope = "main", spaceId = "default")
        assertTrue("不同分类 + 时间远不应产生边", result.total == 0)
    }
}
