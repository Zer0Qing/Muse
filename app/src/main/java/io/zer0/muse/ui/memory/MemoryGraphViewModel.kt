@file:Suppress("TooManyFunctions")

package io.zer0.muse.ui.memory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.memory.ai.MemoryLinkDao
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.runtime.DeleteMemoryRequest
import io.zer0.memory.runtime.DeleteMemoryResult
import io.zer0.memory.runtime.FactPinResult
import io.zer0.memory.runtime.MemoryScopeKey
import io.zer0.memory.runtime.MemoryUseCases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 记忆星座 ViewModel。
 *
 * 阶段 2: 从 FactStore 和 MemoryLinkDao 读取真实记忆关系数据。
 * 阶段 3: 支持节点编辑、删除、置顶;关系确认、删除。
 * 阶段 4: 支持聚类候选生成和保存。
 */
class MemoryGraphViewModel(
    application: Application,
    private val factStore: FactStore,
    private val memoryLinkDao: MemoryLinkDao,
    private val factDbProvider: FactDbProvider? = null,
    /** v2.6.2（用户反馈）: 补边构建器（手动重建关联用）。 */
    private val edgeBuilder: io.zer0.memory.ai.MemoryEdgeBuilder? = null,
    /** v2.6.2: 语义近邻向量提供者。 */
    private val semanticProvider: io.zer0.memory.ai.MemoryEdgeBuilder.SemanticProvider? = null,
    /**
     * 统一记忆入口。置顶/删除都走它 —— 星座页的节点操作与记忆页必须同一套规则,
     * 否则同一条事实在两个页面上会有两种置顶状态(一个改了事实行、一个另写了一份副本)。
     * null = 未装配的测试宿主,退化为直接写事实行。
     */
    private val memoryUseCases: MemoryUseCases? = null,
) : AndroidViewModel(application) {

    data class GraphState(
        val nodes: List<MemoryGraphNode> = emptyList(),
        val edges: List<MemoryGraphEdge> = emptyList(),
        val clusters: List<MemoryGraphCluster> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
    )

    private fun storeForScope(scope: String?): FactStore = if (scope.isNullOrBlank() || scope == "main") {
        factStore
    } else {
        factDbProvider?.getFactStore(scope) ?: factStore
    }

    private fun linksForScope(scope: String?): MemoryLinkDao = if (scope.isNullOrBlank() || scope == "main") {
        memoryLinkDao
    } else {
        factDbProvider?.getFactDb(scope)?.memoryLinkDao() ?: memoryLinkDao
    }

    private val _state = MutableStateFlow(GraphState(isLoading = true))
    val state: StateFlow<GraphState> = _state.asStateFlow()

    fun load(scope: String?, spaceId: String) {
        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val scopedStore = storeForScope(scope)
                val scopedLinks = linksForScope(scope)
                val facts = withContext(Dispatchers.IO) {
                    if (scope == null) scopedStore.getBySpace(spaceId) else scopedStore.getByScopeAndSpace(scope, spaceId)
                }
                val links = withContext(Dispatchers.IO) {
                    if (scope == null) scopedLinks.listBySpace(spaceId) else scopedLinks.listBySpaceAndScope(spaceId, scope)
                }
                val factIdsInLinks = links.flatMapTo(mutableSetOf()) { setOf(it.sourceFactId, it.targetFactId) }
                // 普通事实也必须可见；关系/重要度/置顶只用于排序优先级。
                val nodeFacts = selectGraphFacts(facts, factIdsInLinks, MAX_NODES)
                val nodeIdSet = nodeFacts.map { it.id }.toSet()
                val nodeEdges = links.filter { it.sourceFactId in nodeIdSet && it.targetFactId in nodeIdSet }.take(MAX_EDGES)

                val nodes = nodeFacts.map { fact ->
                    MemoryGraphNode(
                        factId = fact.id,
                        title = fact.fact.take(NODE_TITLE_MAX_LENGTH),
                        category = fact.category,
                        importance = fact.importance,
                        confidence = fact.confidence,
                        isPinned = fact.pinnedAt != null,
                        isExpired = false,
                    )
                }
                val edges = nodeEdges.map { link ->
                    MemoryGraphEdge(
                        id = link.id,
                        sourceFactId = link.sourceFactId,
                        targetFactId = link.targetFactId,
                        relationType = link.linkType,
                        weight = link.weight,
                        sourceTitle = link.sourceTitle,
                        targetTitle = link.targetTitle,
                    )
                }
                // 阶段 4: 自动聚类(按 category 初步分组)
                val clusters = generateClusters(nodes)
                _state.value = GraphState(nodes = nodes, edges = edges, clusters = clusters, isLoading = false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = GraphState(isLoading = false, error = e.message ?: "load failed")
            }
        }
    }

    // ── 阶段 3: 节点操作 ──

    /**
     * 删除节点。
     *
     * 删除键一律取事实行自身的 scope/space:星座图在"全部作用域"视图下也会展示
     * 子助手作用域的行,拿界面上的 scope 猜归属会删到同号的其他行。
     * 只有确实删掉了才重载 —— 删除被拒(行已不在、跨 Space)时刷新只会让用户
     * 看到节点闪一下又回来。
     */
    fun deleteNode(factId: Long, scope: String?, spaceId: String) {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { deleteFactScoped(factId, scope, spaceId) }
            if (deleted) load(scope, spaceId)
        }
    }

    /** 按事实行自身的作用域键删除;false = 没删。 */
    private suspend fun deleteFactScoped(factId: Long, scope: String?, spaceId: String): Boolean {
        val store = storeForScope(scope)
        val fact = store.getById(factId) ?: return false
        if (fact.spaceId != spaceId) return false
        if (scope != null && fact.scope != scope) return false
        val unified = memoryUseCases
        if (unified != null) {
            val key = MemoryScopeKey(assistantIdForScope(fact.scope), fact.scope, fact.spaceId)
            when (unified.delete(DeleteMemoryRequest(key, factId))) {
                is DeleteMemoryResult.Deleted -> return true
                // 历史数据里子助手 scope 的行可能物理上就在默认库(分库迁移之前写入),
                // 按 scope 路由的分库找不到它。这种行本页确实读到了,就该能删。
                is DeleteMemoryResult.Missing -> Unit
                is DeleteMemoryResult.Rejected -> return false
            }
        }
        return store.delete(factId, store.assistantId, fact.scope, fact.spaceId)
    }

    /**
     * v2.6.11: 取事实全文。星座节点只带截断标题（[NODE_TITLE_MAX_LENGTH]），
     * 编辑前必须先拿全文，否则保存会把长事实永久写回成标题那几十个字。
     */
    fun loadFactContent(factId: Long, scope: String?, onLoaded: (String?) -> Unit) {
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) { storeForScope(scope).getById(factId)?.fact }
            onLoaded(content)
        }
    }

    fun editNode(factId: Long, newContent: String, scope: String?, spaceId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val store = storeForScope(scope)
                val current = store.getById(factId)?.fact
                // v2.6.11: 兜底护栏 —— 传入内容若正好是现存全文的截断标题，说明调用方
                // 拿的是显示用标题而不是全文，落库会把长事实截断，这里直接拒写。
                val looksTruncated =
                    current != null &&
                        current.length > NODE_TITLE_MAX_LENGTH &&
                        newContent == current.take(NODE_TITLE_MAX_LENGTH)
                if (looksTruncated) false else store.update(factId, newContent, scope)
            }
            if (saved) load(scope, spaceId)
            onResult(saved)
        }
    }

    fun togglePin(factId: Long, scope: String? = null, spaceId: String = "default") {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { toggleFactPin(factId, scope, spaceId) }
            // 只有确实改了事实行才动 UI —— 先翻图标再写库,失败时用户会以为已置顶。
            if (result is FactPinResult.Changed) {
                _state.value = _state.value.copy(
                    nodes = _state.value.nodes.map {
                        if (it.factId == factId) it.copy(isPinned = result.pinned) else it
                    },
                )
            }
        }
    }

    /**
     * 统一置顶切换:事实表 `pinned_at` 是唯一事实源。
     *
     * 行不属于当前 scope/Space 时直接拒绝 —— 同一 id 在别的库/别的 Space 下
     * 可能是另一条事实,拿裸 id 改置顶会改错行。
     */
    private suspend fun toggleFactPin(factId: Long, scope: String?, spaceId: String): FactPinResult {
        val store = storeForScope(scope)
        val fact = store.getById(factId) ?: return FactPinResult.Missing
        if (fact.scope != (scope ?: "main") || fact.spaceId != spaceId) {
            return FactPinResult.Rejected("memory belongs to another space")
        }
        val unified = memoryUseCases
        if (unified != null) {
            return unified.toggleFactPinned(
                MemoryScopeKey(assistantIdForScope(fact.scope), fact.scope, fact.spaceId),
                factId,
            )
        }
        val target = fact.pinnedAt == null
        if (!store.setPinned(factId, target, fact.scope, fact.spaceId)) return FactPinResult.Missing
        return FactPinResult.Changed(target)
    }

    /** 主助手在 assistantId 维度的名字是 "default",子助手用自己的 scope 名。 */
    private fun assistantIdForScope(scope: String): String = if (scope == "main") "default" else scope

    // ── 阶段 3: 关系操作 ──
    fun deleteEdge(edgeId: Long, scope: String?, spaceId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { linksForScope(scope).deleteById(edgeId) }
            load(scope, spaceId)
        }
    }

    /**
     * v2.6.2（用户反馈）: 手动建立一条边（用户在星座页点两个节点连起来）。
     *
     * @param sourceFactId 起点事实
     * @param targetFactId 终点事实
     * @param relationType 关系类型（related_to / causes / part_of / contradicts）
     */
    fun addManualEdge(sourceFactId: Long, targetFactId: Long, relationType: String, scope: String?, spaceId: String) {
        if (sourceFactId == targetFactId) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val scopedLinks = linksForScope(scope)
                // 无向去重：已有同对边时跳过
                val existing = scopedLinks.listBySpaceAndScope(spaceId, scope ?: "main")
                val dup = existing.any {
                    (it.sourceFactId == sourceFactId && it.targetFactId == targetFactId) ||
                        (it.sourceFactId == targetFactId && it.targetFactId == sourceFactId)
                }
                if (dup) return@withContext
                val scopedStore = storeForScope(scope)
                val src = scopedStore.getById(sourceFactId)
                val tgt = scopedStore.getById(targetFactId)
                scopedLinks.insert(
                    io.zer0.memory.ai.MemoryLinkEntity(
                        sourceFactId = sourceFactId,
                        targetFactId = targetFactId,
                        sourceTitle = src?.fact?.take(60) ?: sourceFactId.toString(),
                        targetTitle = tgt?.fact?.take(60) ?: targetFactId.toString(),
                        linkType = relationType,
                        weight = 0.7f,
                        spaceId = spaceId,
                        scope = scope ?: "main",
                        createdAt = java.time.Instant.now().toString(),
                    ),
                )
            }
            load(scope, spaceId)
        }
    }

    /**
     * v2.6.2（用户反馈）: 手动重建关联 —— 对当前 scope+space 的全部事实跑一次补边。
     * 供星座设置面板的“立即重建关联”按钮调用。
     */
    fun rebuildEdges(scope: String?, spaceId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val scopedStore = storeForScope(scope)
                val all = if (scope == null) scopedStore.getBySpace(spaceId) else scopedStore.getByScopeAndSpace(scope, spaceId)
                if (all.isEmpty()) return@withContext
                // 全部事实当作“新事实”跑一遍补边（已有边会去重跳过）。
                edgeBuilder?.buildEdges(
                    newFacts = all,
                    existingFacts = all,
                    scope = scope ?: "main",
                    spaceId = spaceId,
                    semanticProvider = semanticProvider,
                )
            }
            load(scope, spaceId)
        }
    }

    fun confirmEdge(edgeId: Long, scope: String? = null, spaceId: String = "default") {
        viewModelScope.launch {
            // 当前 MemoryLinkEntity 没有 userConfirmed 字段,暂时用 weight 提升模拟确认
            // v2.6.2（记忆审计）: 按当前 scope/Space 路由 —— 否则其他 Space 点击确认时
            // 找不到边，或 edgeId 相同就误改默认图谱。
            val scopedLinks = linksForScope(scope)
            val edges = scopedLinks.listBySpaceAndScope(spaceId, scope ?: "main")
            val edge = edges.find { it.id == edgeId } ?: return@launch
            val updated = edge.copy(weight = (edge.weight + 0.2f).coerceAtMost(1.0f))
            withContext(Dispatchers.IO) { scopedLinks.insert(updated) }
            // 局部刷新
            _state.value = _state.value.copy(
                edges = _state.value.edges.map { if (it.id == edgeId) it.copy(weight = updated.weight) else it },
            )
        }
    }

    // ── 阶段 4: 聚类 ──
    private fun generateClusters(nodes: List<MemoryGraphNode>): List<MemoryGraphCluster> {
        // 第一版:按 category 分组,同组 >=2 个节点才成 cluster
        return nodes.groupBy { it.category }
            .filter { it.value.size >= 2 }
            .map { (category, groupNodes) ->
                MemoryGraphCluster(
                    id = "auto_$category",
                    name = category,
                    nodeIds = groupNodes.map { it.factId }.toSet(),
                )
            }
    }

    companion object {
        private const val MAX_NODES = 500
        private const val MAX_EDGES = 1000
        private const val NODE_TITLE_MAX_LENGTH = 24
    }
}

data class MemoryGraphNode(
    val factId: Long,
    val title: String,
    val category: String,
    val importance: Int,
    val confidence: Float,
    val isPinned: Boolean,
    val isExpired: Boolean,
)

data class MemoryGraphEdge(
    val id: Long,
    val sourceFactId: Long,
    val targetFactId: Long,
    val relationType: String,
    val weight: Float,
    val sourceTitle: String,
    val targetTitle: String,
)

/**
 * 阶段 4: 记忆聚类(星座)。
 */
data class MemoryGraphCluster(
    val id: String,
    val name: String,
    val nodeIds: Set<Long>,
)
