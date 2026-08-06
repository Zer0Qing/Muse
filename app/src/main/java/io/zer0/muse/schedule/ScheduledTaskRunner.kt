package io.zer0.muse.schedule

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.app.NotificationCompat
import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.serialization.json.JsonObject
import io.zer0.common.resultOf
import io.zer0.muse.MainActivity
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.schedule.AutomationConfig
import io.zer0.muse.data.schedule.AutomationConfig.toAction
import io.zer0.muse.data.schedule.AutomationConfig.toCondition
import io.zer0.muse.data.schedule.AutomationConfig.toIdsList
import io.zer0.muse.data.quicknote.QuickNoteDao
import io.zer0.muse.data.quicknote.QuickNoteEntity
import io.zer0.muse.data.schedule.ScheduledTaskDao
import io.zer0.muse.data.schedule.ScheduledTaskEntity
import io.zer0.muse.data.schedule.ScheduledTaskExecutionEntity
import io.zer0.muse.data.session.SessionRepository
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 17: 定时任务执行器。
 *
 * 在 App 启动时启动,每 60 秒检查一次是否有到期的定时任务。
 * 到期任务会真正执行 prompt(调用 AI + 写入会话),并弹通知(点击跳转到主页)。
 * 使用协程轮询而非 AlarmManager,避免 Android 后台限制。
 *
 * P1-7: 执行任务后插入一条 execution 记录(success/failed),供 UI 展示执行历史。
 *
 * 真正执行改造(按 [ProactiveMessageRunner]):
 *  - 按 task.assistantId 解析助手配置,用其 systemPrompt 作为系统消息
 *  - 调用 [ChatService.completeText](非流式)执行 task.prompt
 *  - 把用户 prompt 和 AI 回复写入一个专用会话(标题用 task.name)
 *  - replySummary 存 AI 回复前 200 字;失败时 errorMessage 记录真实异常
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

    companion object {
        const val CHANNEL_ID = "scheduled_tasks"
        const val NOTIFICATION_ID_BASE = 2000
        private const val TAG = "ScheduledTask"

        /** R-TEST-11: 纯调度逻辑,供单元测试直接调用。 */
        internal fun computeNextRun(interval: String, cronExpr: String, now: Long): Long = when (interval) {
            "hourly" -> now + 3_600_000L
            "daily" -> now + 86_400_000L
            "weekly" -> now + 604_800_000L
            "cron" -> {
                if (cronExpr.isBlank()) {
                    0L
                } else {
                    resultOf { CronExpression.parse(cronExpr).nextRunAfter(now) }
                        .onError { msg, t -> Logger.w(TAG, "Invalid cron expr: ${t?.message ?: msg}") }
                        .getOrNull() ?: 0L
                }
            }
            else -> 0L
        }
        private const val POLL_INTERVAL_MS = 60_000L // 每分钟检查一次
        private const val REPLY_SUMMARY_MAX_LEN = 200 // AI 回复摘要最大长度
        /** 单次任务 AI 调用超时(毫秒)。 */
        private const val LLM_TIMEOUT_MS = 60_000L
        /** v1.0.17: 链式任务最大递归深度,防止无限循环。 */
        private const val MAX_CHAIN_DEPTH = 10
        /** v1.0.17: 重试退避上限(毫秒,5 分钟)。 */
        private const val RETRY_BACKOFF_MAX_MS = 300_000L
        /** v1.0.17: 重试退避步长(毫秒,每次递增 1 分钟)。 */
        private const val RETRY_BACKOFF_STEP_MS = 60_000L
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
            val now = System.currentTimeMillis()
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
     *  1. 评估 condition(条件触发)
     *  2. 按 action_type 执行对应动作(ai_prompt / create_quick_note / call_tool / notify)
     *  3. 成功执行后触发链式任务(next_task_ids)
     *  4. 原子记录执行历史 + 推进下次执行时间
     *
     * v1.0.17: 新增 [chainDepth] 参数用于链式任务递归深度限制;
     *  执行失败时按指数退避重试(retry_count < max_retries 时递增,达上限后重置)。
     *
     * 任何环节失败都记录一条 failed execution,不抛异常。
     */
    suspend fun executeTask(task: ScheduledTaskEntity, chainDepth: Int = 0) {
        // v1.0.17: 链式任务深度限制,防止无限递归
        if (chainDepth >= MAX_CHAIN_DEPTH) {
            Logger.w(TAG, "Chain depth limit reached ($MAX_CHAIN_DEPTH), skip task ${task.id}")
            return
        }

        val now = System.currentTimeMillis()
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
                val action = AutomationConfig.Action(
                    type = task.actionType,
                    config = task.actionConfigJson.toAction().config,
                )
                val output = executeAction(task, action)
                replySummary = output.take(REPLY_SUMMARY_MAX_LEN)
                // 3. 触发链式任务
                triggerChainTasks(task, chainDepth)
            }
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            Logger.w(TAG, "Task ${task.id} execute failed: ${e.message}")
            status = "failed"
            errorMessage = (e.message ?: e.javaClass.simpleName).take(REPLY_SUMMARY_MAX_LEN)
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
        val newRetryCount = when {
            isFailed && task.retryCount < task.maxRetries -> task.retryCount + 1
            isFailed -> 0 // 达到最大重试次数,重置
            else -> 0 // 成功/跳过,重置
        }
        val nextRun = if (isFailed && task.retryCount < task.maxRetries) {
            // 指数退避: retryCount * 60s,上限 5 分钟
            val backoff = minOf(newRetryCount.toLong() * RETRY_BACKOFF_STEP_MS, RETRY_BACKOFF_MAX_MS)
            now + backoff
        } else {
            computeNextRun(task, now)
        }
        resultOf {
            dao.recordExecutionAndScheduleNext(execution, task.id, nextRun, now)
            dao.updateRetryCount(task.id, newRetryCount)
        }.onError { msg, t -> Logger.w(TAG, "Record execution+scheduleNext failed: ${t?.message ?: msg}") }
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
                    val batteryStatus = context.registerReceiver(null, filter)
                    val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                } else true
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
            AutomationConfig.Action.NOTIFY -> executeNotify(action)
            else -> executeAiPrompt(task)
        }
    }

    private suspend fun executeAiPrompt(task: ScheduledTaskEntity): String {
        val assistant = resolveAssistant(task.assistantId)
        val messages = buildMessages(assistant, task.prompt)
        val completion = withTimeoutOrNull(LLM_TIMEOUT_MS) {
            chatService.completeText(
                messages = messages,
                temperature = assistant.temperature,
                maxTokens = assistant.maxTokens,
            )
        } ?: error(context.getString(R.string.schedule_err_ai_timeout, LLM_TIMEOUT_MS / 1000))
        val reply = completion.text
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
        showNotification(task.name, reply)
        return reply
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
        // v1.0.17: 定时任务 call_tool 动作增加风险审批,绕过 ToolPermissionResolver 的安全风险修复
        // 定时任务在后台无用户交互执行,无法走会话级权限审批,故在此直接拦截 HIGH 风险工具
        val toolDef = registry.listTools().firstOrNull { it.name == toolId }
        if (toolDef?.riskLevel == ToolRiskLevel.HIGH) {
            Logger.w(TAG, "call_tool skipped HIGH risk tool '$toolId' in scheduled task '${task.name}'")
            return "跳过高风险工具: $toolId"
        }
        return registry.executeFromJson(toolId, paramsJson)
    }

    private fun executeNotify(action: AutomationConfig.Action): String {
        val cfg = action.config
        val title = cfg["title"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() } ?: "Muse"
        val message = cfg["message"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("notify 缺少 message")
        showNotification(title, message)
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

    /**
     * 计算下次执行时间(纯函数,不操作 DB)。
     * - hourly / daily / weekly: now + 固定间隔
     * - cron: 用 CronExpression 解析;空表达式或解析失败 → 0(降级为不重复)
     * - once / 未知: 0(不重复)
     *
     * 返回 <=0 表示无下次执行,[recordExecutionAndScheduleNext] 会据此禁用任务。
     */
    private fun computeNextRun(task: ScheduledTaskEntity, now: Long): Long {
        return computeNextRun(task.interval, task.cronExpr, now)
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
        val now = System.currentTimeMillis()
        try {
            // 将消息写入目标会话
            sessionRepository.appendMessage(
                sessionId = pm.sessionId,
                message = UIMessage(role = MessageRole.USER, content = pm.content, createdAt = now),
            )
            // 弹出通知
            showNotification("Scheduled Message", pm.content)
            Logger.i(TAG, "Delivered pending message ${pm.id} to session ${pm.sessionId}")
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to deliver pending message ${pm.id}: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, context.getString(R.string.schedule_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.schedule_channel_desc) }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private val notificationId = AtomicInteger(NOTIFICATION_ID_BASE)

    /**
     * v1.64: 弹出定时任务到期通知 — 点击直达定时任务页。
     * 用 muse://scheduled-tasks deep link,经 ShareIntentHandler 解析后导航到 SCHEDULED_TASKS 路由。
     */
    private fun showNotification(title: String, content: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            data = android.net.Uri.parse("muse://scheduled-tasks")
        }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content.take(100))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notificationId.getAndIncrement(), notification)
    }
}
