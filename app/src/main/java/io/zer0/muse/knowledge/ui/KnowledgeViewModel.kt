package io.zer0.muse.knowledge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.muse.data.knowledge.KnowledgeJobDao
import io.zer0.muse.data.knowledge.KnowledgeV2Dao
import io.zer0.muse.knowledge.jobs.JobRunner
import io.zer0.muse.knowledge.storage.KnowledgeLifecycleService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * R8 知识库管理 ViewModel — 页面只观察状态、提交用户意图,不直接读 DAO、不解析文件
 * (计划 R8「页面无解析/直接 DAO 写入」)。
 *
 * 本类只做状态编排:
 *  - 任务页:观察 `job_items` Flow → [KnowledgeJobsUiState];取消经 [JobRunner.requestCancel](先落库);
 *  - 文档页:读取完整 revision(不是 preview),显示版本/来源/能力;保存带 expectedRevisionId;
 *  - 检索页:调用统一检索合同(与真实助手同一条链路),不另写"看起来更好"的调试搜索。
 */
class KnowledgeViewModel(
    private val jobDao: KnowledgeJobDao,
    private val v2Dao: KnowledgeV2Dao,
    private val runner: JobRunner,
    private val lifecycle: KnowledgeLifecycleService,
) : ViewModel() {

    private val _jobsState = MutableStateFlow<KnowledgeJobsUiState?>(null)
    val jobsState: StateFlow<KnowledgeJobsUiState?> = _jobsState.asStateFlow()

    private val _documentState = MutableStateFlow<KnowledgeDocumentUiState?>(null)
    val documentState: StateFlow<KnowledgeDocumentUiState?> = _documentState.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _searchState = MutableStateFlow(KnowledgeSearchUiState())
    val searchState: StateFlow<KnowledgeSearchUiState> = _searchState.asStateFlow()

    /** 检索验证页:查询文本由用户输入,检索动作走统一 [KnowledgeRetriever] 合同。 */
    fun updateSearchQuery(query: String) {
        _searchState.value = _searchState.value.copy(query = query)
    }

    fun search(
        retriever: io.zer0.muse.knowledge.retrieval.KnowledgeRetriever,
        scope: io.zer0.muse.knowledge.domain.KnowledgeScope,
        policy: io.zer0.muse.knowledge.domain.RetrievalPolicy,
        entry: io.zer0.muse.knowledge.retrieval.KnowledgeEntryType =
            io.zer0.muse.knowledge.retrieval.KnowledgeEntryType.DEBUG,
    ) {
        val query = _searchState.value.query.trim()
        if (query.isBlank()) {
            _searchState.value = _searchState.value.copy(emptyReason = "请输入问题")
            return
        }
        _searchState.value = _searchState.value.copy(running = true, emptyReason = null)
        viewModelScope.launch {
            val outcome = retriever.search(
                io.zer0.muse.knowledge.retrieval.KnowledgeSearchRequest(
                    requestId = "ui-search-${System.currentTimeMillis()}",
                    query = query,
                    entry = entry,
                    scope = scope,
                    policy = policy,
                    datasetEpoch = 0L,
                    deadlineMs = Long.MAX_VALUE,
                ),
            )
            _searchState.value = when (outcome) {
                is io.zer0.muse.knowledge.retrieval.RetrievalOutcome.Found ->
                    _searchState.value.copy(
                        running = false,
                        results = outcome.candidates.map { it.toUiResult() },
                        emptyReason = null,
                    )
                is io.zer0.muse.knowledge.retrieval.RetrievalOutcome.Partial ->
                    _searchState.value.copy(
                        running = false,
                        results = outcome.candidates.map { it.toUiResult() },
                        emptyReason = if (outcome.candidates.isEmpty()) "部分检索路径不可用" else null,
                    )
                is io.zer0.muse.knowledge.retrieval.RetrievalOutcome.NoMatch ->
                    _searchState.value.copy(running = false, results = emptyList(), emptyReason = "没有命中")
                is io.zer0.muse.knowledge.retrieval.RetrievalOutcome.EmptyScope ->
                    _searchState.value.copy(running = false, results = emptyList(), emptyReason = "检索范围为空")
                is io.zer0.muse.knowledge.retrieval.RetrievalOutcome.Unavailable ->
                    _searchState.value.copy(running = false, results = emptyList(), emptyReason = "检索不可用: ${outcome.reason}")
                is io.zer0.muse.knowledge.retrieval.RetrievalOutcome.Failed ->
                    _searchState.value.copy(running = false, results = emptyList(), emptyReason = "检索失败: ${outcome.reason}")
            }
        }
    }

    private fun io.zer0.muse.knowledge.retrieval.EvidenceCandidate.toUiResult() =
        KnowledgeSearchResultUi(
            chunkId = chunkId,
            docId = docId,
            locator = locator,
            snippet = text,
            lexicalScore = lexicalScore,
            denseScore = denseScore,
        )

    fun observeJob(jobId: String) {
        viewModelScope.launch {
            jobDao.observeJobItems(jobId).collect { items ->
                _jobsState.value = KnowledgeJobUiMapper.toUiState(jobId, items)
            }
        }
    }

    /** 取消处理:先落库(取消意图),终态由 JobRunner 的 CAS 决定。 */
    fun cancelJobItem(jobItemId: String) {
        viewModelScope.launch {
            val ok = runner.requestCancel(jobItemId)
            _message.value = if (ok) "已请求取消" else "取消失败"
        }
    }

    /** 加载文档完整 revision(不是 preview)。 */
    fun loadDocument(docId: String) {
        viewModelScope.launch {
            val doc = v2Dao.getDoc(docId)
            if (doc == null) {
                _documentState.value = null
                _message.value = "文档不存在"
                return@launch
            }
            val revisions = v2Dao.getRevisionsByDoc(docId)
            val active = revisions.firstOrNull { it.revisionId == doc.currentRevisionId }
                ?: revisions.maxByOrNull { it.revisionNo }
            _documentState.value = KnowledgeDocumentUiState(
                docId = docId,
                ownerType = doc.ownerType,
                ownerId = doc.ownerId,
                folderId = doc.folderId,
                revisionId = active?.revisionId.orEmpty(),
                revisionNo = active?.revisionNo ?: 0,
                fullContent = active?.fullContent.orEmpty(),
                sourceRefJson = doc.sourceRefJson,
                availability = if (active?.fullContent.isNullOrBlank()) "仅存储" else "可检索",
            )
        }
    }

    /** 保存正文:必须带 expectedRevisionId(乐观锁)。 */
    fun saveDocument(docId: String, expectedRevisionId: String, newContent: String) {
        viewModelScope.launch {
            when (val result = lifecycle.editDocument(docId, expectedRevisionId, newContent)) {
                is KnowledgeLifecycleService.EditResult.Ok -> {
                    _message.value = "已保存(版本 ${result.revisionNo})"
                    loadDocument(docId)
                }
                is KnowledgeLifecycleService.EditResult.Conflict -> _message.value = "内容已被更新,请刷新后重试"
                is KnowledgeLifecycleService.EditResult.NotFound -> _message.value = "文档不存在"
                is KnowledgeLifecycleService.EditResult.Invalid -> _message.value = "保存失败: ${result.reason}"
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}

/** 文档页状态 — 完整正文 + 身份/版本/来源。 */
data class KnowledgeDocumentUiState(
    val docId: String,
    val ownerType: String,
    val ownerId: String,
    val folderId: String,
    val revisionId: String,
    val revisionNo: Int,
    val fullContent: String,
    val sourceRefJson: String,
    val availability: String,
) {
    /** 来源展示不暴露凭据:只显示 scheme。 */
    val sourceLabel: String
        get() = when {
            sourceRefJson.isBlank() -> "无原件"
            sourceRefJson.contains("\"scheme\":\"content\"") -> "行内正文"
            sourceRefJson.contains("\"scheme\":\"file\"") -> "本地文件"
            else -> "外部来源"
        }
}

/** 检索页状态。 */
data class KnowledgeSearchUiState(
    val query: String = "",
    val running: Boolean = false,
    val results: List<KnowledgeSearchResultUi> = emptyList(),
    val emptyReason: String? = null,
)

data class KnowledgeSearchResultUi(
    val chunkId: String,
    val docId: String,
    val locator: String,
    val snippet: String,
    val lexicalScore: Float?,
    val denseScore: Float?,
)
