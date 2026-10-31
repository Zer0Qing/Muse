package io.zer0.muse.web

import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.space.MemorySpaceEntity

/**
 * 记忆条目在协议层的中间形态:正文不截断、不分页,投影交给 [HostMemoryReads]。
 *
 * 单独一层是为了把"从哪个库读"和"怎么下发"分开:前者依赖 Room 与分库路由,只能在设备上跑;
 * 后者是纯函数,分页、截断、片段这些容易出错的规则可以脱离数据库直接验。
 */
internal data class HostMemoryRecord(
    val id: String,
    val content: String,
    val createdAt: String,
    /** 来源类别,取值见 [HostMemorySourceKind]。 */
    val source: String,
    val pinned: Boolean,
)

/**
 * 记忆只读数据源。
 *
 * 只开两条读路径:按作用域列举、关键词检索。实现方必须是 App 已有的存储能力 ——
 * 网页端不另建索引、不复制一份记忆表,否则两边口径迟早对不上(改了一条记忆,
 * 网页上还是旧的)。
 */
internal interface HostMemorySource {

    /** [assistantId] 对应作用域下的全部条目;顺序不限,分页在投影层做。 */
    suspend fun entries(assistantId: String?): List<HostMemoryRecord>

    /** 关键词检索,[limit] 为调用方给的上限。 */
    suspend fun search(query: String, assistantId: String?, limit: Int): List<HostMemoryRecord>

    /** 未装配数据源时的空实现:命令照常回执,只是没有任何条目。 */
    object Empty : HostMemorySource {
        override suspend fun entries(assistantId: String?): List<HostMemoryRecord> = emptyList()

        override suspend fun search(query: String, assistantId: String?, limit: Int): List<HostMemoryRecord> =
            emptyList()
    }
}

/**
 * 用 App 自己的事实存储实现的 [HostMemorySource]。
 *
 * 路由口径与记忆页一致:主助手(空 / `default`)读全局 `facts.db` + 作用域 `main`,
 * 子助手读 `facts_<id>.db` + 作用域为该助手 id —— 与写入侧([FactDbProvider])是同一本账,
 * 网页端看到的条目就是模型真正召回的那批。
 *
 * 检索直接走 [FactStore] 的全文检索入口,不在这里写第二套匹配逻辑(那套逻辑有自己的
 * 分词与过期过滤口径,复制一份就一定会跟 App 内的搜索结果不一致)。
 */
internal class FactHostMemorySource(
    private val factStore: FactStore,
    private val factDbProvider: FactDbProvider,
) : HostMemorySource {

    override suspend fun entries(assistantId: String?): List<HostMemoryRecord> =
        storeFor(assistantId).getAll(scope = scopeOf(assistantId)).map { it.toRecord() }

    override suspend fun search(query: String, assistantId: String?, limit: Int): List<HostMemoryRecord> {
        val scope = scopeOf(assistantId)
        val store = storeFor(assistantId)
        val hits = mutableListOf<FactStore.Fact>()
        for (spaceId in spacesOf(store, scope)) {
            hits += store.searchFullTextScoped(query, scope, spaceId, limit)
        }
        return hits.distinctBy { it.id }.sortedByDescending { it.id }.take(limit).map { it.toRecord() }
    }

    /**
     * 该作用域在库里出现过的记忆空间。
     *
     * 检索按 (scope, space) 走库里的分空间入口,所以先把空间列出来;库还空着时用默认空间
     * 兜底,保证空库也走同一条路径返回空列表,而不是另开一条"没有空间"的短路。
     */
    private suspend fun spacesOf(store: FactStore, scope: String): List<String> =
        store.listScopeSpacePairs()
            .filter { it.first == scope }
            .map { it.second }
            .ifEmpty { listOf(MemorySpaceEntity.DEFAULT_SPACE_ID) }

    private fun storeFor(assistantId: String?): FactStore {
        val id = assistantId?.trim().orEmpty()
        return if (id.isEmpty() || id == DEFAULT_ASSISTANT_ID) factStore else factDbProvider.getFactStore(id)
    }

    private fun scopeOf(assistantId: String?): String {
        val id = assistantId?.trim().orEmpty()
        return if (id.isEmpty() || id == DEFAULT_ASSISTANT_ID) MAIN_SCOPE else id
    }

    private fun FactStore.Fact.toRecord(): HostMemoryRecord = HostMemoryRecord(
        id = id.toString(),
        content = fact,
        createdAt = createdAt,
        source = HostMemoryReads.sourceKind(sessionId, source),
        pinned = !pinnedAt.isNullOrBlank(),
    )

    private companion object {
        /** 主助手 id:它的记忆落在全局 `facts.db`。 */
        const val DEFAULT_ASSISTANT_ID = "default"

        /** 主助手的作用域名,与写入侧一致。 */
        const val MAIN_SCOPE = "main"
    }
}
