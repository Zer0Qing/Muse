package io.zer0.muse.ui.workflow

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.tools.DelegationContract
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.UUID

/**
 * Multi-Agent 工作流可视化编排 ViewModel。
 *
 * 维护两类状态:
 *  1. [workflow]: [DelegationContract.TeamWorkflow](节点列表 + 聚合策略),业务模型,
 *     最终由 TeamWorkflowExecutor 消费。
 *  2. [nodePositions]: 节点在画布上的二维位置 [Offset],纯 UI 状态,不影响执行语义。
 *
 * 持久化策略:
 *  - 工作流本体写入应用私有目录 `filesDir/workflows/{teamId}.json`,
 *    同时在 [save] 时回写到对应 [io.zer0.muse.data.AgentTeam.workflow](若 teamId 命中已有团队),
 *    使 TeamWorkflowExecutor 能直接消费。
 *  - 节点位置写入 SharedPreferences(`workflow_editor_prefs`),key 形如 `positions_{teamId}`,
 *    value 为 JSON 字符串,避免污染业务模型。
 *
 * @param application 用于获取 [Context.getFilesDir] 与 SharedPreferences
 * @param teamId 关联的团队 id;为空时使用 "default" 作为存储键,作为独立工作流编辑
 * @param settingsRepository 用于回写到 AgentTeam
 * @param assistantRepository 用于暴露助手列表(节点编辑面板下拉选择)
 */
