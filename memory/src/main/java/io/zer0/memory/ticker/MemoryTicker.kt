package io.zer0.memory.ticker

import io.zer0.ai.core.Model
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.budget.LlmBudget
import io.zer0.memory.compile.MemoryCompiler
import io.zer0.memory.deep.DeepMemoryProcessor
import io.zer0.memory.summary.DailyStateDao
import io.zer0.memory.summary.DailyStateEntity
import io.zer0.memory.summary.SessionSummaryManager
import io.zer0.memory.time.TimeContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate

/**
 * 记忆调度器 (openhanako memory-ticker.ts 移植)。
 *
 * 触发策略:
 *  - 每 [TURNS_PER_SUMMARY] 轮: rollingSummary + compileToday + assemble
 *  - session 结束: final rollingSummary + compileToday + assemble
 *  - 日期切换: compileToday → compileWeek → compileLongterm → compileFacts → assemble → deepMemory
 *
 * 与 openhanako 的差异:
 *  - 无 session 文件路径概念(muse 用 sessionId 直查 Room)
 *  - 无 cache snapshot reflection(muse 不做 prompt cache 观测)
 *  - 无 editable facts 模式(muse 永远走 legacy)
 *  - 无 agent-id / agent-dir 可观测产物
 *  - 用 Kotlin 协程替代 setInterval,Job 跟踪替代 _activeJobs Set
 *
 * 线程安全:
 *  - [_summaryInProgress] / [_activeJobs] 用 [Mutex] 保护
 *  - [_health] 用 synchronized 保护(纯内存读写,极短临界区)
 *  - daily pipeline 用 [_dailyRunning] (AtomicBoolean.compareAndSet) 防重入
 */
