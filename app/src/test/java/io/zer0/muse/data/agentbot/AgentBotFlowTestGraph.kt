package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.room.Room
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.data.session.MessageImageStore
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.ui.agentbot.AgentBotViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import java.io.File

/**
 * 中枢页面按生产形状装配的测试图。
 *
 * 用真实仓储 / 会话 / 发布链路 + 内存 Room，只替换与本任务无关的两个边界：
 * 调度器（会拉起子 Agent）与放行列表存储（DataStore 跨用例会争用同一文件）。
 * 这样页面投影读到的是真实 Room Flow，而不是为测试另造的假实现。
 *
 * 执行器必须是同步的：Room 的失效刷新若在关库之后才跑完，会把
 * "Illegal connection pointer" 漏给下一条用例。
 */
internal class AgentBotFlowTestGraph(
    private val context: Context,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    val db: MuseDb = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
        .allowMainThreadQueries()
        .addCallback(
            object : androidx.room.RoomDatabase.Callback() {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    // 新库不会自动建 FTS4 虚拟表（它只在迁移里创建）。消息写入会同步索引，
                    // 缺表时 append 只会留下一条 FTS 失败日志；这里按生产 DDL 补齐。
                    db.execSQL(io.zer0.muse.data.session.MessageFtsDdl.createSql(useFts5 = false))
                }

                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.setForeignKeyConstraintsEnabled(true)
                }
            },
        )
        .build()

    /** 候选暂存区。目录按实例隔离，避免用例之间互相看见对方的候选。 */
    val stagingDir: File = File(context.cacheDir, "agentbot-staging-${System.nanoTime()}")
    val staging = StagedArtifactStore(stagingDir)

    val repository = AgentBotRepository(
        taskDao = db.botTaskDao(),
        runDao = db.taskRunDao(),
        stepDao = db.taskStepDao(),
        eventDao = db.botEventDao(),
        approvalDao = db.approvalRequestDao(),
        publicationDao = db.publicationOutboxDao(),
        bindingDao = db.botBindingDao(),
        now = clock,
    )

    /**
     * 换掉某一个 DAO 的仓储。用来模拟"某一次读取真的抛异常" ——
     * 读失败与"没有数据"必须在页面层可区分，靠真实 Room 造不出这种故障。
     */
    fun repositoryWith(bindingDao: BotBindingDao = db.botBindingDao()): AgentBotRepository = AgentBotRepository(
        taskDao = db.botTaskDao(),
        runDao = db.taskRunDao(),
        stepDao = db.taskStepDao(),
        eventDao = db.botEventDao(),
        approvalDao = db.approvalRequestDao(),
        publicationDao = db.publicationOutboxDao(),
        bindingDao = bindingDao,
        now = clock,
    )

    val sessions = SessionRepository(
        sessionDao = db.sessionDao(),
        messageDao = db.messageDao(),
        database = db,
        context = context,
        messageImageStore = MessageImageStore(
            File(context.cacheDir, "msg-images-${System.nanoTime()}"),
        ),
    )

    private val publication = PublicationService(db, repository, sessions)
    private val scheduler: BotTaskScheduler = mockk(relaxed = true)
    private val approvals = ApprovalService(repository)
    private val allowances: ConversationToolAllowanceStore = mockk()
    private val coordinator = ForegroundCoordinator(
        modelCaller = ForegroundCoordinator.ModelCaller { error("页面投影不应该调用模型") },
        publication = publication,
        governor = BotResourceGovernor(),
    )
    private val resultApplier = BotResultApplier(repository, staging, BotReviewWorker())
    private val results = AgentBotResultPublisher(context, publication)
    private val facade = AgentConversationFacade(repository)
    private val inbox = BotInboxProcessor(repository)

    /** 候选引用清理的入口（生产上由调度器巡检调用）。 */
    val applier: BotResultApplier get() = resultApplier

    init {
        every { allowances.allowancesFlow } returns flowOf(emptyMap<String, Set<String>>())
        coEvery { allowances.allowedTools(any()) } returns emptySet()
    }

    fun viewModel(
        restorer: TranscriptRestorer = TranscriptRestorer(sessions),
        repository: AgentBotRepository = this.repository,
    ): AgentBotViewModel = AgentBotViewModel(
        repository = repository,
        facade = facade,
        inbox = inbox,
        scheduler = scheduler,
        publication = publication,
        approvals = approvals,
        allowances = allowances,
        coordinator = coordinator,
        sessions = sessions,
        resultApplier = resultApplier,
        restorer = restorer,
        results = results,
    )

    fun close() {
        db.close()
    }
}

