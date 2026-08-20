package io.zer0.muse.ui
import io.zer0.muse.data.experience.DEFAULT_EXPERIENCE_CATEGORY

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.muse.R
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.compile.MemoryCompiler
import io.zer0.memory.fact.FactStore
import io.zer0.memory.space.MemorySpaceEntity
import io.zer0.memory.space.MemorySpaceRepository
import io.zer0.memory.summary.SessionSummaryManager
import io.zer0.memory.ticker.MemoryTicker
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.experience.ExperienceEntity
import io.zer0.muse.data.experience.ExperienceRepository
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.theme.MuseDateFormats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.format.DateTimeFormatter

/**
 * 阶段 6: 记忆页 UI 数据模型。
 *
 * 4 层记忆系统(Fact / Summary / Compile / Deep)的统一 UI 投影:
 *  - Fact 层:元事实列表(可搜索)
 *  - Summary 层:会话滚动摘要列表
 *  - Compile 层:4 段编译产物(facts/today/week/longterm)
 *  - Deep 层:无独立存储,展示统计信息(由 Fact 总数 + 最近更新时间推断)
 */
data class MemoryUiState(
    val isLoading: Boolean = true,
    val query: String = "",
    val factItems: List<MemoryItem> = emptyList(),
    val summaryItems: List<MemoryItem> = emptyList(),
    val compileItems: List<MemoryItem> = emptyList(),
    val searchResults: List<MemoryItem> = emptyList(),
    val isSearching: Boolean = false,
    val factCount: Int = 0,
    val summaryCount: Int = 0,
    /** v2.0: 筛选条件(旧三态已废弃,改为更直观的分类筛选) */
    val categoryFilter: String? = null,
    val lastUpdatedAt: String? = null,
    /** 完整错误堆栈(供 UI 可滚动展示,方便用户复制给开发者定位问题)。 */
    val errorTrace: String? = null,
    /**
     * v0.51: 记忆 dashboard 概览字段。
     *
     *  - [compiledMarkdown]:最近编译的 memory.md 摘要(由 [MemoryTicker.readCompiledMemoryMarkdown]
     *    裁剪到 tokenBudget 后的完整 markdown 文本,供"AI 对你的理解"卡片折叠展示)。
     *  - [healthMap]:MemoryTicker 各步骤健康快照(rollingSummary/compileDaily/compileToday/
     *    rollDailyWindow/compileFacts/deepMemory),用于在 dashboard 展示记忆健康状态。
     *  - [lastCompileTime]:最近一次成功编译时间(从 [healthMap] 的 lastSuccessAt 取最大值)。
     */
    val compiledMarkdown: String = "",
    val healthMap: Map<String, MemoryTicker.StepHealth> = emptyMap(),
    val lastCompileTime: String? = null,
    /** v4: 同步状态文案(如"最近编译 5 分钟前,最近的对话可能还未进入记忆")。 */
    val syncStatus: String = "",
    /** v4: 同步状态是否处于"可能未进入记忆"的过期状态。 */
    val syncStale: Boolean = false,
    /** v5: 记忆统计字段。 */
    val totalFactCount: Int = 0,
    val weekNewCount: Int = 0,
    val monthNewCount: Int = 0,
    /** v5: 重要度分布 (0/1/2 的计数)。 */
    val importanceDistribution: Map<Int, Int> = emptyMap(),
    /** v5: 最活跃会话(top 5, sessionId → count)。 */
    val topSessions: List<Pair<String, Int>> = emptyList(),
    /** v5: 近30天每日新增数(日期 → 条数)。 */
    val dailyTrend: List<Pair<String, Int>> = emptyList(),
    /**
     * v1.98: 经验库相关 state。
     *
     * - [experienceEnabled]:经验库开关(用户在设置页切换,实时反映)。
     * - [experienceItems]:经验条目列表(按 updatedAt 降序,供 UI 展示与编辑)。
     * - [experienceCount]:条目总数(用于 dashboard 指标)。
     */
    val experienceEnabled: Boolean = false,
    val experienceItems: List<MemoryItem> = emptyList(),
    val experienceCount: Int = 0,
    /**
     * v1.0.72: 群聊记忆(独立于主记忆系统)。
     *
     * 展示在记忆中心"群聊"Tab,供用户查看/单条删除/一键清空。
     * 数据来自 [io.zer0.muse.data.groupchat.GroupChatMemoryRepository]。
     */
    val groupChatMemories: List<GroupChatMemoryUiItem> = emptyList(),
    val groupChatMemoriesLoading: Boolean = false,
)

/**
 * 单条记忆 UI 项 — 跨 4 层统一形态。
 *
 * @param id 唯一标识(factId / sessionId / sectionKey)
 * @param title 标题(事实摘要 / 会话标题 / 编译段名)
 * @param content 完整内容(markdown 友好)
 * @param tags 标签(仅 Fact 层有)
 * @param time 时间戳文案
 * @param source 来源层("Fact" / "Summary" / "Compile")
 */
data class MemoryItem(
    val id: String,
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val time: String? = null,
    val source: String,
    /** v4: 重要程度(0=普通,1=重要,2=关键)。仅 Fact 层有值。 */
    val importance: Int = 0,
    /** v4: 来源会话 id(仅 Fact 层有值,用于显示来源)。 */
    val sessionId: String? = null,
    /** v4: 入库时间 ISO 8601(用于显示"X天前入库")。 */
    val createdAt: String? = null,
    /** v1.98: 分类(仅 Experience 层有值,编辑时回填到表单)。 */
    val category: String? = null,
    /**
     * v8: 记忆作用域(仅 Fact 层有值)。
     *  - "main":主助手作用域
     *  - assistantId:子助手/团队成员作用域
     * 用于 UI 显示 scope 徽章(主助手=默认色,子助手=tertiary 色)。
     */
    val scope: String? = null,
    /** B4-05: 手动置顶时间 ISO 8601,null 表示未置顶。 */
    val pinnedAt: String? = null,
)

/**
 * v8: 记忆作用域筛选项(供 UI 顶部 FilterChip 渲染)。
 *
 *  - [id]:作用域标识("main" 或 assistantId),null 表示"全部作用域"(UI 用 all 标记)。
 *    为方便 UI 区分"全部"与具体作用域,使用 [isAll] 标记。
 *  - [displayName]:UI 显示名("全部" / "主助手" / 助手名称)。
 *  - [isMain]:是否为主助手作用域(用于 UI 选用默认色 vs tertiary 色)。
 *  - [isAll]:是否为"全部"选项(用于 UI 显示"全部"且默认选中)。
 */
