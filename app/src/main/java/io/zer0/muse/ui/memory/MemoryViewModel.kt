package io.zer0.muse.ui.memory
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.compile.MemoryCompiler
import io.zer0.memory.fact.FactStore
import io.zer0.memory.runtime.MemoryScopeKey
import io.zer0.memory.runtime.MemorySearchRequest
import io.zer0.memory.runtime.MemorySearchResult
import io.zer0.memory.runtime.MemoryUseCases
import io.zer0.memory.space.MemorySpaceEntity
import io.zer0.memory.space.MemorySpaceRepository
import io.zer0.memory.summary.SessionSummaryManager
import io.zer0.memory.ticker.MemoryTicker
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.experience.DEFAULT_EXPERIENCE_CATEGORY
import io.zer0.muse.data.session.SessionRepository
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
import kotlinx.serialization.encodeToString
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 阶段 6: 记忆页 UI 数据模型。
 *
 * 4 层记忆系统(Fact / Summary / Compile / Deep)的统一 UI 投影:
 *  - Fact 层:元事实列表(可搜索)
 *  - Summary 层:会话滚动摘要列表
 *  - Compile 层:4 段编译产物(facts/today/week/longterm)
 *  - Deep 层:无独立存储,展示统计信息(由 Fact 总数 + 最近更新时间推断)
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
    /**
     * 经验库统一仓库（memory 侧，与工具写入同一份存储）。
     *
     * 旧实现读 app 侧 Room 表，导致「工具记了经验、记忆页看不到」；
     * 现在 UI 与 prompt 都订阅 revision 后重读同一份文件存储。
     */
    private val experienceRepository: io.zer0.memory.experience.ExperienceRepository,
    /** v8: 注入 AssistantRepository 用于加载 availableScopes(主助手 + 子助手列表)。 */
    private val assistantRepository: AssistantRepository,
    private val sessionRepository: SessionRepository,
    /** v1.0.52 P2-2: 注入 MemorySpaceRepository 用于 Space 切换 + 列表。 */
    private val spaceRepository: MemorySpaceRepository,
    /** v1.0.72: 注入 GroupChatMemoryRepository 用于群聊记忆展示/删除。 */
    private val groupChatMemoryRepository: io.zer0.muse.data.groupchat.GroupChatMemoryRepository,
    /** v1.0.72: 注入 GroupChatRepository 用于解析群聊名。 */
    private val groupChatRepository: io.zer0.muse.data.groupchat.GroupChatRepository,
    /** v1.0.72: 注入 ChatService 用于 LLM 合并重复记忆。 */
    private val chatService: io.zer0.ai.ChatService,
    /**
     * F-8: 注入置顶记忆存储 — 记忆页 UI 置顶与 pin_memory 工具写入同一数据源
     * (filesDir/pinned_memory)，确保 UI 置顶的内容真正进入 system prompt「固定记忆」段。
     */
    private val pinnedMemoryStore: io.zer0.memory.pin.PinnedMemoryStore,
    /**
     * P0-4: 注入 FactDbProvider — 生产侧(DeepMemoryProcessor / MemoryAutoSaveScheduler /
     * SystemPromptAssembler)按助手分库写事实(facts_<id>.db),记忆中心必须走同一 provider
     * 才能读到/改到 AI 真正使用的库。子助手 scope 的增删改此前落在默认库,对 AI 无效。
     */
    private val factDbProvider: io.zer0.memory.fact.FactDbProvider,
    /**
     * v1.0.92: LLM 记忆整合器 — 手动"整理记忆"与每日自动整合共用同一实现
     * (相似簇交给大模型合并)。null = 测试/未装配时跳过 LLM 整合。
     */
    private val factConsolidator: io.zer0.memory.fact.LlmFactConsolidator? = null,
    /** 统一事实/置顶读写入口；旧参数保留以兼容无 memory 模块的测试宿主。 */
    private val memoryUseCases: MemoryUseCases? = null,
    /**
     * Dream 服务（记忆整理）。
     *
     * null = 测试宿主未装配：此时页面不展示 Dream 入口，
     * 避免「按钮点了没反应」比「没有按钮」更糟。
     */
    private val dreamService: io.zer0.memory.dream.MemoryDreamService? = null,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(MemoryUiState())
    val state: StateFlow<MemoryUiState> = _state.asStateFlow()

    // ── P0-4: 记忆分库路由 — 与生产写入侧(FactDbProvider)共用同一数据源 ──

    /**
     * 按当前选中 scope 解析生产侧实际使用的 FactStore:
     *  - null / "main" → 全局默认库(主助手事实,scope="main")
     *  - 子助手 id → FactDbProvider 分库(facts_<id>.db)
     *
     * 修复前:UI 恒用默认库 + scope 列,子助手分库的事实读不到、UI 新增/删除对 AI 无效。
     */
    /** 取某段正文（空白视为无内容）。 */
    private fun sectionContent(
        rows: List<io.zer0.memory.summary.ScopedCompiledSectionEntity>,
        key: String,
    ): String = rows.firstOrNull { it.sectionKey == key }?.content.orEmpty()

    /** 取某段来源信息（可能为 null：该段尚未编译过）。 */
    private fun sectionProvenance(
        rows: List<io.zer0.memory.summary.ScopedCompiledSectionEntity>,
        key: String,
    ): io.zer0.memory.runtime.SectionProvenance? =
        rows.firstOrNull { it.sectionKey == key }?.let { io.zer0.memory.runtime.MemoryCompiledProjection.provenanceOf(it) }

    private fun storeForScope(scope: String?): FactStore = when {
        scope == null || scope == "main" -> factStore
        else -> factDbProvider.getFactStore(scope)
    }

    private fun memoryScopeKey(scope: String?): MemoryScopeKey? {
        val normalizedScope = scope?.takeIf { it.isNotBlank() } ?: return null
        val assistantId = if (normalizedScope == "main") "default" else normalizedScope
        return MemoryScopeKey(assistantId, normalizedScope, _selectedSpaceId.value.ifBlank { "default" })
    }

    /** P0-4: 当前选中的 store(scope 为 null 时回退主助手默认库)。 */
    private val currentStore: FactStore
        get() = storeForScope(_selectedScope.value)

    /**
     * P0-4/SEC-04: 按 (scope, id) 定位事实所属的 store。
     *
     * 各分库的自增 id 会撞号——同一个 id 在主库与某个子助手库里都可能是合法记录，
     * 因此调用方必须尽量给出 [scope]；只有拿不到 scope 时才回退到当前选中的 store。
     * **不再跨分库按裸 id 扫描**：那等于用 id 猜归属，会把编辑/删除/置顶落到别的助手身上。
     */
    private suspend fun storeForFact(id: Long, scope: String? = null): FactStore {
        val scoped = scope?.takeIf { it.isNotBlank() }?.let { storeForScope(it) }
        if (scoped != null && runCatching { scoped.getById(id) }.getOrNull() != null) return scoped
        return scoped ?: currentStore
    }

    /** P0-4: "全部"视图 — 默认库 + 各子助手分库并集(按空间过滤)。 */
    private suspend fun loadFactsForAllScopes(spaceId: String): List<FactStore.Fact> {
        val main = resultOf { factStore.getBySpace(spaceId) }.getOrNull() ?: emptyList()
        val scoped = subAssistantStores().flatMap { (id, store) ->
            resultOf { store.getBySpace(spaceId) }
                .onError { msg, t -> Logger.w("MemoryViewModel", "读取子助手记忆分库失败($id): $msg", t) }
                .getOrNull() ?: emptyList()
        }
        return (main + scoped).distinctBy { it.scope to it.id }
    }

    /** P0-4: "全部"视图 — 跨分库全文搜索(按空间过滤)。 */
    private suspend fun searchAllScopes(query: String, spaceId: String): List<FactStore.Fact> {
        val main = resultOf { factStore.searchFullTextBySpace(query, spaceId) }.getOrNull() ?: emptyList()
        val scoped = subAssistantStores().flatMap { (id, store) ->
            resultOf { store.searchFullTextBySpace(query, spaceId) }
                .onError { msg, t -> Logger.w("MemoryViewModel", "搜索子助手记忆分库失败($id): $msg", t) }
                .getOrNull() ?: emptyList()
        }
        return (main + scoped).distinctBy { it.scope to it.id }
    }

    /** P0-4: 子助手 store 列表(scope id → store),仅非主助手。 */
    private fun subAssistantStores(): List<Pair<String, FactStore>> = _availableScopes.value
        .mapNotNull { option ->
            val id = option.id
            if (id == null || id == "main" || id.isBlank()) null else id to factDbProvider.getFactStore(id)
        }

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
    private var dedupInFlight = false

    /** v1.x: 立即编译防重入(编译耗 LLM 调用,避免连点重复执行)。 */
    private var compileInFlight = false

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

    /** 手动合并的草稿;null = 没有正在进行的合并。 */
    private val _mergeDraft = MutableStateFlow<MemoryMergeDraft?>(null)
    val mergeDraft: StateFlow<MemoryMergeDraft?> = _mergeDraft.asStateFlow()

    /** 手动合并进行中(生成草稿与落库两段都置位,供 UI 禁按钮防连点)。 */
    private val _mergeRunning = MutableStateFlow(false)
    val mergeRunning: StateFlow<Boolean> = _mergeRunning.asStateFlow()

    /** 合并失败提示(UI 消费后清除),null = 无错误。 */
    private val _mergeError = MutableStateFlow<String?>(null)
    val mergeError: StateFlow<String?> = _mergeError.asStateFlow()

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
                    val scopes = memoryScopesForMaintenance(_selectedScope.value, _availableScopes.value)
                    val spaceId = _selectedSpaceId.value.ifBlank { "default" }
                    resultOf {
                        var total = 0
                        for (scope in scopes) {
                            total += storeForScope(scope).dedupPass(scope = scope, spaceId = spaceId)
                        }
                        total
                    }
                        .onError { msg, t -> Logger.w("MemoryViewModel", "整理记忆去重失败: ${t?.message ?: msg}") }
                        .getOrNull()
                }
                if (merged == null) {
                    _organizeResult.value = "failed:dedup"
                } else {
                    // v1.0.92: 规则去重完成后追加 LLM 语义整合(与每日自动路径同一实现)。
                    // 失败静默跳过,不影响规则去重结果。
                    val llmMerged = withContext(Dispatchers.IO) {
                        resultOf { llmMergeDuplicates() }
                            .onError { msg, t -> Logger.w("MemoryViewModel", "整理:LLM 整合失败: ${t?.message ?: msg}") }
                            .getOrNull() ?: 0
                    }
                    _organizeStage.value = "complete"
                    _organizeResult.value = "done:${merged + llmMerged}"
                    loadAll(silent = true)
                }
            } finally {
                _organizeRunning.value = false
                if (_organizeStage.value != "complete") _organizeStage.value = null
            }
        }
    }

    // ── Dream：整理记忆的独立入口 ──────────────────────────────────────
    //
    // 与 organizeMemory 的分工：organize 做「编译 + 去重」，Dream 做「重写分段并留 revision」。
    // 两者都会改写用户记忆，所以 Dream 的每次发布都必须可回滚（restore）。

    private val _dreamStatus = MutableStateFlow<io.zer0.memory.dream.DreamStatus?>(null)
    val dreamStatus: StateFlow<io.zer0.memory.dream.DreamStatus?> = _dreamStatus.asStateFlow()

    private val _dreamRunning = MutableStateFlow(false)
    val dreamRunning: StateFlow<Boolean> = _dreamRunning.asStateFlow()

    /** Dream 结果一次性提示（成功/失败/被拒），由 UI 消费后清空。 */
    private val _dreamResult = MutableStateFlow<String?>(null)
    val dreamResult: StateFlow<String?> = _dreamResult.asStateFlow()

    /** 测试宿主/未装配时为 false，页面据此隐藏 Dream 入口。 */
    fun hasDreamService(): Boolean = dreamService != null

    fun consumeDreamResult() {
        _dreamResult.value = null
    }

    /** 刷新 Dream 状态（进入页面 / 操作后调用）。 */
    fun refreshDreamStatus() {
        val service = dreamService ?: return
        val scope = memoryScopeKey(_selectedScope.value) ?: return
        viewModelScope.launch {
            _dreamStatus.value = resultOf { service.status(scope) }.getOrNull()
        }
    }

    /**
     * 启动一次 Dream。
     *
     * 当前 sections 从数据库读 READY 行：与 service 内部 snapshotReader 同源，
     * 保证 beforeFingerprint 反映的是用户此刻真正看到的记忆。
     */
    fun startDream() {
        val service = dreamService ?: return
        val scope = memoryScopeKey(_selectedScope.value) ?: return
        if (_dreamRunning.value) return
        _dreamRunning.value = true
        viewModelScope.launch {
            try {
                val current = resultOf { service.currentSections(scope) }.getOrNull().orEmpty()
                val result = service.start(scope, io.zer0.memory.dream.DreamTrigger.MANUAL, current)
                _dreamResult.value = when (result) {
                    is io.zer0.memory.dream.DreamStartResult.Started -> "done"
                    is io.zer0.memory.dream.DreamStartResult.Rejected -> "rejected:${result.reason}"
                }
                if (result is io.zer0.memory.dream.DreamStartResult.Started) {
                    // 发布成功才刷新：否则 UI 会先显示新内容、再回滚，用户看到闪烁。
                    loadAll(silent = true)
                }
            } finally {
                _dreamRunning.value = false
                refreshDreamStatus()
            }
        }
    }

    /** 回滚到指定 revision。 */
    fun restoreDream(revisionId: String) {
        val service = dreamService ?: return
        val scope = memoryScopeKey(_selectedScope.value) ?: return
        viewModelScope.launch {
            val result = service.restore(scope, revisionId)
            _dreamResult.value = when (result) {
                is io.zer0.memory.dream.DreamRestoreResult.Restored -> "restored"
                is io.zer0.memory.dream.DreamRestoreResult.Rejected -> "rejected:${result.reason}"
            }
            if (result is io.zer0.memory.dream.DreamRestoreResult.Restored) loadAll(silent = true)
            refreshDreamStatus()
        }
    }

    fun consumeOrganizeResult() {
        _organizeResult.value = null
        if (!_organizeRunning.value) _organizeStage.value = null
    }

    // ── 手动合并记忆:选中的几条事实 → 模型草稿 → 用户确认后一次事务落库 ──

    /**
     * 事实条目所属作用域:优先用条目自带 scope,缺失时回退当前选择(与删除路径同一口径)。
     */
    private fun mergeScopeOf(item: MemoryItem): String = item.scope?.takeIf { it.isNotBlank() }
        ?: _selectedScope.value?.takeIf { it.isNotBlank() }
        ?: "main"

    /** 事实条目所属记忆空间:优先用条目自带 spaceId,缺失时回退当前选择。 */
    private fun mergeSpaceOf(item: MemoryItem): String =
        item.spaceId?.takeIf { it.isNotBlank() } ?: _selectedSpaceId.value.ifBlank { "default" }

    /**
     * 手动合并的第一步:只让模型出草稿,不改库。
     *
     * 只接受 Fact 层且 id 可解析的条目,且必须落在**同一个 FactStore**(同 scope + 同 space):
     * [FactStore.mergeFacts] 在一个事务里插新行并给源行打 merged_into_id,
     * 跨库的条目没法一次事务覆盖,提前拦下来比事后半完成更安全。
     */
    fun prepareMerge(items: List<MemoryItem>) {
        _mergeError.value = null
        if (_mergeRunning.value) return
        val targets = items.filter { it.source == "Fact" && it.id.toLongOrNull() != null }
        if (targets.size < 2) {
            _mergeError.value = getApplication<Application>().getString(R.string.memory_merge_error_min_items)
            return
        }
        val consolidator = factConsolidator
        if (consolidator == null) {
            _mergeError.value = getApplication<Application>().getString(R.string.memory_merge_error_no_model)
            return
        }
        val scopes = targets.map(::mergeScopeOf).toSet()
        val spaces = targets.map(::mergeSpaceOf).toSet()
        if (scopes.size > 1 || spaces.size > 1) {
            _mergeError.value = getApplication<Application>().getString(R.string.memory_merge_error_mixed_scope)
            return
        }
        _mergeRunning.value = true
        viewModelScope.launch {
            try {
                val texts = targets.map { it.content }
                val merged = withContext(Dispatchers.IO) {
                    resultOf { consolidator.synthesize(texts) }
                        .onError { msg, t -> Logger.w("MemoryViewModel", "手动合并生成失败: ${t?.message ?: msg}") }
                        .getOrNull()
                }
                if (merged.isNullOrBlank()) {
                    _mergeError.value = getApplication<Application>().getString(R.string.memory_merge_error_generate_failed)
                } else {
                    _mergeDraft.value = MemoryMergeDraft(
                        sources = texts,
                        mergedText = merged,
                        sourceItems = targets,
                    )
                }
            } finally {
                _mergeRunning.value = false
            }
        }
    }

    /**
     * 确认合并:用(可能被用户改过的)[editedText]做合并正文,源行退场,插入一条新事实。
     *
     * 成功后静默刷新列表:与批量删除一致,避免替换视图导致滚动位置回到顶部。
     * 失败保留草稿,用户改完可以重试。
     */
    fun confirmMerge(editedText: String) {
        val draft = _mergeDraft.value ?: return
        if (editedText.isBlank()) {
            _mergeError.value = getApplication<Application>().getString(R.string.memory_merge_error_empty_result)
            return
        }
        if (_mergeRunning.value) return
        val ids = draft.sourceItems.mapNotNull { it.id.toLongOrNull() }.distinct()
        val scope = draft.sourceItems.firstOrNull()?.let(::mergeScopeOf) ?: "main"
        val spaceId = draft.sourceItems.firstOrNull()?.let(::mergeSpaceOf) ?: "default"
        _mergeRunning.value = true
        viewModelScope.launch {
            try {
                val newId = withContext(Dispatchers.IO) {
                    resultOf {
                        storeForScope(scope).mergeFacts(ids, editedText, scope = scope, spaceId = spaceId)
                    }
                        .onError { msg, t -> Logger.w("MemoryViewModel", "手动合并落库失败: ${t?.message ?: msg}") }
                        .getOrNull()
                }
                if (newId == null) {
                    _mergeError.value = getApplication<Application>().getString(R.string.memory_merge_error_commit_failed)
                } else {
                    _mergeDraft.value = null
                    _mergeError.value = null
                    loadAll(silent = true)
                }
            } finally {
                _mergeRunning.value = false
            }
        }
    }

    /** 放弃本次合并:清掉草稿与错误提示,不动库。 */
    fun cancelMerge() {
        _mergeDraft.value = null
        _mergeError.value = null
    }

    /** UI 消费合并错误提示后调用,清除提示。 */
    fun consumeMergeError() {
        _mergeError.value = null
    }

    /**
     * F-7: 客户端筛选 — 重要程度 + 时间范围(基于 createdAt 距今天数)。
     * 不修改查询链路,仅在展示层过滤;统计与去重仍基于全量数据。
     */
    private fun filterFacts(facts: List<FactStore.Fact>): List<FactStore.Fact> {
        val importance = _importanceFilter.value
        val timeRange = _timeRangeFilter.value
        if (importance == null && timeRange == null) return facts
        val cut = timeRange?.days?.let { days -> Instant.now().minus(days, ChronoUnit.DAYS) }
        return facts.filter { f ->
            (importance == null || f.importance == importance) &&
                (cut == null || runCatching { Instant.parse(f.createdAt).isAfter(cut) }.getOrDefault(false))
        }
    }

    /**
     * F-7: 切换重要程度筛选(null=全部, 0=普通, 1=重要, 2=关键)。
     */
    fun selectImportanceFilter(importance: Int?) {
        if (_importanceFilter.value == importance) return
        _importanceFilter.value = importance
        loadAll()
    }

    /**
     * F-7: 切换时间范围筛选(null/ALL=全部, 近7天/近30天/近90天)。
     */
    fun selectTimeRangeFilter(range: MemoryTimeRange?) {
        if (_timeRangeFilter.value == range) return
        _timeRangeFilter.value = range
        loadAll()
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

    /** F-7: 重要程度筛选(null=全部, 0/1/2=普通/重要/关键)。 */
    private val _importanceFilter = MutableStateFlow<Int?>(null)
    val importanceFilter: StateFlow<Int?> = _importanceFilter.asStateFlow()

    /** F-7: 时间范围筛选(null=全部)。 */
    private val _timeRangeFilter = MutableStateFlow<MemoryTimeRange?>(null)
    val timeRangeFilter: StateFlow<MemoryTimeRange?> = _timeRangeFilter.asStateFlow()

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
        // v1.98: 订阅经验库写入信号,CRUD 后自动刷新 UI。
        // 文件存储没有 Room 的 observeAll，revision 是「有人写过」的显式信号；
        // 首帧即 emit，所以进入页面时也会加载一次。
        viewModelScope.launch {
            experienceRepository.revision.collect {
                val experiences = resultOf { experienceRepository.listAll() }.getOrNull().orEmpty()
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
        if (compileInFlight) return
        compileInFlight = true
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
            compileInFlight = false
            _compilingState.value = false
            // v1.x: 编译结果反馈(成功/失败),避免"点了没反应"
            _compileResult.value = if (success) "done" else "failed"
            // 编译完成后静默刷新:不触发 isLoading,避免替换当前视图(时间轴/列表)导致闪屏
            loadAll(silent = true)
        }
    }

    /**
     * v1.0.72 / v1.0.92: LLM 合并重复记忆 — 把相似簇(≥2 条)交给大模型合并成一条,
     * 保留所有关键信息;不同的事实不受影响。失败时静默跳过(规则去重仍由每日任务兜底)。
     *
     * v1.0.92: 实现委托给 [io.zer0.memory.fact.LlmFactConsolidator] — 与每日自动整合
     * 共用同一实现;返回实际合并的簇数,供"整理记忆"结果文案统计。
     */
    private suspend fun llmMergeDuplicates(scope: String? = _selectedScope.value): Int {
        val consolidator = factConsolidator ?: return 0
        val targets = memoryScopesForMaintenance(scope, _availableScopes.value)
        val spaceId = _selectedSpaceId.value.ifBlank { "default" }
        var merged = 0
        for (target in targets) {
            merged += resultOf {
                consolidator.consolidate(storeForScope(target), target, spaceId)
            }
                .onError { msg, t -> Logger.w("MemoryViewModel", "LLM 合并重复记忆失败($target): $msg", t) }
                .getOrNull() ?: 0
        }
        return merged
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
        if (dedupInFlight) return
        dedupInFlight = true
        _dedupState.value = true
        viewModelScope.launch {
            val merged = withContext(Dispatchers.IO) {
                val scopes = memoryScopesForMaintenance(_selectedScope.value, _availableScopes.value)
                val spaceId = _selectedSpaceId.value.ifBlank { "default" }
                resultOf {
                    var total = 0
                    for (scope in scopes) {
                        total += storeForScope(scope).dedupPass(scope = scope, spaceId = spaceId)
                    }
                    total
                }
                    .onError { msg, t -> Logger.w("MemoryViewModel", "记忆去重失败: ${t?.message ?: msg}") }
                    .getOrNull()
            }
            dedupInFlight = false
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
    /**
     * 清掉当前错误提示。
     *
     * 2026-11-20(P2-25):记忆中心的错误态此前只能靠「重新加载」或退出整页消失,
     * 它会盖住搜索框与列表;切换标签也不重置(错误来自上一个标签,却挡住下一个)。
     * 现在错误条可以手动关掉,切标签时也会自动清。
     */
    fun clearErrorTrace() {
        _state.update { it.copy(errorTrace = null) }
    }

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
                    // P0-4: 按 scope 路由到生产侧分库 —
                    //  null(全部)= 默认库 + 各子助手分库并集;"main" = 默认库;"<id>" = facts_<id>.db
                    when {
                        scope == null -> loadFactsForAllScopes(spaceId)
                        scope == "main" -> factStore.getByScopeAndSpace("main", spaceId)
                        else -> factDbProvider.getFactStore(scope).getByScopeAndSpace(scope, spaceId)
                    }
                }
                // F-7: 客户端过滤(重要程度 + 时间范围);统计仍基于全量 facts
                val visibleFacts = filterFacts(facts)
                val summaries = withContext(Dispatchers.IO) {
                    summaryManager.getAllSummaries().filter { summary ->
                        summary.matchesConversationMemoryFilter(scope, spaceId)
                    }
                }
                val sessionsById = withContext(Dispatchers.IO) {
                    sessionRepository.getSessionsByIds(summaries.map { it.sessionId }).associateBy { it.id }
                }
                // P2-33: 编译段此前读写钉死 main/default,切子助手 scope / 其他 space 后
                // 「AI 对你的理解」编辑的是错误槽位(与注入读路径错位)。统一按当前
                // scope(全部视图回退主助手)+ space 路由读。
                val sectionScope = scope ?: "main"
                // 读行级数据而不是 readSection：只有拿到 status / fingerprint 才能在页面上
                // 区分「这段是当前记忆」还是「上次投影失败留下的旧内容」。
                val compiledRows = withContext(Dispatchers.IO) {
                    resultOf { memoryCompiler.readScopedSectionRows(sectionScope, spaceId) }
                        .onError { msg, t -> Logger.w("MemoryViewModel", "readScopedSectionRows 失败: $msg", t) }
                        .getOrNull().orEmpty()
                }
                val compileFacts = sectionContent(compiledRows, MemoryCompiler.Section.FACTS.key)
                val compileToday = sectionContent(compiledRows, MemoryCompiler.Section.TODAY.key)
                val compileWeek = sectionContent(compiledRows, MemoryCompiler.Section.WEEK.key)
                val compileLongterm = sectionContent(compiledRows, MemoryCompiler.Section.LONGTERM.key)                // v0.51: 读取裁剪后的 compiledMarkdown(供"AI 对你的理解"卡片折叠展示)。
                // 与 ChatService 注入 system prompt 同源,反映 AI 真正"看到"的记忆。
                // P2-33: 同样按当前 scope/space 读取,避免展示主助手编译段。
                val compiledMarkdown = withContext(Dispatchers.IO) {
                    resultOf {
                        memoryTicker.readCompiledMemoryMarkdown(
                            locale = "zh-CN",
                            scope = sectionScope,
                            spaceId = spaceId,
                            assistantId = if (sectionScope == "main") "default" else sectionScope,
                        )
                    }
                        .onError { msg, t -> Logger.w("MemoryViewModel", "readCompiledMemoryMarkdown 失败: $msg", t) }
                        .getOrNull() ?: ""
                }
                // v2.6.23(M2): 未裁决矛盾改读 facts 行上的 pending 标记(事实级、可裁决)。
                // 旧 JSON 文本清单(只能"清除")不再读取;反思任务已改走 DB 落库。
                val pendingConflicts = withContext(Dispatchers.IO) {
                    val pendingStore = storeForScope(sectionScope)
                    resultOf { pendingStore.listPendingContradictions(sectionScope, spaceId) }
                        .onError { msg, t -> Logger.w("MemoryViewModel", "读待裁决矛盾失败: $msg", t) }
                        .getOrNull() ?: emptyList()
                }
                // 每对矛盾在两行上各挂一次 pending;只从 id 小的一方生成一条展示项
                val contradictions = pendingConflicts.mapNotNull { row ->
                    val otherId = row.conflictWithId ?: return@mapNotNull null
                    if (row.id >= otherId) return@mapNotNull null
                    val other = pendingConflicts.firstOrNull { it.id == otherId } ?: return@mapNotNull null
                    MemoryConflictUi(row.id, otherId, row.fact, other.fact, row.scope)
                }

                // 状态徽章: 「被取代」= 本行被别的行的 supersedes_id 指向,需要反查一次。
                // 集合用全量 facts 而不是 visibleFacts —— 取代方可能被重要度/时间筛选挡住,
                // 拿它自己的可见性去决定要不要标出被取代方是错的。key 带 scope 防跨分库 id 撞号。
                val supersededKeys = facts.mapNotNull { row ->
                    row.supersedesId?.let { memoryFactKey(row.scope, it) }
                }.toSet()

                val factItems = visibleFacts.map { fact ->
                    val status = memoryFactStatusOf(
                        scope = fact.scope,
                        id = fact.id,
                        mergedIntoId = fact.mergedIntoId,
                        suppressedAt = fact.suppressedAt,
                        supersededKeys = supersededKeys,
                    )
                    MemoryItem(
                        id = fact.id.toString(),
                        title = fact.fact.take(60),
                        content = fact.fact,
                        tags = fact.tags,
                        time = fact.time,
                        source = "Fact",
                        importance = fact.importance,
                        sessionId = fact.sessionId,

                        confidence = fact.confidence,
                        lastHitAt = fact.lastHitAt,
                        hitCount = fact.hitCount,
                        createdAt = fact.createdAt,
                        // v8: 透传 scope,供 UI 显示徽章(主助手=默认色,子助手=tertiary 色)
                        scope = fact.scope,
                        // v2.6.2（记忆审计）: 透传 spaceId —— 旧实现不设，UI 项失去归属信息。
                        spaceId = fact.spaceId,
                        // B4-05: 透传置顶时间
                        pinnedAt = fact.pinnedAt,
                        // v9: 透传 category,供新 UI 按分类筛选与展示
                        category = fact.category,
                        // 状态徽章: 已合并/已出场读本行字段,被取代看反查集合
                        mergedIntoId = fact.mergedIntoId,
                        suppressed = status.suppressed,
                        superseded = status.superseded,
                        // v2.6.23(M1/M2): 证据与未裁决矛盾透传给卡片
                        evidenceQuote = fact.evidenceQuote,
                        evidenceMessageId = fact.evidenceMessageId,
                        pendingConflictWith = fact.conflictWithId.takeIf { fact.conflictStatus == "pending" },
                    )
                }
                val summaryItems = projectConversationMemoryItems(
                    summaries = summaries,
                    sessionsById = sessionsById,
                    fallbackTitle = { sessionId ->
                        getApplication<Application>().getString(
                            R.string.memory_summary_title,
                            sessionId.take(8),
                        )
                    },
                ).map(ConversationMemoryItem::toMemoryItem)
                // v2.6.7: 四段编译产物逐行超 140 字符，改为表驱动构造
                val compileSections = listOf(
                    Triple("facts", R.string.memory_compile_section_facts, compileFacts),
                    Triple("today", R.string.memory_compile_section_today, compileToday),
                    Triple("week", R.string.memory_compile_section_week, compileWeek),
                    Triple("longterm", R.string.memory_compile_section_longterm, compileLongterm),
                )
                val app = getApplication<Application>()
                val compileItems = compileSections.mapNotNull { (key, labelRes, content) ->
                    if (content.isBlank()) return@mapNotNull null
                    // 段落来源一并带到条目上：UI 据此显示「状态 · 更新时间」，
                    // 让「这段是当前记忆还是失败留下的旧内容」在界面上可判断。
                    val provenance = sectionProvenance(compiledRows, key)
                    MemoryItem(
                        id = key,
                        title = app.getString(labelRes),
                        content = content,
                        tags = provenance?.let { listOf(it.status.name, it.sourceFingerprint) }
                            ?.filter { value -> value.isNotBlank() }
                            ?: emptyList(),
                        time = provenance?.updatedAt,
                        source = "Compile",
                    )
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
                        contradictions = contradictions,
                        factCount = visibleFacts.size,
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
                    val nowText = java.text.SimpleDateFormat(
                        MuseDateFormats.DATE_TIME_FULL_SEC,
                        java.util.Locale.getDefault(),
                    ).format(java.util.Date())
                    appendLine(
                        getApplication<Application>().getString(R.string.memory_error_trace_time) + nowText,
                    )
                    appendLine(getApplication<Application>().getString(R.string.memory_error_trace_exception) + t.javaClass.name)
                    appendLine(getApplication<Application>().getString(R.string.memory_error_trace_message) + t.message)
                    val deviceText = "${android.os.Build.BRAND} ${android.os.Build.MODEL} " +
                        "(Android ${android.os.Build.VERSION.RELEASE}, SDK ${android.os.Build.VERSION.SDK_INT})"
                    appendLine(
                        getApplication<Application>().getString(R.string.memory_error_trace_device) + deviceText,
                    )
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
            // 审查修复 (B-19): 搜索按当前 scope/space 过滤,与 loadAll 口径一致 —
            // scope 非 null 走 searchFullTextScoped(scope+space 双重),scope=null(全部)
            // 走 searchFullTextBySpace(仅按 space 隔离),spaceId 恒过滤,避免跨 space/scope 泄漏。
            val scope = _selectedScope.value
            val spaceId = _selectedSpaceId.value
            val results = withContext(Dispatchers.IO) {
                // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                resultOf {
                    val unified = memoryUseCases
                    val unifiedScope = memoryScopeKey(scope)
                    if (unified != null && unifiedScope != null) {
                        when (
                            val result = unified.search(
                                MemorySearchRequest(
                                    scope = unifiedScope,
                                    query = query,
                                    limit = 50,
                                    includeSuppressed = true,
                                ),
                            )
                        ) {
                            is MemorySearchResult.Found -> result.facts
                            MemorySearchResult.Empty -> emptyList()
                        }
                    } else if (scope == null) {
                        // 全部作用域需要逐分库读取，统一入口一次只接受一个可信 scope。
                        searchAllScopes(query, spaceId)
                    } else {
                        storeForScope(scope).searchFullTextScoped(query, scope, spaceId)
                    }
                }.onError { msg, t -> Logger.w("MemoryViewModel", "searchFullTextScoped 失败: $msg", t) }
                    .getOrNull() ?: emptyList()
            }
            // F-7: 搜索结果同样应用重要程度/时间范围筛选,与列表口径一致
            val filteredResults = filterFacts(results)
            val items = filteredResults.map { fact ->
                MemoryItem(
                    id = fact.id.toString(),
                    title = fact.fact.take(60),
                    content = fact.fact,
                    tags = fact.tags,
                    time = fact.time,
                    source = "Fact",
                    importance = fact.importance,
                    sessionId = fact.sessionId,

                    confidence = fact.confidence,
                    lastHitAt = fact.lastHitAt,
                    hitCount = fact.hitCount,
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
    /**
     * 批量删除事实。
     *
     * 逐条删，但**只刷新一次**：单条路径每删一条就 loadAll()，列表会被重置回顶部，
     * 想连删几条就得反复滑回原位。批量路径统一用 silent 刷新收尾，
     * 既不会闪一下加载中，也不会把滚动位置拉回去。
     */
    fun deleteFacts(items: List<MemoryItem>) {
        val targets = items.filter { it.source == "Fact" && it.id.toLongOrNull() != null }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val purgeTargets = mutableSetOf<io.zer0.memory.compile.MemoryCompileTarget>()
                targets.forEach { item ->
                    val id = item.id.toLongOrNull() ?: return@forEach
                    val requestedScope =
                        item.scope?.takeIf { it.isNotBlank() } ?: _selectedScope.value ?: "main"
                    val requestedAssistantId = if (requestedScope == "main") "default" else requestedScope
                    val requestedSpaceId = _selectedSpaceId.value.ifBlank { "default" }
                    resultOf {
                        storeForFact(id, item.scope)
                            .delete(id, requestedAssistantId, requestedScope, requestedSpaceId)
                    }.onSuccess { deleted ->
                        if (deleted) {
                            purgeTargets += io.zer0.memory.compile.MemoryCompileTarget(
                                assistantId = requestedAssistantId,
                                scope = requestedScope,
                                spaceId = requestedSpaceId,
                            )
                        }
                    }.onError { msg, _ ->
                        Logger.w("MemoryVM", "批量删除事实失败: $msg")
                    }
                }
                purgeTargets.forEach { target ->
                    resultOf { memoryCompiler.purgeTombstonedFacts(target) }
                        .onError { msg, t -> Logger.w("MemoryVM", "purgeTombstonedFacts 失败: $msg", t) }
                }
            }
            loadAll(silent = true)
        }
    }

    fun deleteFact(factId: String, scope: String? = null) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val id = factId.toLongOrNull()
                if (id != null) {
                    // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                    // P0-4: 按 factId 定位实际所在 store("全部"视图下子助手事实也能删)
                    val requestedScope = scope?.takeIf { it.isNotBlank() } ?: _selectedScope.value ?: "main"
                    val requestedAssistantId = if (requestedScope == "main") "default" else requestedScope
                    val requestedSpaceId = _selectedSpaceId.value.ifBlank { "default" }
                    val unified = memoryUseCases
                    val unifiedScope = memoryScopeKey(requestedScope)
                    val deleteResult = if (unified != null && unifiedScope != null) {
                        resultOf {
                            when (unified.delete(io.zer0.memory.runtime.DeleteMemoryRequest(unifiedScope, id))) {
                                is io.zer0.memory.runtime.DeleteMemoryResult.Deleted -> true
                                io.zer0.memory.runtime.DeleteMemoryResult.Missing -> false
                                is io.zer0.memory.runtime.DeleteMemoryResult.Rejected -> false
                            }
                        }
                    } else {
                        val store = storeForFact(id, scope)
                        resultOf { store.delete(id, requestedAssistantId, requestedScope, requestedSpaceId) }
                    }
                    deleteResult
                        .onError { msg, t -> MuseToast.show(getApplication<Application>().getString(R.string.memory_delete_failed, msg)) }
                        .onSuccess { deleted ->
                            if (deleted) {
                                // S-04: 剔除编译产物中已删事实,防止"删除后仍注入/从摘要复活"
                                val target = io.zer0.memory.compile.MemoryCompileTarget(
                                    assistantId = requestedAssistantId,
                                    scope = requestedScope,
                                    spaceId = requestedSpaceId,
                                )
                                resultOf { memoryCompiler.purgeTombstonedFacts(target) }
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
    /** 修订历史（“来龙去脉”面板展示当前查看的那条事实）。 */
    private val _factRevisions = MutableStateFlow<List<io.zer0.memory.fact.FactRevisionEntity>>(emptyList())
    val factRevisions: StateFlow<List<io.zer0.memory.fact.FactRevisionEntity>> = _factRevisions.asStateFlow()

    /**
     * 加载某条事实的修订历史。
     *
     * 事实被编辑/合并时会留下修订记录（旧值/新值/时间/原因），这里只是把它读出来给用户看 ——
     * “这条记忆被改过什么”从此可审计。
     */
    fun loadFactRevisions(factId: String, scope: String? = null) {
        viewModelScope.launch {
            val id = factId.toLongOrNull() ?: return@launch
            _factRevisions.value = runCatching { storeForFact(id, scope).getRevisions(id) }
                .getOrDefault(emptyList())
        }
    }

    /**
     * 回滚到指定修订。
     *
     * 回滚本身会再记一条新修订（走 update 路径），所以回滚之后仍可再次回滚，不存在“退不回来”。
     */
    fun revertFactToRevision(factId: String, revisionId: Long, scope: String? = null) {
        viewModelScope.launch {
            val id = factId.toLongOrNull() ?: return@launch
            val ok = runCatching { storeForFact(id, scope).revertToRevision(id, revisionId) }
                .getOrDefault(false)
            MuseToast.show(
                getApplication<Application>().getString(
                    if (ok) R.string.memory_revision_reverted else R.string.memory_revision_revert_failed,
                ),
            )
            if (ok) loadFactRevisions(factId, scope)
        }
    }

    fun toggleFactPinned(factId: String, scope: String? = null) {
        viewModelScope.launch {
            val id = factId.toLongOrNull() ?: return@launch
            val fact = withContext(Dispatchers.IO) { storeForFact(id, scope).getById(id) } ?: return@launch
            val pinned = fact.pinnedAt == null
            try {
                if (pinned) {
                    pinFact(id, fact.fact, scope)
                } else {
                    unpinFact(id, fact.fact, scope)
                }
            } catch (e: CancellationException) {
                // v1.78 (H3): 必须重抛协程取消信号,否则会破坏协程取消语义
                throw e
            } catch (t: Throwable) {
                Logger.w("MemoryViewModel", "toggleFactPinned 异常: ${t.message}", t)
                val msg = if (pinned) R.string.memory_pin_failed else R.string.memory_unpin_failed
                MuseToast.show(getApplication<Application>().getString(msg))
            }
            loadAll()
        }
    }

    /**
     * 置顶记忆精确去重（不调模型）。
     *
     * 内容去首尾空白、内部空白压缩、忽略大小写后相同的条目会被合并，
     * 同组保留创建最早的一条。完成后重载列表并提示合并数量。
     */
    fun dedupPinnedExact() {
        viewModelScope.launch {
            val selectedScope = memoryScopeKey(_selectedScope.value)
            val removed = if (memoryUseCases != null && selectedScope != null) {
                resultOf {
                    when (
                        val result = memoryUseCases.dedupPinned(
                            io.zer0.memory.runtime.DedupPinnedRequest(
                                scope = selectedScope,
                                includeGlobal = selectedScope.assistantId == "default",
                            ),
                        )
                    ) {
                        is io.zer0.memory.runtime.DedupPinnedResult.Removed -> result.count
                    }
                }
            } else {
                resultOf { pinnedMemoryStore.dedupExact() }
            }
                .onError { msg, t -> Logger.w("MemoryViewModel", "置顶去重失败: $msg", t) }
                .getOrNull()
                ?: 0
            if (removed > 0) {
                MuseToast.show(
                    getApplication<Application>().resources.getQuantityString(
                        R.plurals.memory_pinned_dedup_done,
                        removed,
                        removed,
                    ),
                )
            } else {
                MuseToast.show(getApplication<Application>().getString(R.string.memory_pinned_dedup_none))
            }
            loadAll()
        }
    }

    /**
     * 审查修复 (B-18): 置顶 — 先写 PinnedMemoryStore(add 成功),再 setPinned。
     * 顺序保证: 注入侧(固定记忆段)与 facts.pinnedAt 的一致性以 PinnedMemoryStore 为先;
     * 若 setPinned 失败则回滚已成功的 add,避免 UI 与注入不一致。
     */
    private suspend fun pinFact(id: Long, content: String, scope: String? = null) {
        val unified = memoryUseCases
        val unifiedScope = memoryScopeKey(scope)
        if (unified != null && unifiedScope != null) {
            val pinnedResult = resultOf {
                unified.pin(io.zer0.memory.runtime.PinMemoryRequest(unifiedScope, content))
            }.onError { msg, t -> Logger.w("MemoryViewModel", "统一置顶写入失败: $msg", t) }
                .getOrNull()
            val pinnedId = (pinnedResult as? io.zer0.memory.runtime.PinMemoryResult.Pinned)?.id
            if (pinnedId == null) {
                MuseToast.show(getApplication<Application>().getString(R.string.memory_pin_failed))
                return
            }
            val setResult = resultOf { storeForFact(id, scope).setPinned(id, pinned = true) }
                .onError { msg, t -> Logger.w("MemoryViewModel", "统一置顶状态同步失败: $msg", t) }
            if (setResult.isError) {
                resultOf {
                    unified.unpin(io.zer0.memory.runtime.UnpinMemoryRequest(unifiedScope, pinnedId, null))
                }.onError { msg, t -> Logger.w("MemoryViewModel", "统一置顶回滚失败: $msg", t) }
                MuseToast.show(getApplication<Application>().getString(R.string.memory_pin_failed))
            }
            return
        }
        val pinnedAssistantId = scope?.takeIf { it.isNotBlank() && it != "main" } ?: "default"
        // 先写 PinnedMemoryStore(add 成功)再 setPinned
        val addResult = resultOf { pinnedMemoryStore.add(content, assistantId = pinnedAssistantId) }
            .onError { msg, t -> Logger.w("MemoryViewModel", "PinnedMemoryStore.add 失败: $msg", t) }
        if (addResult.isError) {
            // add 失败,未写入任一来源,直接提示,不清后置状态
            MuseToast.show(getApplication<Application>().getString(R.string.memory_pin_failed))
            return
        }
        val setResult = resultOf { storeForFact(id, scope).setPinned(id, pinned = true) }
            .onError { msg, t -> Logger.w("MemoryViewModel", "toggleFactPinned.setPinned 失败: $msg", t) }
        if (setResult.isError) {
            // 回滚已成功的 add,保持两边一致
            resultOf {
                pinnedMemoryStore.removeByContentFlexibleForAssistant(
                    content = content,
                    assistantId = pinnedAssistantId,
                    includeGlobal = false,
                )
            }
                .onError { m2, t2 -> Logger.w("MemoryViewModel", "PinnedMemoryStore 置顶回滚失败: $m2", t2) }
            MuseToast.show(getApplication<Application>().getString(R.string.memory_pin_failed))
        }
    }

    /**
     * 审查修复 (B-18): 取消置顶 — 先确保 PinnedMemoryStore 移除成功,再清 facts.pinnedAt。
     * 匹配用保守的 removeByContentFlexible(精确优先,失败后退化为包含匹配),
     * 覆盖置顶内容被改写的残留;若 setPinned 失败不变更注入侧,两边仍一致。
     */
    private suspend fun unpinFact(id: Long, content: String, scope: String? = null) {
        val unified = memoryUseCases
        val unifiedScope = memoryScopeKey(scope)
        if (unified != null && unifiedScope != null) {
            val removeResult = resultOf {
                unified.unpin(io.zer0.memory.runtime.UnpinMemoryRequest(unifiedScope, null, content))
            }.onError { msg, t -> Logger.w("MemoryViewModel", "统一取消置顶失败: $msg", t) }
            if (removeResult.isError) {
                MuseToast.show(getApplication<Application>().getString(R.string.memory_unpin_failed))
                return
            }
            val setResult = resultOf { storeForFact(id, scope).setPinned(id, pinned = false) }
                .onError { msg, t -> Logger.w("MemoryViewModel", "统一置顶状态清理失败: $msg", t) }
            if (setResult.isError) {
                MuseToast.show(getApplication<Application>().getString(R.string.memory_unpin_failed))
            }
            return
        }
        val pinnedAssistantId = scope?.takeIf { it.isNotBlank() && it != "main" } ?: "default"
        val removeResult = resultOf {
            pinnedMemoryStore.removeByContentFlexibleForAssistant(
                content = content,
                assistantId = pinnedAssistantId,
                includeGlobal = pinnedAssistantId == "default",
            )
        }
            .onError { msg, t -> Logger.w("MemoryViewModel", "PinnedMemoryStore.removeByContentFlexible 失败: $msg", t) }
        if (removeResult.isError) {
            MuseToast.show(getApplication<Application>().getString(R.string.memory_unpin_failed))
            return
        }
        val setResult = resultOf { storeForFact(id, scope).setPinned(id, pinned = false) }
            .onError { msg, t -> Logger.w("MemoryViewModel", "toggleFactPinned.setPinned 失败: $msg", t) }
        if (setResult.isError) {
            MuseToast.show(getApplication<Application>().getString(R.string.memory_unpin_failed))
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
    fun editFact(factId: String, newContent: String, scope: String? = null, onResult: (Boolean) -> Unit = {}) {
        if (newContent.isBlank()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val id = factId.toLongOrNull()
                if (id == null) {
                    false
                } else {
                    // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                    // P0-4: 按 factId 定位实际所在 store
                    resultOf { storeForFact(id, scope).update(id, newContent) }
                        .onError { msg, t -> MuseToast.show(getApplication<Application>().getString(R.string.memory_edit_failed, msg)) }
                        .getOrNull() == true
                }
            }
            if (saved) loadAll()
            onResult(saved)
        }
    }

    /**
     * v4: 更新单条 Fact 的重要程度。
     * @param factId fact 的字符串 id
     * @param importance 0=普通,1=重要,2=关键
     */
    fun setFactImportance(factId: String, importance: Int, scope: String? = null) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val id = factId.toLongOrNull()
                if (id != null) {
                    // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                    // P0-4: 按 factId 定位实际所在 store
                    resultOf { storeForFact(id, scope).setImportance(id, importance) }
                        .onError { msg, t -> MuseToast.show(getApplication<Application>().getString(R.string.memory_set_failed, msg)) }
                }
            }
            loadAll()
        }
    }

    /**
     * 把底层存储异常翻译成用户能看懂的话。
     *
     * 2026-11-20(P2-24):真机上添加元事实失败，提示直接显示 `U_ILLEGAL_ARGUMENT_ERROR`。
     * 这类 `U_*` / `SQLITE_*` 是内部错误码，对用户没有信息量，也让人怀疑是不是自己填错了。
     * 已知的映射成具体解释，其余统一给「存储写入失败，请稍后重试」。
     *
     * 注意：只做展示层翻译，原始 `msg` 仍会随日志/审计记录保留（见调用处 `t`）。
     */
    private fun friendlyStorageError(msg: String): String {
        val raw = msg.trim()
        if (raw.isEmpty()) return getApplication<Application>().getString(R.string.memory_error_storage_generic)
        return when {
            raw.contains("U_ILLEGAL_ARGUMENT", ignoreCase = true) ||
                raw.contains("SQLITE_MISMATCH", ignoreCase = true) ||
                raw.contains("SQLITE_CONSTRAINT", ignoreCase = true) ->
                getApplication<Application>().getString(R.string.memory_error_storage_invalid)
            raw.contains("SQLITE_FULL", ignoreCase = true) ->
                getApplication<Application>().getString(R.string.memory_error_storage_full)
            raw.matches(Regex("^[A-Z][A-Z0-9_]{3,}$")) ->
                getApplication<Application>().getString(R.string.memory_error_storage_generic)
            else -> raw
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
    fun addFact(content: String, onResult: (Boolean) -> Unit = {}) {
        if (content.isBlank()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            // v1.78 (H6): loadAll 移出 runCatching,避免 add 成功但 loadAll 失败时
            // 错误信息显示"添加失败"(错误归因错位)
            val scope = _selectedScope.value ?: "main"
            val spaceId = _selectedSpaceId.value
            val ok = withContext(Dispatchers.IO) {
                // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                // P0-4: 写入路由到生产侧分库 — 子助手 scope 落到 facts_<id>.db,AI 才能真正读到
                resultOf {
                    storeForScope(scope).add(FactStore.Fact(fact = content.trim()), scope = scope, spaceId = spaceId)
                }.onError { msg, t ->
                    // v2.6.7: 拆行修超长（原单行超 140 字符）；trace 必须先于 copy 求值。
                    // 2026-11-20(P2-24):数据库异常消息常常只有一个内部错误码
                    // （真机上是 `U_ILLEGAL_ARGUMENT_ERROR`），直接拼进提示用户看不懂。
                    val friendly = friendlyStorageError(msg)
                    val trace = getApplication<Application>().getString(R.string.memory_add_failed, friendly)
                    _state.update {
                        it.copy(errorTrace = (it.errorTrace ?: "") + "\n" + trace)
                    }
                }.isSuccess
            }
            if (ok) loadAll()
            onResult(ok)
        }
    }

    /**
     * P2: 编辑单条 Summary 内容。
     */
    fun editSummary(sessionId: String, newContent: String, onResult: (Boolean) -> Unit = {}) {
        if (newContent.isBlank()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                resultOf {
                    val existing = summaryManager.getSummary(sessionId) ?: return@resultOf false
                    summaryManager.saveSummary(
                        sessionId,
                        existing.copy(
                            summary = newContent.trim(),
                            updatedAt = Instant.now().toString(),
                        ),
                    )
                    true
                }
                    .onError { msg, _ ->
                        MuseToast.show(getApplication<Application>().getString(R.string.memory_edit_failed, msg))
                    }
                    .getOrNull() ?: false
            }
            if (saved) loadAll()
            onResult(saved)
        }
    }

    /**
     * P2: 编辑单段 Compile 产物内容。
     * P2-33: 写目标改为当前选中 scope/space(此前钉死 main/default,子助手编辑错位)。
     */
    fun editCompile(sectionKey: String, newContent: String) {
        if (newContent.isBlank()) return
        val section = MemoryCompiler.Section.ALL.firstOrNull { it.key == sectionKey }
            ?: return
        val sectionScope = _selectedScope.value ?: "main"
        val sectionSpace = _selectedSpaceId.value
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                resultOf { memoryCompiler.writeSection(section, newContent.trim(), sectionScope, sectionSpace) }
            }
            loadAll()
        }
    }

    /**
     * v2.6.23(M2): 「暂不处理」——清掉双方的 pending,不动内容。
     * 下一轮反思若仍检测到矛盾会重新登记(数据没变,矛盾确实还在)。
     */
    fun dismissConflict(conflict: MemoryConflictUi) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                resultOf {
                    storeForScope(conflict.scope).dismissPendingConflict(conflict.idA, conflict.idB)
                }
                    .onError { msg, t -> Logger.w("MemoryViewModel", "矛盾暂不处理失败: $msg", t) }
            }
            loadAll(silent = true)
        }
    }

    private fun auditMemoryString(@androidx.annotation.StringRes resourceId: Int): String {
        val application = getApplication<Application>()
        val locale = io.zer0.muse.util.AppLocales.parse(settings.getLanguageSync())
        val context = if (locale == null) {
            application
        } else {
            val configuration = android.content.res.Configuration(application.resources.configuration)
            configuration.setLocale(locale)
            application.createConfigurationContext(configuration)
        }
        return context.getString(resourceId)
    }

    /**
     * v2.6.23(M2): 用户裁决 —— [keepId] 一句是对的,另一方按 v18 让位(不删行,可回退)。
     *
     * scope 由调用方(UI 拿冲突项的 scope)传入,跨分库时定位到正确的 store。
     */
    fun resolveConflict(keepId: Long, scope: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                resultOf { storeForScope(scope).resolveContradiction(keepId) }
                    .onError { msg, t -> Logger.w("MemoryViewModel", "矛盾裁决失败: $msg", t) }
                    .getOrNull() ?: false
            }
            if (!ok) {
                _state.update {
                    it.copy(errorTrace = auditMemoryString(R.string.audit_memory_conflict_not_applied))
                }
            }
            loadAll(silent = true)
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

    private fun isToday(createdAt: String?): Boolean {
        if (createdAt == null) return false
        return try {
            val date = java.time.Instant.parse(createdAt)
            val today = java.time.LocalDate.now()
            val itemDate = date.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            itemDate == today
        } catch (e: Exception) {
            false
        }
    }

    private fun isThisWeek(createdAt: String?): Boolean {
        if (createdAt == null) return false
        return try {
            val date = java.time.Instant.parse(createdAt)
            val today = java.time.LocalDate.now()
            val itemDate = date.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            val weekStart = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            !itemDate.isBefore(weekStart) && !itemDate.isAfter(today)
        } catch (e: Exception) {
            false
        }
    }

    private fun isThisMonth(createdAt: String?): Boolean {
        if (createdAt == null) return false
        return try {
            val date = java.time.Instant.parse(createdAt)
            val today = java.time.LocalDate.now()
            val itemDate = date.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            itemDate.year == today.year && itemDate.month == today.month
        } catch (e: Exception) {
            false
        }
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
                // 经验库按助手隔离：记忆页没有「当前助手」概念时归到默认桶，
                // 与 record_experience 工具在缺省上下文下的归属保持一致。
                // v1.78 (H6): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                resultOf {
                    experienceRepository.record(
                        assistantId = io.zer0.muse.data.experience.ExperienceLegacyImporter.DEFAULT_ASSISTANT_ID,
                        category = category.trim().ifBlank { DEFAULT_EXPERIENCE_CATEGORY },
                        content = content.trim(),
                        title = title.trim(),
                        tags = tags,
                    )
                }
                    .onError { msg, t ->
                        MuseToast.show(
                            getApplication<Application>().getString(R.string.memory_add_experience_failed, msg),
                        )
                    }
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
                    val existing = experienceRepository.listAll().firstOrNull { it.id == id }
                        ?: return@resultOf
                    experienceRepository.update(
                        existing.copy(
                            title = title.trim(),
                            content = content.trim(),
                            category = category.trim().ifBlank { DEFAULT_EXPERIENCE_CATEGORY },
                            tags = tags,
                            updatedAt = java.time.Instant.now().toString(),
                        ),
                    )
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
                    .onError { msg, t ->
                        MuseToast.show(
                            getApplication<Application>().getString(R.string.memory_delete_experience_failed, msg),
                        )
                    }
            }
        }
    }

    /**
     * 统一经验条目 → MemoryItem 转换(复用 UI 统一形态)。
     *
     * source 字段标注为 "Experience",供 UI 层区分来源。
     * tags 从 tagsJson(JSON 数组字符串)解析为 List<String>。
     */
    private fun io.zer0.memory.experience.ExperienceEntry.toMemoryItem(): MemoryItem {
        // 存储侧时间是 ISO-8601；UI 仍按原来的「年月日 时分秒」展示，
        // 解析失败时留空而不是塞一个 1970 的假时间。
        val timeText = runCatching {
            java.text.SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL_SEC, java.util.Locale.CHINA)
                .format(java.util.Date.from(java.time.Instant.parse(updatedAt)))
        }.getOrNull()
        return MemoryItem(
            id = id,
            title = title.ifBlank { content.lineSequence().firstOrNull().orEmpty().take(40) },
            content = content,
            tags = tags,
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
                    groupChatName = groupChatNameById[m.groupChatId]
                        ?: auditMemoryString(R.string.groupchat_default_name),
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