/** 等到页面离开 Loading。Room Flow 的首次发射在后台执行器上，advanceUntilIdle 不追踪它。 */
internal suspend fun AgentBotViewModel.awaitScopeSettled(): AgentBotScopeState = scopeState.first { it !is AgentBotScopeState.Loading }

/** 等到绑定解析完成（显式 bind 或从 bot_bindings 回读）。 */
internal suspend fun AgentBotViewModel.awaitBoundSession(): String = boundSessionId.first { it.isNotBlank() }

/** 构造一条任务行。测试只关心 scope 条件，其余字段给稳定默认值。 */
internal fun scopedTask(
    taskId: String,
    botId: String,
    originSessionId: String,
    status: String = BotTaskStatus.QUEUED,
    updatedAt: Long = 1_000L,
) = BotTaskEntity(
    taskId = taskId,
    botId = botId,
    originSessionId = originSessionId,
    requestMessageId = "msg-$taskId",
    goal = "任务 $taskId",
    acceptanceCriteria = "验收 $taskId",
    revision = 1,
    status = status,
    priority = 0,
    createdAt = updatedAt,
    updatedAt = updatedAt,
)

/** 构造一条审批请求。 */
internal fun scopedApproval(
    approvalId: String,
    botId: String,
    originSessionId: String,
    taskId: String,
    status: String = ApprovalStatus.PENDING,
    readyAt: Long = 1_000L,
) = ApprovalRequestEntity(
    approvalId = approvalId,
    botId = botId,
    originSessionId = originSessionId,
    taskId = taskId,
    runId = "run-$taskId",
    stepId = "step-$taskId",
    taskRevision = 1,
    toolCallId = "call-$approvalId",
    toolName = "workspace_write",
    argumentsRef = "args-$approvalId",
    argumentsHash = "hash-$approvalId",
    resourceVersion = "v1",
    risk = "WRITE",
    status = status,
    createdAt = readyAt,
    readyAt = readyAt,
    expiresAt = readyAt + 60_000L,
)

/** 构造一份暂存候选。内容与 id 无关，id 由暂存区生成。 */
internal fun stagedCandidate(targetRef: String) = StagedArtifactStore.Candidate(
    targetRef = targetRef,
    content = "候选内容",
    baseHash = "base-hash",
    taskRevision = 1,
)

/**
 * 绑定读取会抛异常的 DAO 包装。
 *
 * 真实 Room 在内存库里不会抛读异常，而"读失败被当成没有绑定"正是 I-2 的缺陷：
 * 顺着往下会新建会话并覆盖旧绑定。这里让读真的失败，并记录写调用次数，
 * 好让"读失败不得写绑定"能被断言，而不是靠读代码确认。
 */
internal class UnreadableBindingDao(
    private val delegate: BotBindingDao,
) : BotBindingDao {

    /** upsert 次数。读失败中止时它必须是 0。 */
    var writeCalls = 0
        private set

    override suspend fun upsert(binding: BotBindingEntity) {
        writeCalls++
        delegate.upsert(binding)
    }

    override suspend fun upsertAll(bindings: List<BotBindingEntity>) {
        writeCalls++
        delegate.upsertAll(bindings)
    }

    override suspend fun getById(botId: String): BotBindingEntity? = throw java.io.IOException("room: 读取绑定失败")

    override suspend fun listEnabled(): List<BotBindingEntity> = delegate.listEnabled()

    override fun observeAll(): kotlinx.coroutines.flow.Flow<List<BotBindingEntity>> = delegate.observeAll()

    override suspend fun getAll(): List<BotBindingEntity> = delegate.getAll()

    override suspend fun listBySession(sessionId: String): List<BotBindingEntity> = delegate.listBySession(sessionId)

    override suspend fun listByAssistant(assistantId: String): List<BotBindingEntity> = delegate.listByAssistant(assistantId)

    override suspend fun deleteBySession(sessionId: String) = delegate.deleteBySession(sessionId)

    override suspend fun deleteByAssistant(assistantId: String) = delegate.deleteByAssistant(assistantId)

    override suspend fun deleteAll() = delegate.deleteAll()
}