data class ScopeOption(
    val id: String?,
    val displayName: String,
    val isMain: Boolean = false,
    val isAll: Boolean = false,
)

/**
 * v1.0.72: 群聊记忆 UI 条目(记忆中心"群聊"Tab)。
 *
 * @param id 群聊记忆 id
 * @param groupChatId 群聊 id
 * @param groupChatName 群聊名称(从 GroupChatRepository 解析,失败回退 "群聊")
 * @param assistantName 发言助手名(从 AssistantRepository 解析,失败回退原始 id)
 * @param summary 摘要文本
 * @param createdAt 创建时间戳
 * @param timeText 格式化时间文案
 */
data class GroupChatMemoryUiItem(
    val id: String,
    val groupChatId: String,
    val groupChatName: String,
    val assistantName: String,
    val summary: String,
    val createdAt: Long,
    val timeText: String,
)

/**
 * 阶段 6: 记忆页 ViewModel。
 *
 * 数据来源(全部已通过 Koin 注册):
 *  - [FactStore]:Fact 层 + LIKE 搜索
 *  - [SessionSummaryManager]:Summary 层
 *  - [MemoryCompiler]:Compile 层(读取 4 段编译产物)
 *
 * Deep 层无独立存储,通过 Fact 总数 + 时间戳推断展示。
 *
 * v8: 新增 [assistantRepository] 注入,用于:
 *  - 加载 [availableScopes](主助手 + 所有子助手)
 *  - [selectedScope] 控制记忆页 factItems 按作用域筛选
 *  - [addFact] 写入时使用当前 [selectedScope](null 时默认 "main")
 */
