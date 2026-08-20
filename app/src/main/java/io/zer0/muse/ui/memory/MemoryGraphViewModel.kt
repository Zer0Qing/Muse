package io.zer0.muse.ui.memory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.memory.ai.MemoryLinkDao
import io.zer0.memory.ai.MemoryLinkEntity
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
) : AndroidViewModel(application) {

    data class GraphState(
        val nodes: List<MemoryGraphNode> = emptyList(),
        val edges: List<MemoryGraphEdge> = emptyList(),
        val clusters: List<MemoryGraphCluster> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(GraphState(isLoading = true))
    val state: StateFlow<GraphState> = _state.asStateFlow()

    fun load(scope: String, spaceId: String) {
        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val facts = withContext(Dispatchers.IO) { factStore.getByScopeAndSpace(scope, spaceId) }
                val links = withContext(Dispatchers.IO) { memoryLinkDao.listBySpaceAndScope(spaceId, scope) }
                val factIdsInLinks = links.flatMapTo(mutableSetOf()) { setOf(it.sourceFactId, it.targetFactId) }
                val nodeFacts = facts.filter { it.id in factIdsInLinks || it.importance >= 1 || it.pinnedAt != null }.take(MAX_NODES)
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
    fun deleteNode(factId: Long, scope: String, spaceId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                memoryLinkDao.deleteByFactId(factId)
                factStore.delete(factId)
            }
            load(scope, spaceId)
        }
    }

    fun editNode(factId: Long, newContent: String, scope: String?, spaceId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { factStore.update(factId, newContent, scope) }
            load(scope ?: "main", spaceId)
        }
    }

    fun togglePin(factId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val fact = factStore.getById(factId) ?: return@withContext
                factStore.setPinned(factId, fact.pinnedAt == null)
            }
            _state.value = _state.value.copy(
                nodes = _state.value.nodes.map { if (it.factId == factId) it.copy(isPinned = !it.isPinned) else it }
            )
        }
    }

    // ── 阶段 3: 关系操作 ──
    fun deleteEdge(edgeId: Long, scope: String, spaceId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { memoryLinkDao.deleteById(edgeId) }
            load(scope, spaceId)
        }
    }

    fun confirmEdge(edgeId: Long) {
        viewModelScope.launch {
            // 当前 MemoryLinkEntity 没有 userConfirmed 字段,暂时用 weight 提升模拟确认
            val edges = memoryLinkDao.listBySpaceAndScope("default", "main")
            val edge = edges.find { it.id == edgeId } ?: return@launch
            val updated = edge.copy(weight = (edge.weight + 0.2f).coerceAtMost(1.0f))
            withContext(Dispatchers.IO) { memoryLinkDao.insert(updated) }
            // 局部刷新
            _state.value = _state.value.copy(
                edges = _state.value.edges.map { if (it.id == edgeId) it.copy(weight = updated.weight) else it }
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
        private const val MAX_NODES = 40
        private const val MAX_EDGES = 80
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
