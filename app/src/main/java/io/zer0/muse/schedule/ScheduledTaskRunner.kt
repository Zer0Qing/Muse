package io.zer0.muse.schedule

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.quicknote.QuickNoteDao
import io.zer0.muse.data.quicknote.QuickNoteEntity
import io.zer0.muse.data.schedule.AutomationConfig
import io.zer0.muse.data.schedule.AutomationConfig.toAction
import io.zer0.muse.data.schedule.AutomationConfig.toCondition
import io.zer0.muse.data.schedule.AutomationConfig.toIdsList
import io.zer0.muse.data.schedule.ScheduledTaskDao
import io.zer0.muse.data.schedule.ScheduledTaskEntity
import io.zer0.muse.data.schedule.ScheduledTaskExecutionEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.notification.MuseNotificationTarget
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel
import io.zer0.muse.util.GlobalCoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 17: 定时任务执行器。
 *
 * 在 App 启动时启动,每 60 秒检查一次是否有到期的定时任务。
 * 到期任务会真正执行 prompt(调用 AI + 写入会话),并弹通知(点击跳转到对应任务)。
 * 使用协程轮询而非 AlarmManager,避免 Android 后台限制。
 *
 * P1-7: 执行任务后插入一条 execution 记录(success/failed),供 UI 展示执行历史。
 *
 * 真正执行改造(按 [ProactiveMessageRunner]):
 *  - 按 task.assistantId 解析助手配置,用其 systemPrompt 作为系统消息
 *  - 调用 [ChatService.completeText](非流式)执行 task.prompt
 *  - 把用户 prompt 和 AI 回复写入一个专用会话(标题用 task.name)
 *  - replySummary 存 AI 回复正文(最多 10,000 字);失败时 errorMessage 记录真实异常
 *  - 通知内容用 AI 回复摘要
 *  - [executeTask] 对外暴露,供 UI"立即执行"按钮调试调用
 *
 * @param dao 定时任务 DAO(含 @Transaction 组合方法)
 * @param chatService 非流式调用 LLM 执行 task.prompt
 * @param sessionRepository 把用户 prompt 和 AI 回复写入会话
 * @param assistantRepository 按 task.assistantId 取助手配置(systemPrompt / temperature / maxTokens)
 * @param context 应用 Context
 * @param appScope App 全局协程作用域
 */
