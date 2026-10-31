package io.zer0.muse.web

import io.zer0.muse.data.knowledge.KnowledgeBaseDao
import io.zer0.muse.data.knowledge.KnowledgeChunkDao
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.rag.EmbeddingService

/**
 * 知识只读命令(`knowledge.list` / `knowledge.documents`)的载荷投影。
 *
 * 两件事:
 *  - 把仓储行折成协议 DTO(空库折成空数组,库不存在折成带 error 的事件,不抛异常);
 *  - 分页口径与次数夹取([pageLimit]),与 `memory.list` 一样由命令层先夹再查。
 *
 * ## 分页口径
 *
 * 文档按「最近更新在前」排(`updated_at DESC, id DESC`),`cursor` 取上一页最后一条的 id,
 * 下一页只取排在它后面的行。keyset 而不是 offset:导入新文档只会插到第一页之前,
 * 不会把后面几页挤得重复或跳条。
 */
internal object HostKnowledgeReads {

    const val TYPE_LIST = "knowledge.list"
    const val TYPE_DOCUMENTS = "knowledge.documents"

    /** 单页条数默认值。 */
    const val DEFAULT_LIMIT = 20

    /** 单页条数上限:网页端一屏放不下更多,再大的页只会把回执撑大。 */
    const val MAX_LIMIT = 50

    /** 知识库不存在时的 error 值(`knowledge.documents` 的 kbId 没命中)。 */
    const val ERROR_KB_NOT_FOUND = "knowledge_base_not_found"

    /** 一页文档 + 是否还有下一页。 */
    data class DocumentPage(val documents: List<HostKnowledgeDocument>, val hasMore: Boolean)

    /** `knowledge.list` 回执。空库下发空数组,不是错误。 */
    fun listEvent(bases: List<HostKnowledgeBase>, requestId: String?): HostEvent = HostEvent(
        type = TYPE_LIST,
        requestId = requestId,
        knowledgeBases = bases,
    )

    /**
     * `knowledge.documents` 回执。
     *
     * [page] 为 null 表示目标知识库不存在 —— 回 [ERROR_KB_NOT_FOUND] 而不是空页,
     * 否则网页端会把「库被删了」显示成「库里没文档」。
     */
    fun documentsEvent(kbId: String, page: DocumentPage?, requestId: String?): HostEvent {
        if (page == null) {
            return HostEvent(type = TYPE_DOCUMENTS, requestId = requestId, kbId = kbId, error = ERROR_KB_NOT_FOUND)
        }
        return HostEvent(
            type = TYPE_DOCUMENTS,
            requestId = requestId,
            kbId = kbId,
            documents = page.documents,
            hasMore = page.hasMore,
        )
    }

    /** 请求的每页条数夹到 `1..`[MAX_LIMIT];不传按 [DEFAULT_LIMIT]。 */
    fun pageLimit(requested: Int?): Int = (requested ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

    /** 文档索引状态。[embeddingModel] 命中索引失败哨兵值时优先判为需重建。 */
    fun statusOf(chunkCount: Int, embeddingModel: String): String = when {
        embeddingModel == KnowledgeDocDao.INDEX_BROKEN_SENTINEL -> HostDocumentStatus.INDEX_BROKEN
        chunkCount > 0 -> HostDocumentStatus.INDEXED
        else -> HostDocumentStatus.PENDING
    }
}

/**
 * 知识只读命令的数据源。
 *
 * 抽成接口是为了让网关在不接知识库(未启用 RAG、纯聊天场景)时也能照常回执;
 * 生产装配见 [DaoHostKnowledgeSource]。
 */
internal interface HostKnowledgeSource {

    /** 知识库一览,按最近更新在前。 */
    suspend fun bases(): List<HostKnowledgeBase>

    /**
     * 某知识库下的一页文档。
     *
     * @return null 表示 [kbId] 对应的知识库不存在
     */
    suspend fun documents(kbId: String, cursor: String?, limit: Int): HostKnowledgeReads.DocumentPage?

    /** 未装配时的空实现:没有库。 */
    object Empty : HostKnowledgeSource {
        override suspend fun bases(): List<HostKnowledgeBase> = emptyList()

        override suspend fun documents(kbId: String, cursor: String?, limit: Int): HostKnowledgeReads.DocumentPage? = null
    }
}

/**
 * 直接读知识库三张表的数据源(与 App 知识库页同一个库、同一套过滤口径)。
 *
 * 计数都不读 `knowledge_bases.doc_count` 那种写入列,而是现算:
 * 文档数由 [KnowledgeBaseDao.getAll] 的子查询给出,分块数由 [KnowledgeChunkDao.countByKb] 聚合。
 */
internal class DaoHostKnowledgeSource(
    private val baseDao: KnowledgeBaseDao,
    private val docDao: KnowledgeDocDao,
    private val chunkDao: KnowledgeChunkDao,
    private val embeddingService: EmbeddingService,
) : HostKnowledgeSource {

    override suspend fun bases(): List<HostKnowledgeBase> {
        val backend = embeddingBackend()
        val chunkCounts = chunkDao.countByKb().associate { it.kbId to it.chunkCount }
        return baseDao.getAll().map { base ->
            HostKnowledgeBase(
                id = base.id,
                name = base.name,
                documentCount = base.docCount,
                chunkCount = chunkCounts[base.id] ?: 0,
                createdAt = base.createdAt,
                updatedAt = base.updatedAt,
                embeddingBackend = backend,
            )
        }
    }

    override suspend fun documents(kbId: String, cursor: String?, limit: Int): HostKnowledgeReads.DocumentPage? {
        // 先确认库存在:不存在的 kbId 与「空库」必须给出不同回执。
        if (baseDao.getById(kbId) == null) return null
        // 多取一条只为判断 hasMore,回执里不带。
        val rows = docDao.getPageByKb(kbId = kbId, cursor = cursor, limit = limit + 1)
        return HostKnowledgeReads.DocumentPage(
            documents = rows.take(limit).map { row ->
                HostKnowledgeDocument(
                    id = row.id,
                    title = row.title,
                    chunkCount = row.chunkCount,
                    sizeBytes = row.sizeBytes,
                    createdAt = row.createdAt,
                    status = HostKnowledgeReads.statusOf(row.chunkCount, row.embeddingModel),
                )
            },
            hasMore = rows.size > limit,
        )
    }

    /**
     * 当前实际生效的 embedding 后端名。
     *
     * 取 [EmbeddingService.activeBackend] 这份运行时快照(本次进程真正建过 Provider 才会填),
     * 而不是配置里的 `embeddingSource` —— 降级到本地关键词时两者不同,这里要的是实际在跑的那个。
     * 进程内还没解析过后端时返回 null,回执里该字段留空。
     */
    private fun embeddingBackend(): String? =
        embeddingService.activeBackend.value
            ?.let { state -> state.modelName.ifBlank { state.backendId } }
            ?.takeIf { it.isNotBlank() }
}