class MemoryViewModel(
    application: Application,
    private val factStore: FactStore,
    private val summaryManager: SessionSummaryManager,
    private val memoryCompiler: MemoryCompiler,
    /** v0.51: 注入 MemoryTicker 用于读取 healthFlow 与裁剪后的 compiledMarkdown。 */
    private val memoryTicker: MemoryTicker,
    /** v1.98: 注入 SettingsRepository 用于读取经验库开关。 */
    private val settings: SettingsRepository,
    /** v1.98: 注入 ExperienceRepository 用于经验库 CRUD + observeAll。 */
    private val experienceRepository: ExperienceRepository,
    /** v8: 注入 AssistantRepository 用于加载 availableScopes(主助手 + 子助手列表)。 */
    private val assistantRepository: AssistantRepository,
    /** v1.0.52 P2-2: 注入 MemorySpaceRepository 用于 Space 切换 + 列表。 */
    private val spaceRepository: MemorySpaceRepository,
    /** v1.0.72: 注入 GroupChatMemoryRepository 用于群聊记忆展示/删除。 */
    private val groupChatMemoryRepository: io.zer0.muse.data.groupchat.GroupChatMemoryRepository,
    /** v1.0.72: 注入 GroupChatRepository 用于解析群聊名。 */
    private val groupChatRepository: io.zer0.muse.data.groupchat.GroupChatRepository,
    /** v1.0.72: 注入 ChatService 用于 LLM 合并重复记忆。 */
    private val chatService: io.zer0.ai.ChatService,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(MemoryUiState())
    val state: StateFlow<MemoryUiState> = _state.asStateFlow()

    // 审计修复 (3.2): 记录 loadAll 的协程 Job,新加载前取消旧协程,避免旧结果覆盖新状态
    private var loadJob: Job? = null

    // 审计修复 (3.3): 记录 search 的协程 Job,新搜索前取消旧协程,避免旧结果覆盖新状态
    private var searchJob: Job? = null

    /**
     * v8: 当前选中的记忆作用域。
     *  - null:全部作用域(默认,UI 选中"全部" chip)
     *  - "main":仅展示主助手作用域的事实
     *  - assistantId:仅展示该子助手作用域的事实
     *
     * 切换 scope 后会触发 [loadAll] 重新拉取数据。
     */
    private val _selectedScope = MutableStateFlow<String?>(null)
    val selectedScope: StateFlow<String?> = _selectedScope.asStateFlow()
    /** v1.x: 全量去重防重入。 */
    private var _dedupRunning = false
    /** v1.x: 立即编译防重入(编译耗 LLM 调用,避免连点重复执行)。 */
    private var _compiling = false
    /** v1.0.90: 编译进行中状态,供记忆页显示明确反馈。 */
    private val _compilingState = MutableStateFlow(false)
    val compilingState: StateFlow<Boolean> = _compilingState.asStateFlow()

    /** v1.0.90: 去重进行中状态,供记忆页禁用按钮并显示反馈。 */
    private val _dedupState = MutableStateFlow(false)
    val dedupState: StateFlow<Boolean> = _dedupState.asStateFlow()

    /** 统一“整理记忆”动作的状态，避免用户需要理解多个底层按钮。 */
    private val _organizeRunning = MutableStateFlow(false)
    val organizeRunning: StateFlow<Boolean> = _organizeRunning.asStateFlow()
    private val _organizeStage = MutableStateFlow<String?>(null)
    val organizeStage: StateFlow<String?> = _organizeStage.asStateFlow()
    private val _organizeResult = MutableStateFlow<String?>(null)
    val organizeResult: StateFlow<String?> = _organizeResult.asStateFlow()

    /**
     * 统一整理记忆：编译摘要后执行去重。
     * 两个底层动作仍保持独立 API，供旧入口和后台任务兼容；新 UI 只暴露本方法。
     */
    fun organizeMemory() {
        if (_organizeRunning.value) return
        _organizeRunning.value = true
        _organizeStage.value = "prepare"
        viewModelScope.launch {
            try {
                _organizeStage.value = "compile"
                val compileOk = withContext(Dispatchers.IO) {
                    resultOf { memoryTicker.forceCompileNow() }
                        .onError { msg, t -> Logger.w("MemoryViewModel", "整理记忆编译失败: ${t?.message ?: msg}") }
                        .getOrNull() != null
                }
                if (!compileOk) {
                    _organizeResult.value = "failed:compile"
                    return@launch
                }
                _organizeStage.value = "dedup"
                val merged = withContext(Dispatchers.IO) {
                    resultOf {
                        factStore.dedupPass(
                            scope = _selectedScope.value ?: "main",
                            spaceId = _selectedSpaceId.value,
                        )
                    }
                        .onError { msg, t -> Logger.w("MemoryViewModel", "整理记忆去重失败: ${t?.message ?: msg}") }
                        .getOrNull()
                }
                if (merged == null) {
                    _organizeResult.value = "failed:dedup"
                } else {
                    _organizeStage.value = "complete"
                    _organizeResult.value = "done:$merged"
                    loadAll(silent = true)
                }
            } finally {
                _organizeRunning.value = false
                if (_organizeStage.value != "complete") _organizeStage.value = null
            }
        }
    }

    fun consumeOrganizeResult() {
        _organizeResult.value = null
        if (!_organizeRunning.value) _organizeStage.value = null
    }

    /**
     * v8: 可选的作用域列表(响应式)。
     * 始终包含"全部" + "主助手";其余项来自 [assistantRepository.observeAll]。
     * 用户在助手设置页新建/删除子助手时,本列表自动更新。
     */
    private val _availableScopes = MutableStateFlow<List<ScopeOption>>(emptyList())
    val availableScopes: StateFlow<List<ScopeOption>> = _availableScopes.asStateFlow()

    /**
     * v1.0.52 P2-2: 当前选中的记忆空间 id。
     * 从 SettingsRepository.currentSpaceIdFlow 读取,用户切换 Space 后写入。
     * factItems 按 (scope + spaceId) 双重过滤。
     */
    private val _selectedSpaceId = MutableStateFlow(MemorySpaceEntity.DEFAULT_SPACE_ID)
    val selectedSpaceId: StateFlow<String> = _selectedSpaceId.asStateFlow()

    /**
     * v1.0.52 P2-2: 可用的 Space 列表(响应式)。
     * 来自 [spaceRepository.observeSpaces],用户在 Space 管理页 CRUD 后自动更新。
     */
    private val _availableSpaces = MutableStateFlow<List<MemorySpaceEntity>>(emptyList())
    val availableSpaces: StateFlow<List<MemorySpaceEntity>> = _availableSpaces.asStateFlow()

    /**
     * v1.0.51: 存量记忆迁移进度 — 升级后首次启动补跑历史 session 摘要时实时反映。
     * null 表示未在迁移中;非 null 时 MemoryScreen 顶部显示进度条。
     * 直接转发 MemoryTicker.backfillProgressFlow,无需 ViewModel 中转状态。
     */
    val backfillProgress: StateFlow<MemoryTicker.BackfillProgress?> = memoryTicker.backfillProgressFlow

    /** v1.0.51: 清除迁移进度(用户已看到完成提示后由 MemoryScreen 调用)。 */
    fun clearBackfillProgress() = memoryTicker.clearBackfillProgress()

    init {
        // v0.51: 收集 MemoryTicker 的 healthFlow,实时反映记忆健康状态到 UI。
        // 各步骤成功/失败时间会在 dashboard 卡片展示,无需用户手动刷新。
        viewModelScope.launch {
            memoryTicker.healthFlow.collect { health ->
                val lastCompileTime = health.values
                    .mapNotNull { it.lastSuccessAt }
                    .maxOrNull()
                _state.update {
                    it.copy(
                        healthMap = health,
                        lastCompileTime = lastCompileTime,
                        syncStatus = computeSyncStatus(lastCompileTime),
                        syncStale = computeSyncStale(lastCompileTime),
                    )
                }
            }
        }
        // v1.98: 订阅经验库开关,实时反映设置页切换到 UI(决定是否展示经验库卡片)。
        viewModelScope.launch {
            settings.experienceEnabledFlow.collect { enabled ->
                _state.update { it.copy(experienceEnabled = enabled) }
            }
        }
        // v1.98: 订阅经验库条目流,CRUD 后自动刷新 UI(无需手动 loadAll)。
        viewModelScope.launch {
            experienceRepository.observeAll().collect { experiences ->
                val items = experiences.map { it.toMemoryItem() }
                _state.update {
                    it.copy(
                        experienceItems = items,
                        experienceCount = experiences.size,
                    )
                }
            }
        }
        // v8: 订阅 AssistantRepository.observeAll,实时刷新 availableScopes。
        // 用户在助手设置页新建/删除子助手时,FilterChip 列表自动同步。
        // 默认包含"全部" + "主助手",其余项按 AssistantEntity.id/name 渲染。
        viewModelScope.launch {
            assistantRepository.observeAll.collect { assistants ->
                _availableScopes.value = buildScopes(assistants)
            }
        }
        // v1.0.52 P2-2: 订阅 currentSpaceIdFlow,用户在记忆页或设置页切换 Space 后实时同步。
        // 切换 Space 后触发 loadAll 重新按 spaceId 过滤事实列表。
        viewModelScope.launch {
            settings.currentSpaceIdFlow.collect { spaceId ->
                val changed = _selectedSpaceId.value != spaceId
                _selectedSpaceId.value = spaceId
                if (changed) loadAll(silent = true)
            }
        }
        // v1.0.52 P2-2: 订阅 Space 列表,用户在 Space 管理页 CRUD 后自动同步切换器下拉。
        viewModelScope.launch {
            spaceRepository.observeSpaces().collect { spaces ->
                _availableSpaces.value = spaces
            }
        }
        loadAll()
    }

    /**
     * v8: 构造作用域选项列表(始终包含"全部" + "主助手" + 所有子助手)。
     *
     * "default" 助手在 Muse 中视为"默认主助手",其作用域映射到 "main"(与 FactEntity 默认值一致),
     * 避免历史数据(default 助手写入的 facts 仍带 scope="main")与 UI 选项不一致。
     * 其他 assistantId 直接使用其 id 作为 scope。
     */
    private fun buildScopes(assistants: List<AssistantEntity>): List<ScopeOption> {
        val all = ScopeOption(
            id = null,
            displayName = getApplication<Application>().getString(R.string.memory_scope_all),
            isAll = true,
        )
        val main = ScopeOption(
            id = "main",
            displayName = getApplication<Application>().getString(R.string.memory_scope_main),
            isMain = true,
        )
        // 排除 "default" 助手 — 它在历史数据中已映射到 "main",避免 UI 重复展示。
        val subScopes = assistants
            .filter { it.id.isNotBlank() && it.id != "default" }
            .map { ScopeOption(id = it.id, displayName = it.name.ifBlank { it.id }) }
        return listOf(all, main) + subScopes
    }

    /**
     * v8: 切换当前选中的作用域。null 表示"全部"。
     * 切换后立即触发 [loadAll] 重新拉取按 scope 过滤的数据。
     */
    fun selectScope(scope: String?) {
        _selectedScope.value = scope
        loadAll()
    }

    /**
     * v1.0.52 P2-2: 切换当前选中的记忆空间。
     * 持久化到 SettingsRepository,跨会话保留。
     * currentSpaceIdFlow 订阅者会自动触发 loadAll 重新拉取数据。
     */
    fun selectSpace(spaceId: String) {
        viewModelScope.launch {
            resultOf { settings.saveCurrentSpaceId(spaceId) }
                .onError { msg, t -> Logger.w("MemoryViewModel", "saveCurrentSpaceId 失败: $msg", t) }
        }
    }

    /**
     * v1.78 (#7): 手动触发立即编译(对照 MemoryTicker.forceCompileNow)。
     * 用户在记忆页点"立即编译"按钮时调用,不等待 ticker 自动调度。
     */
    fun compileNow() {
        if (_compiling) return
        _compiling = true
        _compilingState.value = true
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                resultOf { memoryTicker.forceCompileNow() }
                    .onError { msg, t -> Logger.w("MemoryViewModel", "forceCompileNow 失败: $msg", t) }
                    .isSuccess
            }
            // v1.0.72: 编译完成后立即执行 LLM 合并去重(重复记忆合并成一条,
            //   不同事实不动;失败不影响编译结果)
            if (success) {
                llmMergeDuplicates()
            }
            _compiling = false
            _compilingState.value = false
            // v1.x: 编译结果反馈(成功/失败),避免"点了没反应"
            _compileResult.value = if (success) "done" else "failed"
            // 编译完成后静默刷新:不触发 isLoading,避免替换当前视图(时间轴/列表)导致闪屏
            loadAll(silent = true)
        }
    }

    /**
     * v1.0.72: LLM 合并重复记忆 — 把相似簇(≥2 条)交给大模型合并成一条,
     * 保留所有关键信息;不同的事实不受影响。失败时静默跳过(规则去重仍由每日任务兜底)。
     */
    private suspend fun llmMergeDuplicates() {
        val scope = _selectedScope.value ?: "main"
        val groups = resultOf { factStore.findSimilarGroups(scope, _selectedSpaceId.value) }
            .onError { msg, t -> Logger.w("MemoryViewModel", "查找重复记忆失败: $msg", t) }
            .getOrNull() ?: return
        if (groups.isEmpty()) return

        var mergedCount = 0
        for (group in groups) {
            if (group.size < 2) continue
            val merged = mergeGroupWithLlm(group)
            if (merged == null) continue
            // 保留簇内重要度最高的一条作为 keeper,更新内容 + 删除其余
            val keeper = group.maxByOrNull { it.importance } ?: group.first()
            val deleted = factStore.update(keeper.id, merged, scope)
            if (deleted) {
                group.filter { it.id != keeper.id }.forEach { other ->
                    resultOf { factStore.delete(other.id) }
                        .onError { msg, t -> Logger.w("MemoryViewModel", "删除重复记忆失败: $msg", t) }
                }
                mergedCount++
            }
        }
        if (mergedCount > 0) {
            Logger.i("MemoryViewModel", "LLM 合并去重: 合并 $mergedCount 组重复记忆")
        }
    }

    /** 调 LLM 把一组相似记忆合并成一条(失败返回 null)。 */
    private suspend fun mergeGroupWithLlm(group: List<io.zer0.memory.fact.FactStore.Fact>): String? {
        val sb = StringBuilder()
        sb.appendLine("你是记忆整理助手。以下是多条内容重复或高度相似的记忆,请把它们合并成一条:保留所有关键信息(人名、时间、地点、数字、事件),去重,语言自然简洁,不要遗漏任何事实细节。如果发现某些记忆其实内容不同、不应该合并,请原样返回所有条目。")
        sb.appendLine()
        group.forEachIndexed { idx, fact ->
            sb.appendLine("${idx + 1}. ${fact.fact}")
        }
        sb.appendLine()
        sb.appendLine("请直接输出合并后的一条记忆,不要任何前缀、编号或引号:")

        return resultOf {
            withTimeoutOrNull(30_000L) {
                chatService.completeText(
                    messages = listOf(
                        io.zer0.ai.core.UIMessage(
                            role = io.zer0.ai.core.MessageRole.USER,
                            content = sb.toString(),
                            createdAt = System.currentTimeMillis(),
                        ),
                    ),
                    temperature = 0.3f,
                    maxTokens = 300,
                ).text.trim()
            }
        }.onError { msg, t ->
            Logger.w("MemoryViewModel", "LLM 合并记忆失败: ${t?.message ?: msg}")
        }.getOrNull()?.takeIf { it.isNotBlank() && it.length > 2 }
    }

    /** v1.x: 编译结果提示(UI LaunchedEffect 消费后清除)。 */
    private val _compileResult = MutableStateFlow<String?>(null)
    val compileResult: StateFlow<String?> = _compileResult.asStateFlow()

    /** UI 消费编译结果后调用。 */
    fun consumeCompileResult() {
        _compileResult.value = null
    }

    /** v1.x: 手动触发全量去重 — 合并当前作用域内近似重复的事实,并刷新列表。
     * 结果通过 [dedupResult] 返回,UI 用 LaunchedEffect 消费并提示。
     */
    private val _dedupResult = MutableStateFlow<String?>(null)
    val dedupResult: StateFlow<String?> = _dedupResult.asStateFlow()

    fun dedupNow() {
        if (_dedupRunning) return
        _dedupRunning = true
        _dedupState.value = true
        viewModelScope.launch {
            val merged = withContext(Dispatchers.IO) {
                resultOf { factStore.dedupPass(scope = _selectedScope.value ?: "main", spaceId = _selectedSpaceId.value) }
                    .onError { msg, t -> Logger.w("MemoryViewModel", "记忆去重失败: ${t?.message ?: msg}") }
                    .getOrNull()
            }
            _dedupRunning = false
            _dedupState.value = false
            _dedupResult.value = if (merged != null) "merged:$merged" else "failed"
            loadAll(silent = true)
        }
    }

    /** UI 消费去重结果后调用,清除提示。 */
    fun consumeDedupResult() {
        _dedupResult.value = null
    }

    /**
     * 拉取全部 4 层数据(memory 模块 Repository 层全是 suspend,无 Flow,需主动 pull)。
     * 捕获完整崩溃堆栈到 state.errorTrace,供 UI 可滚动展示(方便用户复制给开发者)。
     *
     * v8: Fact 层按 [_selectedScope] 过滤:
     *  - null:全部作用域(向后兼容旧版)
     *  - "main" / assistantId:仅拉取该作用域的事实
     * Summary / Compile 层不区分 scope(由 MemoryTicker 全局编译,不按助手隔离)。
     *
     * v1.0.52 P2-2: Fact 层进一步按 [_selectedSpaceId] 过滤:
     *  - scope 为 null 时:按 spaceId 查询(getBySpace)
     *  - scope 非 null 时:按 scope + spaceId 双重查询(getByScopeAndSpace)
     *  - 这样切换 Space 时,factItems 仅展示当前 Space 的事实
     */
    fun loadAll(silent: Boolean = false) {
        // 审计修复 (3.2): 取消上一次加载协程,快速切换 scope 时旧协程不再覆盖新状态
        loadJob?.cancel()
        // silent=true 时不触发 isLoading,避免编译完成后替换当前视图导致闪屏
        if (!silent) {
            _state.update { it.copy(isLoading = true, errorTrace = null) }
        }
        loadJob = viewModelScope.launch {
            try {
                val scope = _selectedScope.value
                val spaceId = _selectedSpaceId.value
                val facts = withContext(Dispatchers.IO) {
                    if (scope == null) {
                        factStore.getBySpace(spaceId)
                    } else {
                        factStore.getByScopeAndSpace(scope, spaceId)
                    }
                }
                val summaries = withContext(Dispatchers.IO) { summaryManager.getAllSummaries() }
                val compileFacts = withContext(Dispatchers.IO) {
                    // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                    resultOf { memoryCompiler.readSection(MemoryCompiler.Section.FACTS) }
                        .onError { msg, t -> Logger.w("MemoryViewModel", "readSection(FACTS) 失败: $msg", t) }
                        .getOrNull() ?: ""
                }
                val compileToday = withContext(Dispatchers.IO) {
                    resultOf { memoryCompiler.readSection(MemoryCompiler.Section.TODAY) }
                        .onError { msg, t -> Logger.w("MemoryViewModel", "readSection(TODAY) 失败: $msg", t) }
                        .getOrNull() ?: ""
                }
                val compileWeek = withContext(Dispatchers.IO) {
                    resultOf { memoryCompiler.readSection(MemoryCompiler.Section.WEEK) }
                        .onError { msg, t -> Logger.w("MemoryViewModel", "readSection(WEEK) 失败: $msg", t) }
                        .getOrNull() ?: ""
                }
                val compileLongterm = withContext(Dispatchers.IO) {
                    resultOf { memoryCompiler.readSection(MemoryCompiler.Section.LONGTERM) }
                        .onError { msg, t -> Logger.w("MemoryViewModel", "readSection(LONGTERM) 失败: $msg", t) }
                        .getOrNull() ?: ""
                }
                // v0.51: 读取裁剪后的 compiledMarkdown(供"AI 对你的理解"卡片折叠展示)。
                // 与 ChatService 注入 system prompt 同源,反映 AI 真正"看到"的记忆。
                val compiledMarkdown = withContext(Dispatchers.IO) {
                    resultOf { memoryTicker.readCompiledMemoryMarkdown() }
                        .onError { msg, t -> Logger.w("MemoryViewModel", "readCompiledMemoryMarkdown 失败: $msg", t) }
                        .getOrNull() ?: ""
                }

                val factItems = facts.map { fact ->
                    MemoryItem(
                        id = fact.id.toString(),
                        title = fact.fact.take(60),
                        content = fact.fact,
                        tags = fact.tags,
                        time = fact.time,
                        source = "Fact",
                        importance = fact.importance,
                        sessionId = fact.sessionId,
                        createdAt = fact.createdAt,
                        // v8: 透传 scope,供 UI 显示徽章(主助手=默认色,子助手=tertiary 色)
                        scope = fact.scope,
                    // B4-05: 透传置顶时间
                    pinnedAt = fact.pinnedAt,
                        // v9: 透传 category,供新 UI 按分类筛选与展示
                        category = fact.category,
                    )
                }
                val summaryItems = summaries.map { summary ->
                    MemoryItem(
                        id = summary.sessionId,
                        title = getApplication<Application>().getString(R.string.memory_summary_title, summary.sessionId.take(8)),
                        content = summary.summary,
                        time = summary.updatedAt,
                        source = "Summary",
                    )
                }
                val compileItems = buildList {
                    if (compileFacts.isNotBlank()) add(MemoryItem("facts", getApplication<Application>().getString(R.string.memory_compile_section_facts), compileFacts, source = "Compile"))
                    if (compileToday.isNotBlank()) add(MemoryItem("today", getApplication<Application>().getString(R.string.memory_compile_section_today), compileToday, source = "Compile"))
                    if (compileWeek.isNotBlank()) add(MemoryItem("week", getApplication<Application>().getString(R.string.memory_compile_section_week), compileWeek, source = "Compile"))
                    if (compileLongterm.isNotBlank()) add(MemoryItem("longterm", getApplication<Application>().getString(R.string.memory_compile_section_longterm), compileLongterm, source = "Compile"))
                }

                // v5: 计算统计
                val now = Instant.now()
                val weekAgo = now.minus(7, java.time.temporal.ChronoUnit.DAYS)
                val monthAgo = now.minus(30, java.time.temporal.ChronoUnit.DAYS)
                val dateFormat = DateTimeFormatter.ISO_LOCAL_DATE.withZone(java.time.ZoneId.systemDefault())
                val total = facts.size
                val weekNew = facts.count { f ->
                    runCatching { Instant.parse(f.createdAt).isAfter(weekAgo) }.getOrDefault(false)
                }
                val monthNew = facts.count { f ->
                    runCatching { Instant.parse(f.createdAt).isAfter(monthAgo) }.getOrDefault(false)
                }
                val impDist = facts.groupBy { it.importance.coerceIn(0, 2) }.mapValues { it.value.size }
                val topSessionsList = facts.groupBy { it.sessionId ?: "unknown" }
                    .mapValues { it.value.size }
                    .entries
                    .sortedByDescending { it.value }
                    .take(5)
                    .map { it.key to it.value }
                // 近30天每日趋势
                val dailyMap = mutableMapOf<String, Int>()
                facts.forEach { f ->
                    runCatching {
                        val day = dateFormat.format(Instant.parse(f.createdAt))
                        dailyMap[day] = (dailyMap[day] ?: 0) + 1
                    }
                }
                val dailyTrendList = dailyMap.entries
                    .sortedBy { it.key }
                    .takeLast(30)
                    .map { it.key to it.value }

                _state.update {
                    it.copy(
                        isLoading = false,
                        factItems = factItems,
                        summaryItems = summaryItems,
                        compileItems = compileItems,
                        factCount = facts.size,
                        summaryCount = summaries.size,
                        lastUpdatedAt = facts.maxByOrNull { it.createdAt }?.createdAt,
                        compiledMarkdown = compiledMarkdown,
                        syncStatus = computeSyncStatus(it.lastCompileTime),
                        syncStale = computeSyncStale(it.lastCompileTime),
                        totalFactCount = total,
                        weekNewCount = weekNew,
                        monthNewCount = monthNew,
                        importanceDistribution = impDist,
                        topSessions = topSessionsList,
                        dailyTrend = dailyTrendList,
                    )
                }
            } catch (e: CancellationException) {
                // v1.78 (H1): 必须重抛协程取消信号,否则会破坏协程取消语义
                throw e
            } catch (t: Throwable) {
                // 捕获完整堆栈信息(类名 + 消息 + stack trace),
                // 供 UI 在可滚动框中展示,用户可上下滑动查看完整错误并复制给开发者
                val sw = java.io.StringWriter()
                t.printStackTrace(java.io.PrintWriter(sw))
                val fullTrace = buildString {
                    appendLine(getApplication<Application>().getString(R.string.memory_error_trace_header))
                    appendLine(getApplication<Application>().getString(R.string.memory_error_trace_time) + java.text.SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL_SEC, java.util.Locale.getDefault()).format(java.util.Date()))
                    appendLine(getApplication<Application>().getString(R.string.memory_error_trace_exception) + t.javaClass.name)
                    appendLine(getApplication<Application>().getString(R.string.memory_error_trace_message) + t.message)
                    appendLine(getApplication<Application>().getString(R.string.memory_error_trace_device) + "${android.os.Build.BRAND} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE}, SDK ${android.os.Build.VERSION.SDK_INT})")
                    appendLine()
                    appendLine(getApplication<Application>().getString(R.string.memory_error_trace_stack_header))
                    append(sw.toString())
                }
                _state.update { it.copy(isLoading = false, errorTrace = fullTrace) }
            }
        }
    }

    /**
     * 搜索查询(onValueChange 防抖由 UI 层处理,这里直接执行)。
     * 空查询清空搜索结果,非空走 LIKE 搜索。
     */
    fun search(query: String) {
        _state.update { it.copy(query = query) }
        if (query.isBlank()) {
            _state.update { it.copy(searchResults = emptyList(), isSearching = false) }
            return
        }
        _state.update { it.copy(isSearching = true) }
        // 审计修复 (3.3): 取消上一次搜索协程,避免旧搜索结果覆盖新结果
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                resultOf { factStore.searchFullText(query) }
                    .onError { msg, t -> Logger.w("MemoryViewModel", "searchFullText 失败: $msg", t) }
                    .getOrNull() ?: emptyList()
            }
            val items = results.map { fact ->
                MemoryItem(
                    id = fact.id.toString(),
                    title = fact.fact.take(60),
                    content = fact.fact,
                    tags = fact.tags,
                    time = fact.time,
                    source = "Fact",
                    importance = fact.importance,
                    sessionId = fact.sessionId,
                    createdAt = fact.createdAt,
                    // v8: 透传 scope,搜索结果与列表项徽章一致
                    scope = fact.scope,
                    // B4-05: 透传置顶时间
                    pinnedAt = fact.pinnedAt,
                    // v9: 透传 category,搜索结果也按分类展示
                    category = fact.category,
                )
            }
            _state.update { it.copy(searchResults = items, isSearching = false) }
        }
    }

    /**
     * 删除单条 Fact(仅 Fact 层支持删除)。
     *
     * S-04: 删除后立即调用 [MemoryCompiler.purgeTombstonedFacts],把命中墓碑的内容
     * 从已编译的 FACTS 段剔除 — 注入链路即刻生效,不等下次定时编译。
     */
    fun deleteFact(factId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val id = factId.toLongOrNull()
                if (id != null) {
                    // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                    resultOf { factStore.delete(id) }
                        .onError { msg, t -> MuseToast.show(getApplication<Application>().getString(R.string.memory_delete_failed, msg)) }
                        .onSuccess { deleted ->
                            if (deleted) {
                                // S-04: 剔除编译产物中已删事实,防止"删除后仍注入/从摘要复活"
                                resultOf { memoryCompiler.purgeTombstonedFacts() }
                                    .onError { msg, t -> Logger.w("MemoryVM", "purgeTombstonedFacts 失败: $msg", t) }
                            }
                        }
                }
            }
            loadAll()
        }
    }

    /**
     * P2: 删除单条 Summary(根据 sessionId)。
     */
    /** B4-05: 切换单条 Fact 的手动置顶状态。 */
    fun toggleFactPinned(factId: String) {
        viewModelScope.launch {
            val id = factId.toLongOrNull() ?: return@launch
            val fact = withContext(Dispatchers.IO) { factStore.getById(id) } ?: return@launch
            val pinned = fact.pinnedAt == null
            resultOf { factStore.setPinned(id, pinned) }
                .onError { msg, t -> Logger.w("MemoryViewModel", "toggleFactPinned 失败: $msg", t) }
            loadAll()
        }
    }
    fun deleteSummary(sessionId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                resultOf { summaryManager.delete(sessionId) }
                    .onError { msg, t -> MuseToast.show(getApplication<Application>().getString(R.string.memory_delete_failed, msg)) }
            }
            loadAll()
        }
    }

    /**
     * P2: 清空单段 Compile 产物(根据 sectionKey:facts/today/week/longterm)。
     */
    fun deleteCompile(sectionKey: String) {
        val section = MemoryCompiler.Section.ALL.firstOrNull { it.key == sectionKey }
            ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                resultOf { memoryCompiler.clearSection(section) }
                    .onError { msg, t -> MuseToast.show(getApplication<Application>().getString(R.string.memory_delete_failed, msg)) }
            }
            loadAll()
        }
    }

    /**
     * P2: 编辑单条 Fact 内容。
     */
    fun editFact(factId: String, newContent: String) {
        if (newContent.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val id = factId.toLongOrNull()
                if (id != null) {
                    // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                    resultOf { factStore.update(id, newContent) }
                        .onError { msg, t -> MuseToast.show(getApplication<Application>().getString(R.string.memory_edit_failed, msg)) }
                }
            }
            loadAll()
        }
    }

    /**
     * v4: 更新单条 Fact 的重要程度。
     * @param factId fact 的字符串 id
     * @param importance 0=普通,1=重要,2=关键
     */
    fun setFactImportance(factId: String, importance: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val id = factId.toLongOrNull()
                if (id != null) {
                    // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                    resultOf { factStore.setImportance(id, importance) }
                        .onError { msg, t -> MuseToast.show(getApplication<Application>().getString(R.string.memory_set_failed, msg)) }
                }
            }
            loadAll()
        }
    }

    /**
     * 手动新增一条元事实。
     *
     * v8: 新增事实的 scope 取当前 [_selectedScope],若为 null(全部)则默认 "main"。
     * 这与用户在 UI 上切换 scope 后再"新增"的直觉一致 — 用户切到某子助手作用域后,
     * 新增的事实归属该子助手;切到"全部"时归属主助手(默认)。
     *
     * v1.0.52 P2-2: 新增事实的 spaceId 取当前 [_selectedSpaceId],
     * 新增的事实归属当前选中的 Space。
     */
    fun addFact(content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            // v1.78 (H6): loadAll 移出 runCatching,避免 add 成功但 loadAll 失败时
            // 错误信息显示"添加失败"(错误归因错位)
            val scope = _selectedScope.value ?: "main"
            val spaceId = _selectedSpaceId.value
            val ok = withContext(Dispatchers.IO) {
                // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                resultOf {
                    factStore.add(FactStore.Fact(fact = content.trim()), scope = scope, spaceId = spaceId)
                }.onError { msg, t ->
                    _state.update { it.copy(errorTrace = (it.errorTrace ?: "") + "\n" + getApplication<Application>().getString(R.string.memory_add_failed, msg)) }
                }.isSuccess
            }
            if (ok) loadAll()
        }
    }

    /**
     * P2: 编辑单条 Summary 内容。
     */
    fun editSummary(sessionId: String, newContent: String) {
        if (newContent.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                resultOf {
                    val existing = summaryManager.getSummary(sessionId) ?: return@resultOf
                    summaryManager.saveSummary(
                        sessionId,
                        existing.copy(
                            summary = newContent.trim(),
                            updatedAt = Instant.now().toString(),
                        )
                    )
                }
            }
            loadAll()
        }
    }

    /**
     * P2: 编辑单段 Compile 产物内容。
     */
    fun editCompile(sectionKey: String, newContent: String) {
        if (newContent.isBlank()) return
        val section = MemoryCompiler.Section.ALL.firstOrNull { it.key == sectionKey }
            ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                resultOf { memoryCompiler.writeSection(section, newContent.trim()) }
            }
            loadAll()
        }
    }

    /**
     * v4: 根据 lastCompileTime(ISO 8601)计算同步状态文案。
     *  - null: 尚未编译
     *  - < 10 分钟: 记忆已同步
     *  - >= 10 分钟: 最近的对话可能还未进入记忆
     */
    private fun computeSyncStatus(lastCompileTime: String?): String {
        if (lastCompileTime == null) {
            return getApplication<Application>().getString(R.string.memory_sync_never)
        }
        return try {
            val minutes = Duration.between(
                Instant.parse(lastCompileTime),
                Instant.now(),
            ).toMinutes()
            if (minutes < 10) {
                getApplication<Application>().getString(R.string.memory_sync_recent, minutes)
            } else {
                getApplication<Application>().getString(R.string.memory_sync_stale, minutes)
            }
        } catch (e: Exception) {
            ""
        }
    }
    private fun computeSyncStale(lastCompileTime: String?): Boolean {
        if (lastCompileTime == null) return false
        return try {
            Duration.between(
                Instant.parse(lastCompileTime),
                Instant.now(),
            ).toMinutes() >= 10
        } catch (e: Exception) {
            false
        }
    }

    // ── v2.0: 记忆分类筛选 CRUD ──────────────────────────────────────────

    /** v9: 设置分类筛选(null=全部, 对应 fact category: identity/preference/event/relationship/goal/medical)。 */
    fun setCategoryFilter(category: String?) {
        _state.update { it.copy(categoryFilter = category) }
    }

    /** v9: 根据分类筛选记忆条目(null=全部)。 */
    private fun filterItems(items: List<MemoryItem>, category: String?): List<MemoryItem> {
        return items.filter { item ->
            category == null || item.category.equals(category, ignoreCase = true)
        }
    }

    private fun isToday(createdAt: String?): Boolean {
        if (createdAt == null) return false
        return try {
            val date = java.time.Instant.parse(createdAt)
            val today = java.time.LocalDate.now()
            val itemDate = date.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            itemDate == today
        } catch (e: Exception) { false }
    }

    private fun isThisWeek(createdAt: String?): Boolean {
        if (createdAt == null) return false
        return try {
            val date = java.time.Instant.parse(createdAt)
            val today = java.time.LocalDate.now()
            val itemDate = date.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            val weekStart = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            !itemDate.isBefore(weekStart) && !itemDate.isAfter(today)
        } catch (e: Exception) { false }
    }

    private fun isThisMonth(createdAt: String?): Boolean {
        if (createdAt == null) return false
        return try {
            val date = java.time.Instant.parse(createdAt)
            val today = java.time.LocalDate.now()
            val itemDate = date.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            itemDate.year == today.year && itemDate.month == today.month
        } catch (e: Exception) { false }
    }

    // ── v1.98: 经验库 CRUD ──────────────────────────────────────────────

    /**
     * v1.98: 手动新增一条经验。
     * @param title 标题(简短描述)
     * @param content 详细内容(经验正文)
     * @param category 分类(默认"通用")
     * @param tags 标签列表(默认空)
     */
    fun addExperience(title: String, content: String, category: String = DEFAULT_EXPERIENCE_CATEGORY, tags: List<String> = emptyList()) {
        if (title.isBlank() || content.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val entity = ExperienceEntity(
                    id = "exp_${now}_${(0..9999).random()}",
                    title = title.trim(),
                    content = content.trim(),
                    category = category.trim().ifBlank { DEFAULT_EXPERIENCE_CATEGORY },
                    tagsJson = if (tags.isEmpty()) "[]" else AppJson.encodeToString(tags),
                    source = "manual",
                    createdAt = now,
                    updatedAt = now,
                )
                // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                resultOf { experienceRepository.upsert(entity) }
                    .onError { msg, t -> MuseToast.show(getApplication<Application>().getString(R.string.memory_add_experience_failed, msg)) }
            }
            // observeAll Flow 会自动刷新 UI,无需手动 loadAll
        }
    }

    /**
     * v1.98: 编辑已有经验条目(标题/内容/分类/标签)。
     */
    fun editExperience(
        id: String,
        title: String,
        content: String,
        category: String = DEFAULT_EXPERIENCE_CATEGORY,
        tags: List<String> = emptyList(),
    ) {
        if (title.isBlank() || content.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                resultOf {
                    val existing = experienceRepository.getById(id) ?: return@resultOf
                    val updated = existing.copy(
                        title = title.trim(),
                        content = content.trim(),
                        category = category.trim().ifBlank { DEFAULT_EXPERIENCE_CATEGORY },
                        tagsJson = if (tags.isEmpty()) "[]" else AppJson.encodeToString(tags),
                        updatedAt = System.currentTimeMillis(),
                    )
                    experienceRepository.upsert(updated)
                }.onError { msg, t -> MuseToast.show(getApplication<Application>().getString(R.string.memory_edit_experience_failed, msg)) }
            }
        }
    }

    /**
     * v1.98: 删除单条经验。
     */
    fun deleteExperience(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                resultOf { experienceRepository.delete(id) }
                    .onError { msg, t -> MuseToast.show(getApplication<Application>().getString(R.string.memory_delete_experience_failed, msg)) }
            }
        }
    }

    /**
     * v1.98: ExperienceEntity → MemoryItem 转换(复用 UI 统一形态)。
     *
     * source 字段标注为 "Experience",供 UI 层区分来源。
     * tags 从 tagsJson(JSON 数组字符串)解析为 List<String>。
     */
    private fun ExperienceEntity.toMemoryItem(): MemoryItem {
        val parsedTags = runCatching {
            AppJson.decodeFromString<List<String>>(tagsJson)
        }.getOrDefault(emptyList())
        val timeText = runCatching {
            java.text.SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL_SEC, java.util.Locale.CHINA)
                .format(java.util.Date(updatedAt))
        }.getOrNull()
        return MemoryItem(
            id = id,
            title = title,
            content = content,
            tags = parsedTags,
            time = timeText,
            source = "Experience",
            category = category,
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // v1.0.72: 群聊记忆(记忆中心"群聊"Tab)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * v1.0.72: 加载全部群聊记忆(按时间降序)。
     * 展示在记忆中心"群聊"Tab,供用户查看/删除。
     */
    fun loadGroupChatMemories() {
        viewModelScope.launch {
            _state.update { it.copy(groupChatMemoriesLoading = true) }
            val memories = withContext(Dispatchers.IO) {
                resultOf { groupChatMemoryRepository.getAll() }.getOrNull() ?: emptyList()
            }
            val assistants = resultOf { assistantRepository.observeAll.first() }.getOrNull() ?: emptyList()
            val groupChats = resultOf { groupChatRepository.observeChats().first() }.getOrNull() ?: emptyList()
            val groupChatNameById = groupChats.associateBy({ it.id }, { it.name })
            val assistantNameById = assistants.associateBy({ it.id }, { it.name })
            val items = memories.map { m ->
                GroupChatMemoryUiItem(
                    id = m.id,
                    groupChatId = m.groupChatId,
                    groupChatName = groupChatNameById[m.groupChatId] ?: "群聊",
                    assistantName = assistantNameById[m.assistantId] ?: m.assistantId.take(8),
                    summary = m.summary,
                    createdAt = m.createdAt,
                    timeText = runCatching {
                        java.text.SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL_SEC, java.util.Locale.CHINA)
                            .format(java.util.Date(m.createdAt))
                    }.getOrNull() ?: "",
                )
            }
            _state.update { it.copy(groupChatMemories = items, groupChatMemoriesLoading = false) }
        }
    }

    /**
     * v1.0.72: 删除单条群聊记忆。
     *
     * @param id 群聊记忆 id
     */
    fun deleteGroupChatMemory(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                resultOf { groupChatMemoryRepository.deleteById(id) }
                    .onError { msg, t ->
                        Logger.w("MemoryViewModel", "删除群聊记忆失败: $msg", t)
                        MuseToast.show(getApplication<Application>().getString(R.string.memory_delete_group_chat_failed, msg))
                    }
            }
            // 删除后刷新列表
            _state.update { it.copy(groupChatMemories = it.groupChatMemories.filterNot { m -> m.id == id }) }
        }
    }

    /**
     * v1.0.72: 清空全部群聊记忆(一键)。
     */
    fun clearAllGroupChatMemories() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                resultOf { groupChatMemoryRepository.deleteAll() }
                    .onError { msg, t ->
                        Logger.w("MemoryViewModel", "清空群聊记忆失败: $msg", t)
                        MuseToast.show(getApplication<Application>().getString(R.string.memory_clear_group_chat_failed, msg))
                    }
            }
            _state.update { it.copy(groupChatMemories = emptyList()) }
        }
    }
}