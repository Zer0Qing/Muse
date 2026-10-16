@file:Suppress("TooManyFunctions")

package io.zer0.muse.ui.memory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.memory.ai.MemoryLinkDao
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
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
    fun deleteNode(factId: Long, scope: String?, spaceId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val store = storeForScope(scope)
                if (scope == null) {
                    store.delete(factId)
                } else {
                    store.delete(factId, store.assistantId, scope, spaceId)
                }
            }
            load(scope, spaceId)
        }
    }

    fun editNode(factId: Long, newContent: String, scope: String?, spaceId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { storeForScope(scope).update(factId, newContent, scope) }
            load(scope, spaceId)
        }
    }

    fun togglePin(factId: Long, scope: String? = null, spaceId: String = "default") {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val scopedStore = storeForScope(scope)
                val fact = scopedStore.getById(factId) ?: return@withContext
                // v2.6.2（记忆审计）: 按当前 scope/Space 路由 —— 否则星座页展示子助手/
                // 其他 Space 的节点时，置顶会改到默认库中同 ID 的事实。
                if (fact.scope != (scope ?: "main") || fact.spaceId != spaceId) return@withContext
                scopedStore.setPinned(factId, fact.pinnedAt == null)
            }
            _state.value = _state.value.copy(
                nodes = _state.value.nodes.map { if (it.factId == factId) it.copy(isPinned = !it.isPinned) else it },
            )
        }
    }

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
    fun addManualEdge(
        sourceFactId: Long,
        targetFactId: Long,
        relationType: String,
        scope: String?,
        spaceId: String,
    ) {
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