class WorkflowEditorViewModel(
    private val application: Application,
    private val teamId: String,
    private val settingsRepository: SettingsRepository,
    private val assistantRepository: AssistantRepository,
) : ViewModel() {

    /** UI 状态聚合。 */
    data class UiState(
        /** 当前工作流定义(节点 + 聚合策略)。 */
        val workflow: DelegationContract.TeamWorkflow = DelegationContract.TeamWorkflow(),
        /** 节点 id → 画布坐标(原点在画布左上,单位 px)。 */
        val nodePositions: Map<String, Offset> = emptyMap(),
        /** 当前选中节点 id,null 表示未选中。 */
        val selectedNodeId: String? = null,
        /** 助手列表(节点编辑面板下拉用)。 */
        val assistants: List<AssistantEntity> = emptyList(),
        /** 顶部提示消息(瞬时,UI 自行消费后清空)。 */
        val toast: String? = null,
        /** 是否正在加载。 */
        val loading: Boolean = true,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** SharedPreferences 文件名,存放节点画布位置。 */
    private val prefs: android.content.SharedPreferences =
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 实际存储 key 使用的 teamId(空串降级为 "default")。 */
    private val storageKey: String = teamId.ifBlank { "default" }

    /** 工作流 JSON 文件。 */
    private val workflowFile: File =
        File(File(application.filesDir, WORKFLOW_DIR).apply { mkdirs() }, "$storageKey.json")

    init {
        // 启动时加载工作流 + 节点位置 + 助手列表
        load()
        observeAssistants()
    }

    /** 订阅助手列表,实时同步到 UI 状态。 */
    private fun observeAssistants() {
        viewModelScope.launch {
            assistantRepository.observeAll.collect { list ->
                _state.update { it.copy(assistants = list) }
            }
        }
    }

    // ---------------------------------------------------------------------
    // 节点操作
    // ---------------------------------------------------------------------

    /**
     * 添加新节点。
     *
     * @param position 新节点在画布上的初始位置;为 null 时自动错位排列,避免重叠
     * @param preset 预设节点字段(可选,复制/导入场景使用)
     */
    fun addNode(
        position: Offset? = null,
        preset: DelegationContract.TeamWorkflowNode? = null,
    ) {
        val nodeId = preset?.id?.takeIf { it.isNotBlank() } ?: "node-${UUID.randomUUID().toString().take(8)}"
        val node = (preset ?: DelegationContract.TeamWorkflowNode(
            id = nodeId,
            assistantId = "",
            name = application.getString(R.string.workflow_node_default_name),
            taskTemplate = "",
        )).copy(id = nodeId)

        // 计算新节点位置:优先用传入坐标,否则按现有节点数错位
        val pos = position ?: run {
            val count = _state.value.workflow.nodes.size
            Offset(
                x = 80f + (count % 4) * 220f,
                y = 120f + (count / 4) * 180f,
            )
        }

        _state.update { s ->
            s.copy(
                workflow = s.workflow.copy(nodes = s.workflow.nodes + node),
                nodePositions = s.nodePositions + (nodeId to pos),
                selectedNodeId = nodeId,
            )
        }
    }

    /** 删除节点,同时清除其它节点对它的依赖引用。 */
    fun removeNode(id: String) {
        _state.update { s ->
            s.copy(
                workflow = s.workflow.copy(
                    nodes = s.workflow.nodes
                        .filterNot { it.id == id }
                        .map { it.copy(dependsOn = it.dependsOn.filterNot { d -> d == id }) },
                ),
                nodePositions = s.nodePositions - id,
                selectedNodeId = if (s.selectedNodeId == id) null else s.selectedNodeId,
            )
        }
    }

    /** 更新节点字段(以 id 匹配)。 */
    fun updateNode(node: DelegationContract.TeamWorkflowNode) {
        _state.update { s ->
            s.copy(
                workflow = s.workflow.copy(
                    nodes = s.workflow.nodes.map { if (it.id == node.id) node else it },
                ),
            )
        }
    }

    /** 复制节点(含位置偏移,dependsOn 清空避免悬空引用)。 */
    fun duplicateNode(id: String) {
        val source = _state.value.workflow.nodes.firstOrNull { it.id == id } ?: return
        val sourcePos = _state.value.nodePositions[id] ?: Offset(0f, 0f)
        val newId = "node-${UUID.randomUUID().toString().take(8)}"
        val copy = source.copy(
            id = newId,
            name = source.name.ifBlank { "节点" } + " 副本",
            dependsOn = emptyList(),
        )
        _state.update { s ->
            s.copy(
                workflow = s.workflow.copy(nodes = s.workflow.nodes + copy),
                nodePositions = s.nodePositions + (newId to Offset(sourcePos.x + 40f, sourcePos.y + 40f)),
                selectedNodeId = newId,
            )
        }
    }

    // ---------------------------------------------------------------------
    // 依赖/连线操作
    // ---------------------------------------------------------------------

    /**
     * 建立依赖:from → to,表示 to 依赖 from(from 先执行)。
     *
     * 校验:禁止自环、禁止重复、禁止成环(简单 DFS 检测)。
     */
    fun addDependency(from: String, to: String) {
        if (from == to) return
        val nodes = _state.value.workflow.nodes
        if (nodes.none { it.id == from } || nodes.none { it.id == to }) return
        // 已存在则跳过
        val target = nodes.first { it.id == to }
        if (from in target.dependsOn) return
        // 成环检测:若 to 已经能到达 from,则再加 to→from 会成环
        if (canReach(to, from, nodes)) {
            _state.update { it.copy(toast = "禁止添加该连线:会形成循环依赖") }
            return
        }
        _state.update { s ->
            s.copy(
                workflow = s.workflow.copy(
                    nodes = s.workflow.nodes.map { n ->
                        if (n.id == to) n.copy(dependsOn = n.dependsOn + from) else n
                    },
                ),
            )
        }
    }

    /** 移除依赖:from → to。 */
    fun removeDependency(from: String, to: String) {
        _state.update { s ->
            s.copy(
                workflow = s.workflow.copy(
                    nodes = s.workflow.nodes.map { n ->
                        if (n.id == to) n.copy(dependsOn = n.dependsOn.filterNot { it == from }) else n
                    },
                ),
            )
        }
    }

    /**
     * DFS 判断 [from] 在当前依赖图上能否到达 [to]。
     * 用于成环检测:若 to→...→from 已存在,则 from→to 会成环。
     */
    private fun canReach(from: String, to: String, nodes: List<DelegationContract.TeamWorkflowNode>): Boolean {
        if (from == to) return true
        val visited = mutableSetOf<String>()
        val stack = ArrayDeque<String>()
        stack.addLast(from)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            if (cur == to) return true
            if (!visited.add(cur)) continue
            // cur 依赖的节点 = cur 的 dependsOn;反向:谁依赖 cur?
            // 这里查 from 能否到 to:沿着"被依赖"方向走,即 cur 的 dependsOn 是 cur 等待的节点
            nodes.firstOrNull { it.id == cur }?.dependsOn?.forEach { next ->
                if (next !in visited) stack.addLast(next)
            }
        }
        return false
    }

    // ---------------------------------------------------------------------
    // 位置/选中
    // ---------------------------------------------------------------------

    /** 移动节点到新位置(画布坐标系,px)。 */
    fun moveNode(id: String, offset: Offset) {
        _state.update { s ->
            if (id !in s.nodePositions) return@update s
            s.copy(nodePositions = s.nodePositions + (id to offset))
        }
    }

    /** 选中节点(null 取消选中)。 */
    fun selectNode(id: String?) {
        _state.update { it.copy(selectedNodeId = id) }
    }

    /** 更新聚合策略。 */
    fun updateAggregationStrategy(strategy: DelegationContract.TeamWorkflow.AggregationStrategy) {
        _state.update { it.copy(workflow = it.workflow.copy(aggregationStrategy = strategy)) }
    }

    /** 清空画布上所有节点与位置。 */
    fun clear() {
        _state.update { it.copy(workflow = DelegationContract.TeamWorkflow(), nodePositions = emptyMap(), selectedNodeId = null) }
    }

    /** 消费一次性 toast。 */
    fun consumeToast() {
        _state.update { it.copy(toast = null) }
    }

    // ---------------------------------------------------------------------
    // 持久化
    // ---------------------------------------------------------------------

    /**
     * 加载工作流与节点位置。
     *
     * 优先级:本地 JSON 文件 > AgentTeam.workflow > 空工作流。
     */
    fun load() {
        viewModelScope.launch {
            val workflow = loadWorkflowFromFile()
            val positions = loadPositionsFromPrefs()
            _state.update { it.copy(workflow = workflow, nodePositions = positions, loading = false) }
        }
    }

    /**
     * 保存工作流到本地 JSON 文件,并回写到对应 AgentTeam(若存在)。
     * 节点位置同步写入 SharedPreferences。
     */
    fun save() {
        viewModelScope.launch {
            val current = _state.value
            runCatching {
                // 1. 写工作流 JSON 文件
                workflowFile.parentFile?.mkdirs()
                workflowFile.writeText(exportJson())
                // 2. 写节点位置到 SharedPreferences
                savePositionsToPrefs(current.nodePositions)
                // 3. 回写到 AgentTeam(若 teamId 命中已有团队)
                if (teamId.isNotBlank()) {
                    settingsRepository.updateMultiAgentConfig { config ->
                        config.copy(
                            teams = config.teams.map { team ->
                                if (team.id == teamId) team.copy(workflow = current.workflow, updatedAt = System.currentTimeMillis()) else team
                            },
                        )
                    }
                }
                _state.update { it.copy(toast = "已保存") }
            }.onFailure { e ->
                Logger.w(TAG, "保存工作流失败", e)
                _state.update { it.copy(toast = "保存失败: ${e.message}") }
            }
        }
    }

    /** 导出工作流为 JSON 字符串(仅业务模型,不含画布位置)。 */
    fun exportJson(): String {
        return AppJson.encodeToString(
            DelegationContract.TeamWorkflow.serializer(),
            _state.value.workflow,
        )
    }

    /**
     * 从 JSON 字符串导入工作流(覆盖当前)。
     *
     * @return 成功与否;失败时通过 toast 提示
     */
    fun importJson(json: String) {
        runCatching {
            val parsed = AppJson.decodeFromString(DelegationContract.TeamWorkflow.serializer(), json)
            // 为缺失位置的节点补默认坐标
            val existing = _state.value.nodePositions
            val newPositions = parsed.nodes.mapIndexed { idx, node ->
                node.id to (existing[node.id] ?: Offset(
                    x = 80f + (idx % 4) * 220f,
                    y = 120f + (idx / 4) * 180f,
                ))
            }.toMap()
            _state.update {
                it.copy(
                    workflow = parsed,
                    nodePositions = newPositions,
                    selectedNodeId = null,
                    toast = "导入成功",
                )
            }
        }.onFailure { e ->
            Logger.w(TAG, "导入工作流失败", e)
            _state.update { it.copy(toast = "导入失败: ${e.message}") }
        }
    }

    // ---------------------------------------------------------------------
    // 内部:文件 / SP 读写
    // ---------------------------------------------------------------------

    /** 从本地 JSON 文件加载工作流;失败时尝试从 AgentTeam 读取,再失败返回空工作流。 */
    private suspend fun loadWorkflowFromFile(): DelegationContract.TeamWorkflow {
        // 1. 本地文件
        runCatching {
            if (workflowFile.exists()) {
                return AppJson.decodeFromString(
                    DelegationContract.TeamWorkflow.serializer(),
                    workflowFile.readText(),
                )
            }
        }.onFailure { Logger.w(TAG, "读取工作流文件失败", it) }
        // 2. AgentTeam
        if (teamId.isNotBlank()) {
            val team = settingsRepository.multiAgentConfigCache.teams.firstOrNull { it.id == teamId }
            team?.workflow?.let { return it }
        }
        // 3. 空
        return DelegationContract.TeamWorkflow()
    }

    /** 从 SharedPreferences 读取节点位置 Map。 */
    private fun loadPositionsFromPrefs(): Map<String, Offset> {
        val raw = prefs.getString(positionsKey(storageKey), null) ?: return emptyMap()
        return runCatching {
            val map = AppJson.decodeFromString(positionsSerializer, raw)
            map.mapValues { Offset(it.value.x, it.value.y) }
        }.onFailure { Logger.w(TAG, "读取节点位置失败", it) }.getOrDefault(emptyMap())
    }

    /** 把节点位置 Map 写入 SharedPreferences。 */
    private fun savePositionsToPrefs(positions: Map<String, Offset>) {
        val serializable = positions.mapValues { SerializableOffset(it.value.x, it.value.y) }
        val raw = AppJson.encodeToString(positionsSerializer, serializable)
        prefs.edit().putString(positionsKey(storageKey), raw).apply()
    }

    /** 可序列化的二维坐标(Offset 本身不可序列化)。 */
    @Serializable
    private data class SerializableOffset(val x: Float, val y: Float)

    companion object {
        private const val TAG = "WorkflowEditorVM"
        private const val PREFS_NAME = "workflow_editor_prefs"
        private const val WORKFLOW_DIR = "workflows"

        /** Map<String, SerializableOffset> 的序列化器。 */
        private val positionsSerializer =
            MapSerializer(String.serializer(), kotlinx.serialization.serializer<SerializableOffset>())

        private fun positionsKey(teamId: String) = "positions_$teamId"
    }
}