class ScheduledTaskRunner(
    private val dao: ScheduledTaskDao,
    private val chatService: ChatService,
    private val sessionRepository: SessionRepository,
    private val assistantRepository: AssistantRepository,
    private val context: Context,
    private val appScope: CoroutineScope,
    private val pendingMessageManager: io.zer0.muse.data.schedule.PendingMessageManager? = null,
    private val toolRegistry: ToolRegistry? = null,
    // v1.0.17: 改用 Room DAO(替代 QuickNoteStore JSON 存储)
    private val quickNoteDao: QuickNoteDao? = null,
) {
    private var job: Job? = null

    /**
     * 定点推进([computeNextRunAnchored])的结果。
     *
     * - [nextRunAt]: 下一次执行时间;<=0 表示无下次执行(once / 非法 cron / 未知 interval)
     * - [missedCount]: 这次不补跑、只记一条 missed 历史的计划轮次数(0 = 没错过)
     */
    internal data class ScheduleAdvance(val nextRunAt: Long, val missedCount: Int)

    companion object {
        const val CHANNEL_ID = "scheduled_tasks"
        const val NOTIFICATION_ID_BASE = 2000
        private const val TAG = "ScheduledTask"

        /**
         * R-TEST-11: 计算 [now] 之后的下次执行时间(纯函数,不操作 DB)。
         *
         * 定时任务间隔的唯一实现:工具建/改任务、UI 预览、改间隔后的首跑都调这里
         * (任务执行完推进调度走 [computeNextRunAnchored]),不在别处重写间隔表,
         * 避免「同一个 interval 在不同入口算出不同时间」。
         *
         * - hourly / daily / weekly: now + 固定间隔
         * - cron: 用 [CronExpression] 解析;空表达式或解析失败 → 0
         * - once / 未知 interval: 0
         *
         * 返回 <=0 表示「没有下一次执行」,而不是「马上再跑一次」:
         * [ScheduledTaskDao.recordExecutionAndScheduleNext] 见 <=0 即禁用任务,
         * 一次性任务因此执行完就退出调度,不会被算出第二次执行。
         * 建任务时问的是「首跑时间」,那个用 [initialNextRunAt]。
         *
         * 注意:这是「从现在算」的口径,只适合还没有历史计划时刻的入口(建任务、改间隔、
         * UI 预览)。任务执行完后推进调度用 [computeNextRunAnchored],锚定上次计划时刻。
         */
        internal fun computeNextRun(interval: String, cronExpr: String, now: Long): Long {
            if (interval == "cron") return nextCronRunAfter(cronExpr, now)
            val period = fixedPeriodMillis(interval) ?: return 0L
            return now + period
        }

        /**
         * 固定周期 interval 的周期长度(毫秒);cron / once / 未知 interval 返回 null。
         *
         * 间隔表只此一份:[computeNextRun] 与 [computeNextRunAnchored] 共用,
         * 避免「同一 interval 在两个口径下用两套周期」。
         */
        private fun fixedPeriodMillis(interval: String): Long? = when (interval) {
            "hourly" -> 3_600_000L
            "daily" -> 86_400_000L
            "weekly" -> 604_800_000L
            else -> null
        }

        /**
         * cron 下一次执行时间(纯函数)。空表达式 / 解析失败 / 无匹配时的取值与原口径一致。
         */
        private fun nextCronRunAfter(cronExpr: String, after: Long): Long {
            if (cronExpr.isBlank()) return 0L
            return resultOf { CronExpression.parse(cronExpr).nextRunAfter(after) }
                .onError { msg, t -> Logger.w(TAG, "Invalid cron expr: ${t?.message ?: msg}") }
                .getOrNull() ?: 0L
        }

        /**
         * 锚定上次计划时刻推进调度(纯函数,不操作 DB)。
         *
         * 为什么不能一律从 now 算:任务到期后往往晚几十秒到几小时才被执行(轮询抖动、Doze、
         * 设备关机、进程被杀)。以 now 为基准每次都会把下一次推到「现在 + 一个周期」,错过的那
         * 一轮被永久跳过且不留痕,而且每次唤醒都再漂一次,墙钟不断往后走。
         *
         * 这里改用 [scheduledAt](上次的计划时刻,即执行前的 next_run_at) 作锚点:
         * 下一次 = scheduledAt + 整数倍周期,仍然是「原计划节奏」上的点。
         * [missedCount] = 本次没有补跑的计划轮次数 = 锚点之后已经过去的计划时刻个数。
         *
         * 补跑策略:只补跑最近一次(本次执行就是那一次),更早的轮次不补跑、只记 missed ——
         * 一次补跑几十轮网络请求 / 写文件,比漏跑更糟。因为 [nextRunAt] 一定 > now,
         * 本次执行结束后任务不会在同一秒内被再次领取。
         *
         * 兜底口径:
         * - [scheduledAt] <= 0(旧数据无锚点):退回 [computeNextRun] 的「从现在算」,不计 missed
         * - [scheduledAt] > now(任务还没到期,典型场景是用户点「立即执行」,也可能是系统时间
         *   被回拨):下一次就是原定的那个计划时刻,一次也不动 —— 手动多跑一次不能把
         *   以后每天 09:00 永久改成 12:00
         * - once / 未知 interval:无下次执行(0),不计 missed,与原语义一致
         * - 周期数超过 [MAX_MISSED_SCAN]:按 [MAX_MISSED_SCAN] 记上限并从 now 重新起算,
         *   避免异常时钟(如 1970 年的锚点)把 Long 算溢出或空转上百万次
         */
        internal fun computeNextRunAnchored(
            interval: String,
            cronExpr: String,
            scheduledAt: Long,
            now: Long,
        ): ScheduleAdvance {
            val period = fixedPeriodMillis(interval)
            return when {
                period != null -> anchoredFixedPeriod(period, scheduledAt, now)
                interval == "cron" -> advanceCron(cronExpr, scheduledAt, now)
                else -> ScheduleAdvance(0L, 0)
            }
        }

        /** 固定周期(hourly / daily / weekly)的锚定推进:下一次 = 锚点 + 整数倍周期。 */
        private fun anchoredFixedPeriod(period: Long, scheduledAt: Long, now: Long): ScheduleAdvance {
            if (scheduledAt <= 0L) return ScheduleAdvance(now + period, 0)
            // 还没到期(手动「立即执行」/ 时钟回拨):原定计划时刻一次也不动
            if (scheduledAt > now) return ScheduleAdvance(scheduledAt, 0)
            val periods = (now - scheduledAt) / period
            return if (periods > MAX_MISSED_SCAN.toLong()) {
                ScheduleAdvance(now + period, MAX_MISSED_SCAN)
            } else {
                ScheduleAdvance(scheduledAt + (periods + 1) * period, periods.toInt())
            }
        }

        /**
         * cron 的锚定推进:从 [scheduledAt] 逐格前进,数出跳过了几个计划时刻。
         *
         * cron 表达式本身已经锚定墙钟(如「每天 9:00」),所以没错过时结果与
         * [computeNextRun] 一致;这里额外做的是把「跳过了几格」数出来。
         */
        private fun advanceCron(cronExpr: String, scheduledAt: Long, now: Long): ScheduleAdvance {
            if (cronExpr.isBlank()) return ScheduleAdvance(0L, 0)
            val expr = resultOf { CronExpression.parse(cronExpr) }
                .onError { msg, t -> Logger.w(TAG, "Invalid cron expr: ${t?.message ?: msg}") }
                .getOrNull() ?: return ScheduleAdvance(0L, 0)
            if (scheduledAt <= 0L) return ScheduleAdvance(expr.nextRunAfter(now), 0)
            return when {
                // 还没到期(cron 任务的手动立即执行):保持原定的下一格
                scheduledAt > now -> ScheduleAdvance(scheduledAt, 0)
                else -> scanSkippedCronSlots(expr, scheduledAt, now)
            }
        }

        /** 从锚点逐格前进到 [now] 之后,返回下一次计划时刻与中间被跳过的格数。 */
        private fun scanSkippedCronSlots(expr: CronExpression, scheduledAt: Long, now: Long): ScheduleAdvance {
            var cursor = scheduledAt
            var missed = 0
            while (missed < MAX_MISSED_SCAN) {
                val candidate = expr.nextRunAfter(cursor)
                // 不前进(含 NO_MATCH)或已跨过 now:收敛到这一格
                if (candidate <= cursor || candidate > now) return ScheduleAdvance(candidate, missed)
                missed++
                cursor = candidate
            }
            return ScheduleAdvance(expr.nextRunAfter(now), MAX_MISSED_SCAN)
        }

        /**
         * 构造一条「这几轮错过了、没有补跑」的执行记录。
         *
         * 只写一条而不是每轮一条:一小时级的任务关机三天会产生 70+ 条,把真正执行过的历史
         * 淡掉。正文由调用方传入(文案走 strings.xml 的 schedule_missed_summary,与
         * success / failed / skipped 一致),状态用 [STATUS_MISSED],不新增数据库列。
         *
         * @param now 本次补跑的时间
         */
        internal fun missedExecutionEntity(
            taskId: String,
            replySummary: String,
            now: Long,
        ): ScheduledTaskExecutionEntity = ScheduledTaskExecutionEntity(
            id = "exec-${java.util.UUID.randomUUID()}",
            taskId = taskId,
            executedAt = now,
            status = STATUS_MISSED,
            replySummary = replySummary,
            errorMessage = "",
        )

        /**
         * 新建任务 / 改间隔后的首跑时间(纯函数)。
         *
         * 与 [computeNextRun] 只差「没有下一次」这一种情况:[computeNextRun] 返回 0 是
         * 「不要再有下一次」的禁用标记([ScheduledTaskDao.recordExecutionAndScheduleNext] 见
         * <=0 就 setEnabled(false)),不能拿去落库;建任务/改间隔时一次性任务还没跑过,应当尽快
         * 跑一次,所以兜底成 now + [POLL_INTERVAL_MS]。
         *
         * 为什么兜底是 60 秒而不是 now:调度靠 [POLL_INTERVAL_MS] 轮询 + getDueTasks(next_run_at <= now),
         * 落成 now 会让任务在下一次扫描(0–60s,最坏是紧接着的那一跳)就被领取执行——用户点完
         * 「创建」退出对话框,几十秒内就看到任务已经跑完、执行历史多了一条。留一个轮询周期的缓冲。
         * 周期类 interval 走 [computeNextRun] 的正值,不受这个兜底影响。
         */
        internal fun initialNextRunAt(interval: String, cronExpr: String, now: Long): Long =
            computeNextRun(interval, cronExpr, now).takeIf { it > 0 } ?: (now + POLL_INTERVAL_MS)

        private const val POLL_INTERVAL_MS = 60_000L // 每分钟检查一次

        /**
         * 锚定推进最多数多少格 missed。
         *
         * 正常错过(关机数小时到数天)远达不到这个量级;超过它的只可能是锚点异常
         * (如时钟被回拨到 1970),此时按上限记数并从 now 重新起算,不逐格空转。
         */
        private const val MAX_MISSED_SCAN = 1_000

        /** 执行历史里「这一轮错过了、没有补跑」的状态值(与 success / failed / skipped 并列)。 */
        internal const val STATUS_MISSED = "missed"

        // 执行历史展开后展示正文;限制上限避免异常工具输出无限膨胀数据库。
        private const val EXECUTION_DETAIL_MAX_LEN = 10_000

        /**
         * B-12: 抢占式领取判定(纯函数,供单元测试直接调用)。
         *
         * [dao.claimTask] 返回受影响行数:1 = 本执行者领取成功;0 = 已被其他执行者抢先领取。
         * 行数 > 0 即判定领取成功;负数/意外值按失败处理(保守跳过,杜绝重复执行)。
         */
        internal fun claimTaskSucceeded(rowsAffected: Int): Boolean = rowsAffected > 0

        /** 单次任务 AI 调用超时(毫秒)。 */
        private const val LLM_TIMEOUT_MS = 60_000L

        /**
         * v2.6.8: 定时任务单次执行最多几轮工具调用。
         *
         * 定时任务在后台跑，没人看着；给 3 轮足够覆盖“查笔记 → 查屏幕时长 → 汇总”这类链式需求，
         * 又能避免模型陷入无限工具循环把额度烧光。
         */
        private const val MAX_TOOL_ROUNDS = 3

        /** v1.0.17: 链式任务最大递归深度,防止无限循环。 */
        private const val MAX_CHAIN_DEPTH = 10

        /** v1.0.17: 重试退避上限(毫秒,5 分钟)。 */
        private const val RETRY_BACKOFF_MAX_MS = 300_000L

        /** v1.0.17: 重试退避步长(毫秒,每次递增 1 分钟)。 */
        private const val RETRY_BACKOFF_STEP_MS = 60_000L

        /**
         * B-12: 领取后临时推进的 next_run_at 哨兵值(远未来,≈146 亿年后)。
         *
         * 领取(CAS)时把 next_run_at 临时推到该值以摘除"到期"身份,执行期间任何到期扫描/
         * 再领取都不命中;执行结束 [executeTask] 会用真实的下次执行时间覆盖它。
         */
        private const val CLAIM_NEXT_RUN_SENTINEL = Long.MAX_VALUE

        /**
         * B-26: 哨兵看门狗阈值 —— claim 后超过该时长仍停在哨兵值视为"卡死"(领取后
         * recordExecutionAndScheduleNext 事务失败回滚),允许重置 next_run_at。
         * 单次 AI 执行有 [LLM_TIMEOUT_MS](60s)上限,5 分钟远超单次耗时,不会误伤执行中任务。
         */
        private const val CLAIM_STUCK_TIMEOUT_MS = 5 * 60 * 1000L
    }

    private fun restoreBlocksScheduledWrites(taskId: String, operation: String): Boolean {
        if (!io.zer0.common.ProcessWriteGate.restoring) return false
        Logger.w(TAG, "备份恢复进行中，跳过定时任务 $taskId 的${operation}写入")
        return true
    }

    fun start() {
        createNotificationChannel()
        job?.cancel()
        job = appScope.launch(GlobalCoroutineExceptionHandler) {
            Logger.i(TAG, "Runner started")
            while (isActive) {
                tickOnce()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * v1.104 P3: 一次扫描所有到期任务并执行。
     *
     * 从 [start] 轮询循环抽出,供:
     *  - 协程轮询每分钟调用一次(原有路径)
     *  - [ScheduledTaskWorker] 在 App 进程被杀后由 WorkManager 拉起时调用一次
     *
     * 不循环 delay,调用方决定调用频率。所有异常被捕获并记录,不会向上抛出
     * (CancellationException 例外,确保协程能被正常取消)。
     */
    private suspend fun tickOnce() {
        try {
            // B-25: 后台调度总控 — 关闭时跳过整次巡检(读 DataStore 缓存值,成本低)
            if (!isScheduleWorkEnabled()) {
                Logger.i(TAG, "后台调度总控已关闭,跳过定时任务巡检")
                return
            }
            // B-7: 备份恢复(文件级替换 DB)窗口内跳过任务巡检,避免写丢失。
            if (io.zer0.common.ProcessWriteGate.restoring) {
                Logger.i(TAG, "备份恢复进行中,跳过定时任务巡检")
                return
            }
            val now = System.currentTimeMillis()
            // B-26: 哨兵看门狗 — 先回收领取后事务失败、卡死在哨兵值(CLAIM_NEXT_RUN_SENTINEL)
            // 的任务,把超时未释放的 next_run_at 重置为 now,让本轮 getDueTasks 将其重新纳入到期执行。
            try {
                recoverStuckClaimNow(now)
            } catch (e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                Logger.w(TAG, "哨兵看门狗回收失败: ${e.message}")
            }
            val dueTasks = dao.getDueTasks(now)
            dueTasks.forEach { task ->
                resultOf { executeTask(task) }
                    .onError { msg, t -> Logger.w(TAG, "Task ${task.id} execute error: ${t?.message ?: msg}") }
            }
            // Phase 3 3E: 检查并发送定时消息
            pendingMessageManager?.drainDueMessages()?.forEach { pm ->
                resultOf { deliverPendingMessage(pm) }
                    .onError { msg, t -> Logger.w(TAG, "Pending message ${pm.id} delivery error: ${t?.message ?: msg}") }
            }
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            Logger.w(TAG, "Poll error: ${e.message}")
        }
    }

    /**
     * B-25: 读取后台调度总控开关。
     *
     * ScheduledTaskRunner 未注入 SettingsRepository(保持 Koin 模块签名稳定),这里
     * 经 GlobalContext 解析;Koin 缺失/读取失败时保守返回 false，避免设置不可读时
     * 在用户不知情的情况下继续执行定时任务。
     */
    private suspend fun isScheduleWorkEnabled(): Boolean {
        val settings = resultOf {
            org.koin.core.context.GlobalContext.get().get<io.zer0.muse.data.SettingsRepository>()
        }.getOrNull()
        return scheduleWorkEnabledFromRepository(
            available = settings != null,
            value = settings?.let {
                resultOf { it.scheduleWorkEnabledFlow.first() }.getOrNull()
            },
        )
    }

    /**
     * B-26: 哨兵看门狗 —— 释放卡死在领取哨兵(CLAIM_NEXT_RUN_SENTINEL)的过期任务。
     *
     * [executeTask] 领取后若 [dao.recordExecutionAndScheduleNext] 事务失败回滚,next_run_at
     * 会停在哨兵值(远未来),此后任何到期扫描都命中不了 → 任务永久丢失。
     * 这里把"哨兵值且 updated_at 已超过 [CLAIM_STUCK_TIMEOUT_MS]"的任务重置为 now,
     * 由本轮轮询重新执行。
     *
     * 防误重置:领取哨兵本就是 [executeTask] 摘除到期身份的唯一来源;单次 AI 执行
     * 有 [LLM_TIMEOUT_MS](60s)上限,5 分钟阈值远大于单次执行耗时,不会误伤
     * "正在执行中"的任务(执行中 updated_at 很新,不会被判为过期)。
     * 仅重置仍 enabled 的任务,避免复活用户已手动禁用的任务。
     */
    private suspend fun recoverStuckClaimNow(now: Long) {
        val recovered = dao.recoverStuckClaim(
            sentinel = CLAIM_NEXT_RUN_SENTINEL,
            staleBefore = now - CLAIM_STUCK_TIMEOUT_MS,
            now = now,
        )
        if (recovered > 0) {
            Logger.w(TAG, "B-26 哨兵看门狗: 回收 $recovered 个卡在哨兵值的任务,已重置为 now 重新调度")
        }
    }

    /**
     * v1.104 P3: 供 [ScheduledTaskWorker] 调用的一次性扫描入口。
     *
     * Worker 进程被 WorkManager 拉起时,通知渠道可能尚未创建(新进程),所以这里幂等调用一次。
     * 之后委托给 [tickOnce] 完成实际扫描。
     */
    suspend fun tickOnceForWorker() {
        createNotificationChannel()
        tickOnce()
    }

    /**
     * v1.137: 执行单个自动化任务。
     *
     * 流程:
     *  0. 抢占式领取 CAS(见 B-12):执行前原子比对 next_run_at(含 ABA 哨兵防御),
     *     失败即已被人领取/删除,跳过不做任何副作用
     *  1. 评估 condition(条件触发)
     *  2. 按 action_type 执行对应动作(ai_prompt / create_quick_note / call_tool / notify)
     *  3. 成功执行后触发链式任务(next_task_ids)
     *  4. 原子记录执行历史 + 推进下次执行时间
     *
     * v1.0.17: 新增 [chainDepth] 参数用于链式任务递归深度限制;
     *  执行失败时按指数退避重试(retry_count < max_retries 时递增,达上限后重置)。
     *
     * B-12: 执行动作前先通过 [ScheduledTaskDao.claimTask] 做抢占式领取,
     *  保证轮询 / Worker / 手动"立即执行" / 链式多路不同进程不会重复执行同一到期任务。
     *
     * 任何环节失败都记录一条 failed execution,不抛异常。
     */
    suspend fun executeTask(task: ScheduledTaskEntity, chainDepth: Int = 0) {
        // v1.0.17: 链式任务深度限制,防止无限递归
        if (chainDepth >= MAX_CHAIN_DEPTH) {
            Logger.w(TAG, "Chain depth limit reached ($MAX_CHAIN_DEPTH), skip task ${task.id}")
            return
        }
        if (restoreBlocksScheduledWrites(task.id, "任务执行")) return

        val now = System.currentTimeMillis()
        // B-12: 抢占式领取 — 执行动作前先做原子 CAS,防止轮询(tickOnce)与 Worker(tickOnceForWorker)
        // 并发执行同一到期任务导致重复 AI 调用 / 重复通知。CAS 期望值用 DB 当前 next_run_at(而非调用方
        // 快照),兼容 UI 手动"立即执行"传入的过期实体;领取时把 next_run_at 推进为远未来哨兵摘除"到期"
        // 身份,后续执行者再按原到期值 CAS 必失败。执行结束由 recordExecutionAndScheduleNext 重置为真实
        // 下次执行时间。依赖 ScheduledTaskDao.claimTask 的单条原子 UPDATE 实现跨进程安全。
        val current = try {
            dao.getById(task.id)
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            Logger.w(TAG, "Task ${task.id} load failed: ${e.message}")
            null
        }
        // 任务已在执行前被删除,无法领取,跳过(同时避免对已删除任务写执行记录)
        if (current == null) {
            Logger.w(TAG, "Task ${task.id} not found, skip")
            return
        }
        // B-12(ABA 防御):若当前 next_run_at 已是领取哨兵,说明另一执行者正在执行本任务
        // (已领取且尚未重置),此时绝不能再按哨兵值 CAS(会命中而重复执行),直接跳过。
        // 哨兵值不会由正常调度产生(computeNextRun 上限为周级/退避;cron 无匹配走
        // CronExpression.NO_MATCH = MAX-1,与领取哨兵 MAX 区分,见 C-06),唯一来源就是领取动作。
        if (current.nextRunAt == CLAIM_NEXT_RUN_SENTINEL) {
            Logger.w(TAG, "Task ${task.id} is currently being executed by another executer, skip")
            return
        }

        val claimedRows = try {
            dao.claimTask(task.id, current.nextRunAt, now, CLAIM_NEXT_RUN_SENTINEL)
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            Logger.w(TAG, "Task ${task.id} claim failed: ${e.message}")
            0
        }
        if (!claimTaskSucceeded(claimedRows)) {
            Logger.w(TAG, "Task ${task.id} already claimed by another executer, skip")
            return
        }
        // 恢复可能在 CAS 领取后开始。此时不再调用模型/工具，避免把结果写进待替换数据集；
        // 领取哨兵由 watchdog 或恢复后的下一轮调度重新接管。
        if (io.zer0.common.ProcessWriteGate.restoring ||
            restoreBlocksScheduledWrites(task.id, "任务动作")
        ) {
            return
        }

        var status = "success"
        var replySummary = ""
        var errorMessage = ""

        try {
            // 1. 条件评估
            val condition = task.conditionJson.toCondition()
            if (!evaluateCondition(task, condition)) {
                status = "skipped"
                replySummary = context.getString(R.string.schedule_condition_not_met)
                // 条件不满足仍推进 schedule,避免任务卡死
            } else {
                // 2. 执行动作
                if (restoreBlocksScheduledWrites(task.id, "任务动作")) return
                val action = AutomationConfig.Action(
                    type = task.actionType,
                    config = task.actionConfigJson.toAction().config,
                )
                val output = executeAction(task, action)
                replySummary = output.take(EXECUTION_DETAIL_MAX_LEN)
                // 3. 触发链式任务
                triggerChainTasks(task, chainDepth)
            }
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            Logger.w(TAG, "Task ${task.id} execute failed: ${e.message}")
            status = "failed"
            errorMessage = (e.message ?: e.javaClass.simpleName).take(EXECUTION_DETAIL_MAX_LEN)
        }

        // 4. 原子记录执行历史 + 推进下次执行时间
        val execution = ScheduledTaskExecutionEntity(
            id = "exec-${java.util.UUID.randomUUID()}",
            taskId = task.id,
            executedAt = System.currentTimeMillis(),
            status = status,
            replySummary = replySummary,
            errorMessage = errorMessage,
        )
        // v1.0.17: 重试策略 — 失败时按指数退避重试,成功/达上限时重置 retryCount
        val isFailed = status == "failed"
        val willRetry = isFailed && task.retryCount < task.maxRetries
        val newRetryCount = if (willRetry) task.retryCount + 1 else 0
        // 错过的轮次补偿(锚点 = 领取前 DB 里的 next_run_at,即本应该跑的那个计划时刻)。
        // 重试走退避、不参与锚定推进,也不记 missed —— 失败已经有一条 failed 记录。
        val advance = if (willRetry) {
            // 指数退避: retryCount * 60s,上限 5 分钟
            val backoff = minOf(newRetryCount.toLong() * RETRY_BACKOFF_STEP_MS, RETRY_BACKOFF_MAX_MS)
            ScheduleAdvance(now + backoff, 0)
        } else {
            computeNextRunAnchored(task.interval, task.cronExpr, current.nextRunAt, now)
        }
        // 只对启用中的任务记 missed:禁用任务的手动执行不算「漏跑」,那只是用户关掉了调度。
        val missedExecutions = if (advance.missedCount > 0 && current.enabled) {
            listOf(buildMissedExecution(task.id, advance.missedCount, current.nextRunAt, execution.executedAt))
        } else {
            emptyList()
        }
        if (restoreBlocksScheduledWrites(task.id, "任务执行记录")) return
        resultOf {
            // 执行记录 + missed 记录 + 推进 next_run_at 在同一个事务内落库:
            // 要么都在,要么都不在,不会出现「记了 missed 但调度没推进」导致下轮重复计数。
            dao.recordExecutionAndScheduleNext(
                execution = execution,
                taskId = task.id,
                nextRunAt = advance.nextRunAt,
                lastRunAt = now,
                retryCount = newRetryCount,
                missedExecutions = missedExecutions,
            )
        }.onError { msg, t -> Logger.w(TAG, "Record execution+scheduleNext failed: ${t?.message ?: msg}") }
    }

    /**
     * 构造一条 missed 执行记录,正文走 strings.xml([R.string.schedule_missed_summary])。
     *
     * 与 success / failed / skipped 同一条路:执行历史的摘要本来就是 context.getString
     * 拼出来的([ScheduledTaskExecutionEntity.replySummary] 存的是已本地化的文本),
     * 所以 missed 也在这里拼,不在代码里写死中文。
     *
     * @param scheduledAt 上次的计划时刻(missed 计数的锚点)
     * @param now 本次补跑的时间
     */
    private fun buildMissedExecution(
        taskId: String,
        missedCount: Int,
        scheduledAt: Long,
        now: Long,
    ): ScheduledTaskExecutionEntity {
        val gapHours = ((now - scheduledAt) / 3_600_000L).coerceAtLeast(0L)
        val plannedAt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(scheduledAt))
        return missedExecutionEntity(
            taskId = taskId,
            replySummary = context.getString(R.string.schedule_missed_summary, missedCount, plannedAt, gapHours),
            now = now,
        )
    }

    /**
     * v1.137: 评估自动化条件。
     *
     * v1.0.17: 改为 suspend 以适配 Room DAO 的 suspend 查询(quick_note_exists 条件)。
     */
    private suspend fun evaluateCondition(task: ScheduledTaskEntity, condition: AutomationConfig.Condition): Boolean {
        return when (condition.type) {
            AutomationConfig.Condition.ALWAYS -> true
            AutomationConfig.Condition.NETWORK_AVAILABLE -> isNetworkAvailable()
            AutomationConfig.Condition.TIME_RANGE -> {
                val cfg = condition.config
                val start = cfg["startHour"]?.toString()?.trim('"')?.toIntOrNull() ?: 0
                val end = cfg["endHour"]?.toString()?.trim('"')?.toIntOrNull() ?: 23
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                hour in start..end
            }
            AutomationConfig.Condition.QUICK_NOTE_EXISTS -> {
                val cfg = condition.config
                val tag = cfg["tag"]?.toString()?.trim('"')
                val keyword = cfg["keyword"]?.toString()?.trim('"')
                val dao = quickNoteDao ?: return false
                // DAO search 已排除 deleted=1,keyword/tag 为 null 时不过滤
                dao.search(keyword, tag, limit = 1).isNotEmpty()
            }
            AutomationConfig.Condition.CONTAINS -> {
                val cfg = condition.config
                val keyword = cfg["keyword"]?.toString()?.trim('"') ?: ""
                keyword.isNotBlank() && task.prompt.contains(keyword, ignoreCase = true)
            }
            AutomationConfig.Condition.BATTERY_LEVEL -> {
                val minLevel = condition.config["minLevel"]?.toString()?.toIntOrNull() ?: 20
                val bm = context.getSystemService(BatteryManager::class.java)
                val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                level >= minLevel
            }
            AutomationConfig.Condition.CHARGING -> {
                val mustCharging = condition.config["mustCharging"]?.toString()?.toBoolean() ?: true
                if (mustCharging) {
                    val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                    // 传 null 接收器只是查询系统粘性广播的当前值，没有任何第三方发送者参与，
                    // 所以固定 NOT_EXPORTED；但标志不能省，ContextCompat 见到 0 会直接抛异常。
                    val batteryStatus = ContextCompat.registerReceiver(
                        context,
                        null,
                        filter,
                        ContextCompat.RECEIVER_NOT_EXPORTED,
                    )
                    val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                } else {
                    true
                }
            }
            else -> true
        }
    }

    /**
     * v1.137: 按动作类型执行具体动作,返回结果摘要。
     */
    private suspend fun executeAction(task: ScheduledTaskEntity, action: AutomationConfig.Action): String {
        return when (action.type) {
            AutomationConfig.Action.AI_PROMPT -> executeAiPrompt(task)
            AutomationConfig.Action.CREATE_QUICK_NOTE -> executeCreateQuickNote(action)
            AutomationConfig.Action.CALL_TOOL -> executeCallTool(task, action)
            AutomationConfig.Action.NOTIFY -> executeNotify(task, action)
            else -> executeAiPrompt(task)
        }
    }

    private suspend fun executeAiPrompt(task: ScheduledTaskEntity): String {
        val assistant = resolveAssistant(task.assistantId)
        val messages = buildMessages(assistant, task.prompt).toMutableList()
        // v2.6.8（用户反馈）: 定时任务此前完全拿不到工具。
        //
        // 旧实现调 chatService.completeText 时**没传 tools 参数**，模型手里根本没有任何工具，
        // 所以用户看到的“本轮数据源不可用: quick_note_list / screen_time / 天气”不是模型懒得调，
        // 而是它一个工具都没有。文档写“定时任务复用同一套工具注册表”，实现与文档不一致。
        //
        // 这里补上工具装配 + 多轮工具调用循环，与主对话一致：
        //  - 只发只读工具（README/NORMAL 风险），后台无人值守不能执行 HIGH 风险动作
        //  - 最多 MAX_TOOL_ROUNDS 轮，避免无限循环
        //  - 每轮把 tool 结果回灌为 tool 消息，再要一次回复
        val toolDefs = resolveScheduledToolDefinitions(assistant)
        var toolRounds = 0
        var completion: io.zer0.ai.core.ChatCompletion? = null
        while (toolRounds <= MAX_TOOL_ROUNDS) {
            val current = withTimeoutOrNull(LLM_TIMEOUT_MS) {
                chatService.completeText(
                    messages = messages,
                    temperature = assistant.temperature,
                    maxTokens = assistant.maxTokens,
                    tools = toolDefs.takeIf { it.isNotEmpty() },
                )
            } ?: error(context.getString(R.string.schedule_err_ai_timeout, LLM_TIMEOUT_MS / 1000))
            completion = current
            val calls = current.toolCalls
            if (calls.isNullOrEmpty() || toolDefs.isEmpty()) break
            if (toolRounds >= MAX_TOOL_ROUNDS) {
                Logger.w(TAG, "定时任务工具轮次达上限 $MAX_TOOL_ROUNDS，停止继续调用")
                break
            }
            toolRounds++
            // 把助手这轮的 tool_call 回灌进历史，再逐条执行并回填结果
            messages.add(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    content = current.text.orEmpty(),
                    toolCalls = calls,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            calls.forEach { call ->
                val output = executeScheduledTool(call)
                messages.add(
                    UIMessage(
                        role = MessageRole.TOOL,
                        content = output,
                        toolCallId = call.id,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
        val finalCompletion = completion
            ?: error(context.getString(R.string.schedule_err_ai_timeout, LLM_TIMEOUT_MS / 1000))
        // v1.0.74 fix: 剥离 <think> 推理标签,防止思考内容混入任务回复
        val reply = io.zer0.muse.transformer.stripThinkTags(finalCompletion.text)
        if (restoreBlocksScheduledWrites(task.id, "定时会话")) return ""
        val now = System.currentTimeMillis()
        val sessionId = if (task.dedicatedSessionId.isNotBlank()) {
            task.dedicatedSessionId
        } else {
            val newSessionId = sessionRepository.createSession(assistant.id)
            sessionRepository.renameSession(
                newSessionId,
                task.name.ifBlank { context.getString(R.string.schedule_default_session_name) },
            )
            resultOf { dao.updateDedicatedSessionId(task.id, newSessionId) }
                .onError { msg, t -> Logger.w(TAG, "updateDedicatedSessionId failed: ${t?.message ?: msg}") }
            newSessionId
        }
        sessionRepository.appendMessage(
            sessionId = sessionId,
            message = UIMessage(role = MessageRole.USER, content = task.prompt, createdAt = now),
        )
        sessionRepository.appendMessage(
            sessionId = sessionId,
            message = UIMessage(role = MessageRole.ASSISTANT, content = reply, createdAt = System.currentTimeMillis()),
        )
        showNotification(
            task.name,
            reply,
            MuseNotificationTarget.ScheduledTask(task.id),
        )
        return reply
    }

    /**
     * v2.6.8: 定时任务可用的工具集合。
     *
     * 只取只读档工具（SAFE / NORMAL）。后台无人值守执行不能做高风险动作，
     * 即使助手配置里勾了也不发——这是与主对话有意为之的差异。
     * 工具可用性还受助手自身的 toolIdsJson 白名单约束。
     */
    private fun resolveScheduledToolDefinitions(assistant: AssistantEntity): List<ToolDefinition> {
        val registry = toolRegistry ?: return emptyList()
        return try {
            val enabledIds = if (assistant.toolIdsJson.isBlank() || assistant.toolIdsJson == "[]") {
                null
            } else {
                AppJson.decodeFromString<List<String>>(assistant.toolIdsJson)
            }
            registry.listToolsAsToolDefinitions(enabledIds)
                .filter { def ->
                    val risk = runCatching {
                        io.zer0.muse.tools.ToolPermissionResolver.riskLevelFor(def.name)
                    }.getOrDefault(io.zer0.muse.tools.ToolRiskLevel.NORMAL)
                    risk != io.zer0.muse.tools.ToolRiskLevel.HIGH
                }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Logger.w(TAG, "装配定时任务工具失败，本轮不提供工具: ${t.message}")
            emptyList()
        }
    }

    /** v2.6.8: 执行单个工具调用，返回回填给模型的文本。 */
    private suspend fun executeScheduledTool(call: io.zer0.ai.core.ToolCall): String {
        val registry = toolRegistry ?: return "[工具不可用] ToolRegistry 未初始化"
        val risk = runCatching {
            io.zer0.muse.tools.ToolPermissionResolver.riskLevelFor(call.name)
        }.getOrDefault(io.zer0.muse.tools.ToolRiskLevel.HIGH)
        if (risk == io.zer0.muse.tools.ToolRiskLevel.HIGH) {
            Logger.w(TAG, "定时任务跳过 HIGH 风险工具: ${call.name}")
            return "[跳过] ${call.name} 属于高风险工具，定时任务后台执行不授权"
        }
        return try {
            // ToolCall.arguments 是 JSON 文本，ToolRegistry.execute 需要 Map<String, String>
            val argsJson = AppJson.parseToJsonElement(call.arguments) as? kotlinx.serialization.json.JsonObject
            val argsMap: Map<String, String> = argsJson
                ?.mapValues { (_, v) ->
                    (v as? kotlinx.serialization.json.JsonPrimitive)?.content ?: v.toString()
                }
                .orEmpty()
            val outcome = registry.execute(call.name, argsMap)
            if (outcome.isError) {
                "[失败] ${call.name}: ${outcome.content}"
            } else {
                outcome.content
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Logger.w(TAG, "定时任务工具 ${call.name} 执行异常: ${t.message}")
            "[异常] ${call.name}: ${t.message ?: t::class.simpleName}"
        }
    }

    private suspend fun executeCreateQuickNote(action: AutomationConfig.Action): String {
        val cfg = action.config
        val title = cfg["title"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("create_quick_note 缺少 title")
        val content = cfg["content"]?.toString()?.trim('"') ?: ""
        val tags = cfg["tags"]?.toString()?.trim('"')?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        val dao = quickNoteDao ?: throw IllegalStateException("QuickNoteDao 未初始化")
        val id = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        if (io.zer0.common.ProcessWriteGate.restoring) {
            Logger.w(TAG, "备份恢复进行中，跳过定时速记写入")
            return ""
        }
        dao.upsert(
            QuickNoteEntity(
                id = id,
                title = title,
                content = content,
                tags = tags,
                pinned = false,
                deleted = false,
                deletedAt = 0,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return context.getString(R.string.tool_quick_note_added, id, title)
    }

    private suspend fun executeCallTool(task: ScheduledTaskEntity, action: AutomationConfig.Action): String {
        val cfg = action.config
        val toolId = cfg["toolId"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("call_tool 缺少 toolId")
        val paramsElement = cfg["params"]
        val paramsJson = when (paramsElement) {
            is JsonObject -> AppJson.encodeToString(JsonObject.serializer(), paramsElement)
            else -> paramsElement?.toString()?.trim('"') ?: "{}"
        }
        val registry = toolRegistry ?: throw IllegalStateException("ToolRegistry 未初始化")
        if (restoreBlocksScheduledWrites(task.id, "定时工具")) return ""
        // v1.0.17: 定时任务 call_tool 动作增加风险审批,绕过 ToolPermissionResolver 的安全风险修复
        // 定时任务在后台无用户交互执行,无法走会话级权限审批,故在此直接拦截 HIGH 风险工具。
        // P0-3: 风险判定走 ToolPermissionResolver.riskLevelFor(显式表 + 前缀推断的单一真源),
        // 不再依赖注册台账值 —— 注册值与显式表脱节(如 send_email 曾注册为 NORMAL)时
        // 会绕过本拦截,必须以解析器为准
        if (io.zer0.muse.tools.ToolPermissionResolver.riskLevelFor(toolId) == io.zer0.muse.tools.ToolRiskLevel.HIGH) {
            Logger.w(TAG, "call_tool skipped HIGH risk tool '$toolId' in scheduled task '${task.name}'")
            return "跳过高风险工具: $toolId"
        }
        return io.zer0.muse.tools.ToolRouteExecutionGuard(registry)
            .executeFromJson(toolId, paramsJson)
    }

    private fun executeNotify(task: ScheduledTaskEntity, action: AutomationConfig.Action): String {
        val cfg = action.config
        val title = cfg["title"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() } ?: "Muse"
        val message = cfg["message"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("notify 缺少 message")
        if (restoreBlocksScheduledWrites(task.id, "定时通知")) return ""
        showNotification(title, message, MuseNotificationTarget.ScheduledTask(task.id))
        return message
    }

    /**
     * v1.137: 触发链式任务。
     *
     * v1.0.17: 改为同步执行 — 对每个后续任务先更新 next_run_at,然后直接 executeTask,
     * 不再等下一轮 60s 轮询。每个链式任务独立 try-catch,单个失败不影响其他。
     * [chainDepth] 透传给 executeTask,达到 [MAX_CHAIN_DEPTH] 时停止递归。
     */
    private suspend fun triggerChainTasks(task: ScheduledTaskEntity, chainDepth: Int = 0) {
        val nextIds = task.nextTaskIdsJson.toIdsList()
        if (nextIds.isEmpty()) return
        for (nextId in nextIds) {
            try {
                dao.triggerNextTasks(listOf(nextId))
                val nextTask = dao.getById(nextId) ?: continue
                executeTask(nextTask, chainDepth + 1)
            } catch (e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                Logger.w(TAG, "Chain task $nextId failed: ${e.message}")
            }
        }
    }

    /**
     * 检查设备当前是否有可用网络连接。
     */
    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val activeNetwork = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(activeNetwork)
            capabilities != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 按 assistantId 解析助手配置。
     * - assistantId 为空或 "default" → 取 id="default" 的助手,再兜底第一个
     * - 其他 id → 精确匹配;找不到则回退 default,再兜底第一个
     * - 完全无助手 → 抛异常(由 [executeTask] 捕获记录 failed)
     */
    private suspend fun resolveAssistant(assistantId: String): AssistantEntity {
        val assistants = assistantRepository.observeAll.first()
        val id = assistantId.trim()
        if (id.isBlank() || id == "default") {
            return assistants.firstOrNull { it.id == "default" }
                ?: assistants.firstOrNull()
                ?: error(context.getString(R.string.schedule_err_no_assistant))
        }
        return assistants.firstOrNull { it.id == id }
            ?: assistants.firstOrNull { it.id == "default" }
            ?: assistants.firstOrNull()
            ?: error(context.getString(R.string.schedule_err_no_assistant))
    }

    /** 构造调用 AI 的消息列表:助手 systemPrompt(非空时) + 用户 prompt。 */
    private fun buildMessages(assistant: AssistantEntity, prompt: String): List<UIMessage> {
        val list = mutableListOf<UIMessage>()
        if (assistant.systemPrompt.isNotBlank()) {
            list.add(UIMessage(role = MessageRole.SYSTEM, content = assistant.systemPrompt))
        }
        list.add(UIMessage(role = MessageRole.USER, content = prompt))
        return list
    }

    fun stop() {
        job?.cancel()
        job = null
        Logger.i(TAG, "Runner stopped")
    }

    /**
     * Phase 3 3E: 发送定时消息 — 将用户预写的消息写入会话并弹通知。
     */
    private suspend fun deliverPendingMessage(pm: io.zer0.muse.data.schedule.PendingMessage) {
        if (io.zer0.common.ProcessWriteGate.restoring ||
            restoreBlocksScheduledWrites(pm.id, "待发送消息")
        ) {
            return
        }
        val now = System.currentTimeMillis()
        try {
            // 将消息写入目标会话
            sessionRepository.appendMessage(
                sessionId = pm.sessionId,
                message = UIMessage(role = MessageRole.USER, content = pm.content, createdAt = now),
            )
            // 弹出通知
            showNotification(
                "Scheduled Message",
                pm.content,
                MuseNotificationTarget.Session(pm.sessionId),
            )
            Logger.i(TAG, "Delivered pending message ${pm.id} to session ${pm.sessionId}")
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to deliver pending message ${pm.id}: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.schedule_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.schedule_channel_desc) }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private val notificationId = AtomicInteger(NOTIFICATION_ID_BASE)

    /**
     * v1.64: 弹出定时任务到期通知 — 点击直达对应任务并展开执行历史。
     * 用 muse://scheduled-task/{taskId} deep link,经 ShareIntentHandler 解析后导航到指定任务路由。
     */
    private fun showNotification(title: String, content: String, target: MuseNotificationTarget) {
        val notificationManager = MuseNotificationManager(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content.take(100))
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(notificationManager.buildMainActivityPendingIntent(target))
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notificationId.getAndIncrement(), notification)
    }
}