class  MemoryTicker(
    private val summaryManager: SessionSummaryManager,
    private val compiler: MemoryCompiler,
    private val deepProcessor: DeepMemoryProcessor,
    private val dailyStateDao: DailyStateDao,
    /** 记忆重置水印(ISO),null 表示未重置。 */
    private val getResetAt: () -> String?,
    /** 记忆总开关。返回 false 时整个流水线跳过。 */
    private val isMemoryEnabled: () -> Boolean = { true },
    /** 调度器自身协程 scope。一般由 DI 注入 application scope。 */
    private val scope: CoroutineScope,
    /**
     * v0.32: 记忆系统高级配置(对照 openhanako memory.*)。
     *
     * 用 `() -> MemoryConfig` 闭包而非直接 [MemoryConfig] 值,因为该配置由用户在设置页
     * 实时调整(写入 DataStore),调度器每次读取都应拿到最新值,而不是构造时缓存一份。
     * 默认值 `{ MemoryConfig() }` 保证旧调用方不破坏(向后兼容)。
     *
     * 通过公开属性 [config] 暴露给外部读取;同时透传给真正使用它的下游方法:
     *  - [tokenBudget] → [LlmBudget.truncateToTokenBudget],在 [readCompiledMemoryMarkdown] 内裁剪
     *  - [compileThreshold] / [baseImportance] / [decayPerDay] / [forgetSpeed] →
     *    [MemoryCompiler.compileFacts] 与 [DeepMemoryProcessor.processDirtySessions]
     *    内的 fact 分数计算 / 衰减
     */
    private val getConfig: () -> MemoryConfig = { MemoryConfig() },
) {

    /** 当前生效的 [MemoryConfig](每次访问都重新读取闭包,保证拿到用户最新设置)。 */
    val config: MemoryConfig get() = getConfig()

    companion object {
        const val TURNS_PER_SUMMARY = 10
        const val DAILY_CHECK_INTERVAL_MS = 60L * 60 * 1000 // 1 小时
        private const val TAG = "MemoryTicker"

        /** 每日流水线 schema version。步骤结构变化时提升,使旧断点失效并一次性重算。 */
        const val DAILY_STATE_SCHEMA_VERSION = 2

        /** 每日 5 步(顺序依赖: compileDaily 必须先于 compileToday,rollDailyWindow 依赖 daily 已落盘)。 */
        val DAILY_STEP_KEYS = listOf(
            "compileDaily",
            "compileToday",
            "rollDailyWindow",
            "compileFacts",
            "deepMemory",
        )

        /** 含 rollingSummary 在内的全部 6 步(health 报告用)。 */
        val STEP_KEYS = listOf("rollingSummary") + DAILY_STEP_KEYS
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stepsSerializer = MapSerializer(String.serializer(), String.serializer())

    // ──────────────────────────────────────────────
    //  状态
    // ──────────────────────────────────────────────

    /** session → 累计轮数(自上次 rollingSummary 后)。v1.78: 改 ConcurrentHashMap 防并发修改异常。 */
    private val _turnCounts = ConcurrentHashMap<String, Int>()

    /** 正在跑 rollingSummary 的 session id(防止重入)。 */
    private val _summaryInProgress = mutableSetOf<String>()
    private val _summaryInProgressLock = Mutex()

    /** 后台 Job 集合(stop 时统一 await)。 */
    private val _activeJobs = mutableSetOf<Job>()
    private val _activeJobsLock = Any()

    // v1.78 (H7): 改 AtomicBoolean,用 compareAndSet 消除 check-then-act 竞态
    private val _dailyRunning = AtomicBoolean(false)
    @Volatile private var _lastDailyJobDate: String? = null
    @Volatile private var _stopped = false
    private var _timerJob: Job? = null

    // ──────────────────────────────────────────────
    //  健康状态
    // ──────────────────────────────────────────────

    // v1.78 (M3): 字段改 val,通过 copy() 创建新实例再赋值,保证可见性与不可变语义
    data class StepHealth(
        val lastSuccessAt: String? = null,
        val lastErrorAt: String? = null,
        val lastErrorMsg: String? = null,
        val failCount: Int = 0,
    )

    private val _health = STEP_KEYS.associateWith { StepHealth() }.toMutableMap()
    private val _healthLock = Any()

    private val _healthFlow = MutableStateFlow<Map<String, StepHealth>>(emptyMap())
    val healthFlow: StateFlow<Map<String, StepHealth>> = _healthFlow.asStateFlow()

    /** 错误去重签名(label|msg),相同根因只在 console 打一次。v1.78: 加 @Volatile 防并发脏读。 */
    @Volatile
    private var _lastErrorSig: String? = null

    private fun markSuccess(stepKey: String) {
        synchronized(_healthLock) {
            val h = _health[stepKey] ?: return
            _health[stepKey] = h.copy(
                lastSuccessAt = Instant.now().toString(),
                lastErrorAt = null,
                lastErrorMsg = null,
                failCount = 0,
            )
            publishHealth()
        }
    }

    private fun markFailure(stepKey: String, err: Throwable) {
        synchronized(_healthLock) {
            val h = _health[stepKey] ?: return
            _health[stepKey] = h.copy(
                lastErrorAt = Instant.now().toString(),
                lastErrorMsg = err.message ?: err.toString(),
                failCount = h.failCount + 1,
            )
            publishHealth()
        }
    }

    private fun publishHealth() {
        _healthFlow.value = _health.toMap()
    }

    private fun logStepError(label: String, err: Throwable) {
        val msg = err.message ?: err.toString()
        val sig = "$label|$msg"
        if (sig == _lastErrorSig) {
            // 同一根因重复 → 只 debug log,不打 warn
            Logger.d(TAG, "$label (dup suppressed): $msg")
            return
        }
        _lastErrorSig = sig
        Logger.w(TAG, "$label 失败: $msg", err)
    }

    private fun markStepRecovered(label: String) {
        if (_lastErrorSig == null) return
        val prev = _lastErrorSig
        _lastErrorSig = null
        Logger.i(TAG, "$label 恢复正常(之前: $prev)")
    }

    /** 返回每个步骤的健康快照(深拷贝,调用方安全持有)。 */
    fun getHealthStatus(): Map<String, StepHealth> = synchronized(_healthLock) {
        _health.mapValues { it.value.copy() }
    }

    // ──────────────────────────────────────────────
    //  Job 跟踪
    // ──────────────────────────────────────────────

    private fun trackJob(job: Job) {
        synchronized(_activeJobsLock) { _activeJobs.add(job) }
        job.invokeOnCompletion { synchronized(_activeJobsLock) { _activeJobs.remove(job) } }
    }

    /** 在 [scope] 里 launch 一个被跟踪的 fire-and-forget 任务。 */
    private fun launchTracked(block: suspend CoroutineScope.() -> Unit): Job {
        val job = scope.launch(context = Dispatchers.IO, block = block)
        trackJob(job)
        return job
    }

    // ──────────────────────────────────────────────
    //  Daily state 持久化
    // ──────────────────────────────────────────────

    private data class DailyContext(
        val logicalDate: String,
        val resetAt: String?,
        val factsMode: String = "legacy",
    )

    private fun currentContext(): DailyContext {
        val zone = TimeContext.resolveTimeZone(TimeContext.DEFAULT_TIMEZONE)
        val logicalDate = TimeContext.getLogicalDay(Instant.now(), zone).logicalDate.toString()
        return DailyContext(
            logicalDate = logicalDate,
            resetAt = getResetAt(),
            factsMode = "legacy",
        )
    }

    private fun normalizeResetAt(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value).toString() }.getOrNull()
    }

    // v1.78 (H5): 包装 suspend DAO 调用必须用 resultOf,避免吞 CancellationException
    private suspend fun readDailyState(): DailyStateEntity? = resultOf {
        dailyStateDao.get()
    }.getOrNull()

    private suspend fun writeDailyState(
        context: DailyContext,
        completedSteps: Map<String, String>,
        dailyCompletedAt: String?,
    ) {
        val entity = DailyStateEntity(
            schemaVersion = DAILY_STATE_SCHEMA_VERSION,
            logicalDate = context.logicalDate,
            resetAt = context.resetAt,
            factsMode = context.factsMode,
            completedSteps = json.encodeToString(stepsSerializer, completedSteps),
            dailyCompletedAt = dailyCompletedAt,
            updatedAt = Instant.now().toString(),
        )
        // v1.78 (H5): 包装 suspend DAO 调用必须用 resultOf,避免吞 CancellationException
        resultOf { dailyStateDao.upsert(entity) }.onError { msg, t ->
            Logger.w(TAG, "daily state write failed: $msg", t)
        }
    }

    /**
     * 从 Room 读出当天已完成步骤。
     * context 不匹配时返回空 map(等价于"重置进度")。
     */
    private suspend fun restoreDailyProgress(context: DailyContext): Map<String, String> {
        val state = readDailyState() ?: return emptyMap()
        if (state.schemaVersion != DAILY_STATE_SCHEMA_VERSION) {
            Logger.i(TAG, "daily state schema 不匹配(${state.schemaVersion} != $DAILY_STATE_SCHEMA_VERSION),重置进度")
            return emptyMap()
        }
        if (state.logicalDate != context.logicalDate) return emptyMap()
        if (normalizeResetAt(state.resetAt) != context.resetAt) return emptyMap()
        if (state.factsMode != context.factsMode) return emptyMap()
        return runCatching {
            json.decodeFromString(stepsSerializer, state.completedSteps)
        }.getOrElse {
            Logger.w(TAG, "restoreDailyProgress: completedSteps JSON 解析失败,重置进度: ${it.message}")
            emptyMap()
        }
    }

    // ──────────────────────────────────────────────
    //  内部: rollingSummary
    // ──────────────────────────────────────────────

    private suspend fun doRollingSummary(
        sessionId: String,
        messages: List<UIMessage>,
        model: Model?,
        locale: String,
        timeZone: String,
        trigger: String,
        assistantId: String = "",
    ) {
        // 重入保护: 同一 session 不并发跑两次 rolling
        _summaryInProgressLock.withLock {
            if (sessionId in _summaryInProgress) return
            _summaryInProgress.add(sessionId)
        }
        try {
            summaryManager.rollingSummary(sessionId, messages, model, locale, timeZone, assistantId)
            Logger.d(TAG, "rolling summary updated: ${sessionId.take(8)}… (trigger=$trigger)")
            markSuccess("rollingSummary")
            markStepRecovered("滚动摘要")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            markFailure("rollingSummary", e)
            logStepError("滚动摘要 ($sessionId)", e)
        } finally {
            _summaryInProgressLock.withLock { _summaryInProgress.remove(sessionId) }
        }
    }

    // ──────────────────────────────────────────────
    //  内部: compileToday + assemble
    // ──────────────────────────────────────────────

    private suspend fun doCompileTodayAndAssemble(
        model: Model?,
        locale: String,
        timeZone: String,
    ) {
        try {
            compiler.compileToday(summaryManager, model, locale, timeZone)
            // assemble 在 muse 是 lazy 的:readCompiledMemoryMarkdown 由 ChatService 实时调用
            // 这里不显式触发文件写,只通知 health
            markSuccess("compileToday")
            markStepRecovered("compileToday")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            markFailure("compileToday", e)
            logStepError("compileToday", e)
        }
    }

    // ──────────────────────────────────────────────
    //  内部: daily pipeline(按天滚动传送带,5 步带 checkpoint)
    // ──────────────────────────────────────────────

    private suspend fun doDaily(model: Model?, locale: String, timeZone: String) {
        // v1.78 (H7): 用 compareAndSet 原子化 check-then-act,消除竞态
        if (!_dailyRunning.compareAndSet(false, true)) return
        try {
            val context = currentContext()
            var completed = restoreDailyProgress(context)
            Logger.i(TAG, "每日任务开始 (${context.logicalDate})")
            var hasFailed = false

            // Step 0: compileDaily——把已经翻篇的昨天蒸馏成 memory/daily/{date}.md。
            // 必须先于 compileToday 执行:compileDaily 读取的"昨天最终版今日草稿"是这一刻仍
            // 躺在 TODAY section 里的内容;compileToday 一旦先跑,日期切换会把 today 重置为
            // 新一天的空白草稿,昨天的草稿就再也读不到了。
            if ("compileDaily" !in completed) {
                try {
                    val yesterday = runCatching {
                        LocalDate.parse(context.logicalDate).minusDays(1).toString()
                    }.getOrNull()
                    if (yesterday != null) {
                        val yesterdayDraft = compiler.readSection(MemoryCompiler.Section.TODAY)
                        compiler.compileDaily(summaryManager, yesterday, yesterdayDraft, model, locale, timeZone)
                        completed = completed + ("compileDaily" to Instant.now().toString())
                        writeDailyState(context, completed, null)
                        markSuccess("compileDaily")
                        markStepRecovered("compileDaily")
                    } else {
                        hasFailed = true
                        val err = IllegalArgumentException("无法计算昨天日期")
                        markFailure("compileDaily", err)
                        logStepError("compileDaily", err)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    hasFailed = true
                    markFailure("compileDaily", e)
                    logStepError("compileDaily", e)
                }
            }

            // Step 1: compileToday(日期切换后刷新 today.md)
            if ("compileToday" !in completed) {
                try {
                    compiler.compileToday(summaryManager, model, locale, timeZone)
                    completed = completed + ("compileToday" to Instant.now().toString())
                    writeDailyState(context, completed, null)
                    markSuccess("compileToday")
                    markStepRecovered("compileToday(daily)")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    hasFailed = true
                    markFailure("compileToday", e)
                    logStepError("compileToday(daily)", e)
                }
            }

            // Step 2: 从 daily/ 目录零 LLM 装配 week.md(无 checkpoint,纯文件操作)
            try {
                compiler.assembleWeekFromDaily()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                hasFailed = true
                Logger.w(TAG, "assembleWeekFromDaily 失败: ${e.message}", e)
            }

            // Step 3: rollDailyWindow——把滚出 N 日窗口的 daily 条目 fold 进 longterm 并删除源文件。
            // 依赖 compileDaily 已经把昨天落盘,否则窗口判断会漏看最新一天。
            if ("rollDailyWindow" !in completed && "compileDaily" in completed) {
                try {
                    compiler.rollDailyWindow(model, locale, context.logicalDate)
                    completed = completed + ("rollDailyWindow" to Instant.now().toString())
                    writeDailyState(context, completed, null)
                    markSuccess("rollDailyWindow")
                    markStepRecovered("rollDailyWindow")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    hasFailed = true
                    markFailure("rollDailyWindow", e)
                    logStepError("rollDailyWindow", e)
                }
            }

            // Step 4: compileFacts(独立于 step 0-3)
            if ("compileFacts" !in completed) {
                try {
                    compiler.compileFacts(summaryManager, model, locale, getConfig())
                    completed = completed + ("compileFacts" to Instant.now().toString())
                    writeDailyState(context, completed, null)
                    markSuccess("compileFacts")
                    markStepRecovered("compileFacts")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    hasFailed = true
                    markFailure("compileFacts", e)
                    logStepError("compileFacts", e)
                }
            }

            // Step 5: deepMemory(独立,更新 FactStore)
            if ("deepMemory" !in completed) {
                try {
                    val result = deepProcessor.processDirtySessions(summaryManager, model, locale, getConfig())
                    completed = completed + ("deepMemory" to Instant.now().toString())
                    if (result.processed > 0) {
                        Logger.i(TAG, "deep-memory: ${result.processed} session, ${result.factsAdded} 条新事实")
                    }
                    writeDailyState(context, completed, null)
                    markSuccess("deepMemory")
                    markStepRecovered("deepMemory")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    hasFailed = true
                    markFailure("deepMemory", e)
                    logStepError("deepMemory", e)
                }
            }

            if (hasFailed) {
                val done = completed.keys.joinToString(", ")
                Logger.w(TAG, "每日任务部分失败,已完成: [$done],下次 tick 重试未完成步骤")
            } else {
                _lastDailyJobDate = context.logicalDate
                writeDailyState(context, completed, Instant.now().toString())
                Logger.i(TAG, "每日任务完成")
            }
        } finally {
            // v1.78 (H7): AtomicBoolean 用 set(false) 释放
            _dailyRunning.set(false)
        }
    }

    private fun checkDailyJob(model: Model?, locale: String, timeZone: String) {
        if (_stopped) return
        if (!isMemoryEnabled()) return
        val context = currentContext()
        if (_lastDailyJobDate != context.logicalDate) {
            launchTracked { doDaily(model, locale, timeZone) }
        }
    }

    // ──────────────────────────────────────────────
    //  公开 API
    // ──────────────────────────────────────────────

    /**
     * 每轮对话结束后调用(ChatViewModel 在收到 assistant 完整回复后调)。
     *
     * fire-and-forget: 立即返回,rollingSummary + compileToday 在后台跑。
     *
     * @param sessionId 当前会话 id
     * @param messages 完整对话历史(含本轮 user + assistant)
     * @param model 摘要用的模型(为 null 时由 LLM 客户端选默认)
     * @param locale 语言(zh-CN / en-US)
     * @param timeZone 时区
     */
    fun notifyTurn(
        sessionId: String,
        messages: List<UIMessage>,
        model: Model?,
        locale: String = "zh-CN",
        timeZone: String = TimeContext.DEFAULT_TIMEZONE,
        assistantId: String = "",
    ) {
        if (_stopped) return
        if (!isMemoryEnabled()) return
        // v1.78: 原子 read-modify-write,防止并发切会话时丢计数
        // L1: ConcurrentHashMap.merge 对 absent key 会写入 defaultValue(1),且累加永不返回 null,
        // 故运行期永不返回 null;?: 1 为死代码,已移除。merge 签名返回 V?,用 !! 断言非空以匹配实际语义。
        // 注意:用 lambda 替代 Int::plus,避免 Kotlin 严格可空检查下 Int::plus 被解析为 Int? 接收者
        val count = _turnCounts.merge(sessionId, 1) { a, b -> a + b }!!

        if (count % TURNS_PER_SUMMARY == 0) {
            launchTracked {
                doRollingSummary(sessionId, messages, model, locale, timeZone, "threshold", assistantId)
                doCompileTodayAndAssemble(model, locale, timeZone)
            }
        }
        checkDailyJob(model, locale, timeZone)
    }

    /**
     * Session 切换或 dispose 前调用(final pass)。
     *
     * fire-and-forget: 立即返回,后台跑 rollingSummary + compileToday。
     *
     * @return 后台 Job,dispose 场景可 await;switch/close 场景忽略即可
     */
    fun notifySessionEnd(
        sessionId: String,
        messages: List<UIMessage>,
        model: Model?,
        locale: String = "zh-CN",
        timeZone: String = TimeContext.DEFAULT_TIMEZONE,
        assistantId: String = "",
    ): Job {
        if (_stopped) return scope.launch { /* no-op */ }
        val count = _turnCounts.remove(sessionId) ?: 0
        if (count == 0) return scope.launch { /* no-op */ }
        if (!isMemoryEnabled()) return scope.launch { /* no-op */ }
        return launchTracked {
            try {
                doRollingSummary(sessionId, messages, model, locale, timeZone, "session_end", assistantId)
                doCompileTodayAndAssemble(model, locale, timeZone)
            } catch (e: CancellationException) {
                // v1.78 (H3): 必须重抛协程取消信号,否则会破坏协程取消语义
                throw e
            } catch (e: Throwable) {
                Logger.w(TAG, "notifySessionEnd 后台失败: ${e.message}")
            }
        }
    }

    /**
     * 强制刷新指定 session 的摘要(日记等功能调用前确保摘要最新)。
     */
    fun flushSession(
        sessionId: String,
        messages: List<UIMessage>,
        model: Model?,
        locale: String = "zh-CN",
        timeZone: String = TimeContext.DEFAULT_TIMEZONE,
        assistantId: String = "",
    ): Job {
        if (_stopped) return scope.launch { /* no-op */ }
        if (!isMemoryEnabled()) return scope.launch { /* no-op */ }
        return launchTracked {
            doRollingSummary(sessionId, messages, model, locale, timeZone, "manual", assistantId)
        }
    }

    /**
     * 启动每小时的 daily check timer(备用触发,不依赖用户对话)。
     * 主触发是 [notifyTurn] 末尾的 [checkDailyJob]。
     *
     * v1.78: 修复 stop() 后不可重启的 bug — start() 时重置 _stopped=false。
     * 用户在设置页关闭再打开记忆功能后,Ticker 现在可以正常重启。
     */
    fun start() {
        // v1.78: 允许重启 — 重置 stopped 标志
        _stopped = false
        if (_timerJob != null) return
        _timerJob = scope.launch(Dispatchers.IO) {
            while (!_stopped) {
                delay(DAILY_CHECK_INTERVAL_MS)
                if (_stopped) break
                checkDailyJob(model = null, locale = "zh-CN", timeZone = TimeContext.DEFAULT_TIMEZONE)
            }
        }
        Logger.i(TAG, "v3 已启动(turn-based,daily check timer ${DAILY_CHECK_INTERVAL_MS / 60000}min)")
    }

    /**
     * 停止调度器,等待所有后台任务完成。
     *
     * v1.78: 加 30s 超时,防止卡住的 LLM 调用导致 stop() 永久阻塞。
     * 超时后强制取消所有未完成 Job。
     */
    suspend fun stop() {
        _stopped = true
        _timerJob?.cancel()
        _timerJob = null
        // v1.78: 超时等待,避免永久阻塞
        val jobs: List<Job> = synchronized(_activeJobsLock) { _activeJobs.toList() }
        if (jobs.isNotEmpty()) {
            // v1.78 (H8): join 不用 runCatching(会吞 CancellationException);
            // cancel 块用 try/finally 必达,即使 withTimeoutOrNull 超时或 join 抛取消异常也要执行
            try {
                withTimeoutOrNull(30_000L) {
                    jobs.forEach {
                        try {
                            it.join()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            // 单个 Job join 失败不阻塞后续 Job 等待
                        }
                    }
                }
            } finally {
                // 超时或取消后强制取消仍未完成的 Job(cancel 本身不抛异常)
                synchronized(_activeJobsLock) {
                    _activeJobs.forEach { it.cancel() }
                }
            }
        }
    }

    /**
     * 手动触发一次完整编译(调试 / 启动时用)。
     * 先跑 daily job(确保 week/longterm/facts 存在),再 compileToday。
     */
    suspend fun tick(
        model: Model? = null,
        locale: String = "zh-CN",
        timeZone: String = TimeContext.DEFAULT_TIMEZONE,
    ) {
        if (_stopped) return
        if (!isMemoryEnabled()) return
        val context = currentContext()
        if (_lastDailyJobDate != context.logicalDate) {
            doDaily(model, locale, timeZone)
        }
        doCompileTodayAndAssemble(model, locale, timeZone)
    }

    /**
     * v0.45: 强制立即编译记忆(忽略 daily checkpoint,手动压缩前调用)。
     *
     * 与 [tick] 的差异:
     *  - [tick] 受 daily checkpoint 保护,当天已完成的步骤会跳过
     *  - [forceCompileNow] 直接重跑 compileFacts + deepMemory + compileToday,
     *    用于"更新记忆并压缩"场景,确保最新对话提炼成 fact 后再压缩历史
     *
     * 失败步骤记录到 health,不中断整体流程。
     */
    suspend fun forceCompileNow(
        model: Model? = null,
        locale: String = "zh-CN",
        timeZone: String = TimeContext.DEFAULT_TIMEZONE,
    ) {
        if (_stopped) return
        if (!isMemoryEnabled()) return
        // 1. 强制重跑 compileFacts(从 30 天摘要提取 fact,忽略指纹缓存外的 checkpoint)
        // v1.78 (H2): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
        resultOf {
            compiler.compileFacts(summaryManager, model, locale, getConfig())
        }.onSuccess {
            markSuccess("compileFacts")
            markStepRecovered("compileFacts(force)")
        }.onError { msg, t ->
            if (t != null) {
                markFailure("compileFacts", t)
                logStepError("compileFacts(force)", t)
            } else {
                Logger.w(TAG, "compileFacts(force) 失败: $msg")
            }
        }
        // 2. 强制重跑 deepMemory(处理 dirty sessions,提取深层事实)
        resultOf {
            val result = deepProcessor.processDirtySessions(summaryManager, model, locale, getConfig())
            if (result.processed > 0) {
                Logger.i(TAG, "forceCompileNow: deep-memory ${result.processed} session, ${result.factsAdded} 新事实")
            }
            markSuccess("deepMemory")
            markStepRecovered("deepMemory(force)")
        }.onError { msg, t ->
            if (t != null) {
                markFailure("deepMemory", t)
                logStepError("deepMemory(force)", t)
            } else {
                Logger.w(TAG, "deepMemory(force) 失败: $msg")
            }
        }
        // 3. 刷新 today 编译记忆(让 system prompt 下次注入用最新内容)
        doCompileTodayAndAssemble(model, locale, timeZone)
    }

    /**
     * 读取编译后的 memory.md(注入 system prompt 用)。
     * 由 [io.zer0.ai.ChatService] 在发送前调用,把长期记忆拼到 system 段。
     *
     * v0.32: 接入 [MemoryConfig.tokenBudget] —— 用 [LlmBudget.truncateToTokenBudget]
     * 软裁剪到目标 token 数,避免记忆过长挤占对话预算(对照 openhanako memory.token_budget)。
     * 默认值 2500 token 约等于 10KB 文本,日常记忆量足够;用户调小可压缩 prompt。
     */
    suspend fun readCompiledMemoryMarkdown(locale: String = "zh-CN"): String {
        val md = compiler.readCompiledMemoryMarkdown(locale)
        val cfg = getConfig()
        return LlmBudget.truncateToTokenBudget(md, cfg.tokenBudget)
    }
}
