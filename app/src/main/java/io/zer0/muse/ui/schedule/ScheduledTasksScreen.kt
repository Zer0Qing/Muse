@file:OptIn(ExperimentalLayoutApi::class)

package io.zer0.muse.ui.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.form.MuseFloatingButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import io.zer0.muse.ui.common.form.MuseTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.navigation.MuseTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.AppJson
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.schedule.AutomationConfig
import io.zer0.muse.data.schedule.AutomationConfig.toAction
import io.zer0.muse.data.schedule.AutomationConfig.toCondition
import io.zer0.muse.data.schedule.AutomationConfig.toIdsJson
import io.zer0.muse.data.schedule.AutomationConfig.toIdsList
import io.zer0.muse.data.schedule.AutomationConfig.toJson
import io.zer0.muse.data.schedule.ScheduledTaskDao
import io.zer0.muse.data.schedule.ScheduledTaskEntity
import io.zer0.muse.data.schedule.ScheduledTaskExecutionDao
import io.zer0.muse.data.schedule.ScheduledTaskExecutionEntity
import io.zer0.muse.data.schedule.TaskIdName
import io.zer0.muse.schedule.CronExpression
import io.zer0.muse.schedule.ScheduledTaskRunner
import kotlinx.serialization.json.JsonObject
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.mega
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.koin.compose.koinInject

/**
 * v1.104: 把 5 字段 Cron 表达式转为人类可读的中文描述。
 *
 * 覆盖常见模式:每天/每小时/每分钟/工作日/周末/指定星期/指定日期。
 * 不覆盖的复杂表达式返回 null(UI 不显示描述,回退到原始表达式)。
 * 字段顺序:minute hour day-of-month month day-of-week
 */
private fun describeCron(expr: String): String? {
    val parts = expr.trim().split(Regex("\\s+"))
    if (parts.size != 5) return null
    val (minute, hour, dom, month, dow) = parts

    val weekDays = mapOf(
        "0" to "周日", "7" to "周日",
        "1" to "周一", "2" to "周二", "3" to "周三", "4" to "周四",
        "5" to "周五", "6" to "周六",
    )
    fun fmtTime(h: String, m: String): String {
        val hh = h.padStart(2, '0')
        val mm = m.padStart(2, '0')
        return "$hh:$mm"
    }

    // 每天 H:M
    if (dom == "*" && month == "*" && dow == "*" && minute != "*" && hour != "*" && !minute.contains("-") && !hour.contains("-")) {
        return "每天 ${fmtTime(hour, minute)}"
    }
    // 每小时 M 分
    if (hour == "*" && dom == "*" && month == "*" && dow == "*" && minute != "*" && !minute.contains("-")) {
        return "每小时 $minute 分"
    }
    // 每分钟
    if (minute == "*" && hour == "*" && dom == "*" && month == "*" && dow == "*") {
        return "每分钟"
    }
    // 工作日 H:M(dow = 1-5)
    if (dom == "*" && month == "*" && dow == "1-5" && minute != "*" && hour != "*" && !minute.contains("-") && !hour.contains("-")) {
        return "工作日(周一至周五)${fmtTime(hour, minute)}"
    }
    // 周末 H:M(dow = 0,6 或 6,0)
    if (dom == "*" && month == "*" && (dow == "0,6" || dow == "6,0") && minute != "*" && hour != "*" && !minute.contains("-") && !hour.contains("-")) {
        return "周末 ${fmtTime(hour, minute)}"
    }
    // 指定星期 H:M(dow 是单个数字或逗号列表)
    if (dom == "*" && month == "*" && dow != "*" && minute != "*" && hour != "*" && !minute.contains("-") && !hour.contains("-")) {
        val dayNames = dow.split(",").mapNotNull { weekDays[it.trim()] }
        if (dayNames.isNotEmpty()) {
            return "${dayNames.joinToString("、")} ${fmtTime(hour, minute)}"
        }
    }
    // 每月指定日 H:M
    if (dom != "*" && month == "*" && dow == "*" && minute != "*" && hour != "*" && !minute.contains("-") && !hour.contains("-") && !dom.contains("-")) {
        return "每月 $dom 日 ${fmtTime(hour, minute)}"
    }
    return null
}

// M-SC2 修复: interval 原先直接显示英文值(once/hourly/daily/weekly/cron),
// 现统一映射为中文标签,避免用户看到原始枚举值
@Composable
private fun intervalToLabel(interval: String): String = when (interval) {
    "once" -> stringResource(R.string.schedule_interval_once)
    "hourly" -> stringResource(R.string.schedule_interval_hourly)
    "daily" -> stringResource(R.string.schedule_interval_daily)
    "weekly" -> stringResource(R.string.schedule_interval_weekly)
    "cron" -> stringResource(R.string.schedule_interval_cron)
    else -> interval
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledTasksScreen(
    onBack: () -> Unit,
    dao: ScheduledTaskDao = koinInject(),
    executionDao: ScheduledTaskExecutionDao = koinInject(),
    assistantRepository: AssistantRepository = koinInject(),
    runner: ScheduledTaskRunner = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // v1.48: h13 initialValue 用 null,首次进入显示加载态而非闪空状态
    val tasks by dao.observeAll().collectAsStateWithLifecycle(initialValue = null)
    // 助手列表(用于新建/编辑任务时选择执行助手)
    val assistants by assistantRepository.observeAll.collectAsStateWithLifecycle(initialValue = emptyList())
    var showCreate by remember { mutableStateOf(false) }
    var taskToEdit by remember { mutableStateOf<ScheduledTaskEntity?>(null) }

    Scaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.schedule_title),
                onBack = onBack,
            )
        },
        floatingActionButton = {
            // L-SC5: 使用 MuseFloatingButton,与应用 iOS 风格一致
            MuseFloatingButton(
                icon = Icons.Default.Add,
                onClick = { showCreate = true },
                contentDescription = stringResource(R.string.schedule_new_task),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        val tasksList = tasks
        if (tasksList == null) {
            // v1.48: h13 首次加载显示居中加载指示器
            Column(
                Modifier.fillMaxSize().padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
        } else if (tasksList.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(innerPadding), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                MuseEmptyState(
                    icon = Icons.Outlined.Schedule,
                    title = stringResource(R.string.schedule_empty_title),
                    subtitle = stringResource(R.string.schedule_empty_subtitle),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(tasksList, key = { it.id }) { task ->
                    TaskCard(
                        task = task,
                        executionDao = executionDao,
                        runner = runner,
                        onToggle = { enabled -> scope.launch { dao.setEnabled(task.id, enabled) } },
                        onEdit = { taskToEdit = it },
                        onDelete = {
                            scope.launch {
                                executionDao.deleteByTaskId(task.id)
                                dao.delete(task.id)
                                MuseToast.show(context.getString(R.string.schedule_deleted))
                            }
                        },
                    )
                }
            }
        }
    }

    // 新建任务弹窗
    if (showCreate) {
        TaskDialog(
            existingTask = null,
            assistants = assistants,
            onDismiss = { showCreate = false },
            onSave = { task -> scope.launch { dao.upsert(task); showCreate = false } },
        )
    }
    // 编辑任务弹窗(传入现有 task 时预填字段)
    taskToEdit?.let { editing ->
        TaskDialog(
            existingTask = editing,
            assistants = assistants,
            onDismiss = { taskToEdit = null },
            onSave = { task -> scope.launch { dao.upsert(task); taskToEdit = null } },
        )
    }
}

@Composable
private fun TaskCard(
    task: ScheduledTaskEntity,
    executionDao: ScheduledTaskExecutionDao,
    runner: ScheduledTaskRunner,
    onToggle: (Boolean) -> Unit,
    onEdit: (ScheduledTaskEntity) -> Unit,
    onDelete: () -> Unit,
) {
    // P1-7: 任务项可展开,展示最近 5 次执行历史
    var expanded by remember { mutableStateOf(false) }
    var executions by remember { mutableStateOf<List<ScheduledTaskExecutionEntity>>(emptyList()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // "立即执行"按钮的执行中状态(调试用,调用 ScheduledTaskRunner.executeTask)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var executing by remember { mutableStateOf(false) }

    // 展开时加载最近 5 条执行记录(DAO 返回最近 10 条,这里取前 5)
    LaunchedEffect(task.id, expanded) {
        if (expanded) {
            executions = executionDao.queryByTaskId(task.id).take(5)
        }
    }

    Surface(shape = MuseShapes.medium, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(task.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                        Spacer(Modifier.size(8.dp))
                        Text(intervalToLabel(task.interval), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.size(4.dp))
                        Icon(
                            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (expanded) stringResource(R.string.schedule_collapse_history) else stringResource(R.string.schedule_expand_history),
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(task.prompt.take(80), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (task.nextRunAt > 0) {
                        // v1.71: 用 remember 缓存 SimpleDateFormat
                        val fmt = remember { SimpleDateFormat(MuseDateFormats.DATE_TIME_SHORT, Locale.getDefault()) }
                        Text(stringResource(R.string.schedule_next_run, fmt.format(Date(task.nextRunAt))), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                MuseSwitch(checked = task.enabled, onCheckedChange = onToggle)
                // "立即执行"按钮(调试用):直接调用 runner 执行该任务,不等 nextRunAt
                IconButton(
                    onClick = {
                        scope.launch {
                            executing = true
                            resultOf { runner.executeTask(task) }
                            executing = false
                            // 执行完成后若已展开,刷新执行历史
                            if (expanded) {
                                executions = executionDao.queryByTaskId(task.id).take(5)
                            }
                            MuseToast.show(context.getString(R.string.schedule_executed))
                        }
                    },
                    enabled = !executing,
                    modifier = Modifier.size(MuseIconSizes.touchTarget),
                ) {
                    if (executing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.PlayArrow, stringResource(R.string.schedule_run_now), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                }
                IconButton(onClick = { onEdit(task) }, modifier = Modifier.size(MuseIconSizes.touchTarget)) {
                    Icon(Icons.Default.Edit, stringResource(R.string.schedule_edit), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(MuseIconSizes.touchTarget)) { Icon(Icons.Default.Delete, stringResource(R.string.schedule_delete), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)) }
            }

            // P1-7: 展开时显示最近 5 次执行历史(时间 + 状态图标 + 回复摘要),失败任务红色标记
            if (expanded) {
                ExecutionHistorySection(executions = executions)
            }
        }
    }
    if (showDeleteConfirm) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.schedule_delete_task),
            itemName = task.name,
            onConfirm = { showDeleteConfirm = false; onDelete() },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

/**
 * P1-7: 执行历史区域。
 *
 * 在任务卡展开后渲染最近 5 次执行记录。每条记录包含:
 *  - 状态图标(成功 CheckCircle / 失败 Error)
 *  - 执行时间
 *  - 回复摘要(成功)或错误信息(失败),失败用红色标记
 */
@Composable
private fun ExecutionHistorySection(executions: List<ScheduledTaskExecutionEntity>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.schedule_execution_history), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.size(4.dp))
        if (executions.isEmpty()) {
            Text(stringResource(R.string.schedule_no_executions), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        } else {
            executions.forEachIndexed { index, exec ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                ExecutionRow(execution = exec)
            }
        }
    }
}

@Composable
private fun ExecutionRow(execution: ScheduledTaskExecutionEntity) {
    val failed = execution.status == "failed"
    // L-SC3 修复: 用 MuseDateFormats.DATE_TIME_FULL_SEC 令牌替代硬编码 "MM-dd HH:mm:ss"
    val fmt = remember { SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL_SEC, Locale.getDefault()) }
    val iconTint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (failed) Icons.Default.Error else Icons.Default.CheckCircle,
            contentDescription = if (failed) stringResource(R.string.schedule_execution_failed) else stringResource(R.string.schedule_execution_success),
            tint = iconTint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(8.dp))
        Column {
            // 时间 + 失败时整行用红色强调
            val timeColor = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
            Text(
                text = fmt.format(Date(execution.executedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = timeColor,
            )
            // 回复摘要(成功)或错误信息(失败)
            val summary = if (failed) execution.errorMessage else execution.replySummary
            if (summary.isNotEmpty()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * v1.137: 新建/编辑任务弹窗(支持条件触发、多动作类型、链式任务)。
 */
@Composable
private fun TaskDialog(
    existingTask: ScheduledTaskEntity?,
    assistants: List<AssistantEntity>,
    dao: ScheduledTaskDao = koinInject(),
    onDismiss: () -> Unit,
    onSave: (ScheduledTaskEntity) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 基础字段
    var name by rememberSaveable { mutableStateOf(existingTask?.name ?: "") }
    var prompt by rememberSaveable { mutableStateOf(existingTask?.prompt ?: "") }
    var assistantId by rememberSaveable { mutableStateOf(existingTask?.assistantId ?: "default") }
    var interval by rememberSaveable { mutableStateOf(existingTask?.interval ?: "daily") }
    var cronExpr by rememberSaveable { mutableStateOf(existingTask?.cronExpr ?: "") }

    // 自动化字段
    val initialCondition = remember(existingTask?.conditionJson) { existingTask?.conditionJson?.toCondition() ?: AutomationConfig.Condition() }
    var conditionType by rememberSaveable { mutableStateOf(initialCondition.type) }
    var conditionKeyword by rememberSaveable { mutableStateOf(initialCondition.config["keyword"]?.toString()?.trim('"') ?: "") }
    var conditionTag by rememberSaveable { mutableStateOf(initialCondition.config["tag"]?.toString()?.trim('"') ?: "") }
    var conditionStartHour by rememberSaveable { mutableStateOf(initialCondition.config["startHour"]?.toString()?.trim('"') ?: "0") }
    var conditionEndHour by rememberSaveable { mutableStateOf(initialCondition.config["endHour"]?.toString()?.trim('"') ?: "23") }

    val initialActionConfig = remember(existingTask?.actionConfigJson) { existingTask?.actionConfigJson?.toAction()?.config ?: JsonObject(emptyMap()) }
    var actionType by rememberSaveable { mutableStateOf(existingTask?.actionType ?: AutomationConfig.Action.AI_PROMPT) }
    var noteTitle by rememberSaveable { mutableStateOf(initialActionConfig["title"]?.toString()?.trim('"') ?: "") }
    var noteContent by rememberSaveable { mutableStateOf(initialActionConfig["content"]?.toString()?.trim('"') ?: "") }
    var noteTags by rememberSaveable { mutableStateOf(initialActionConfig["tags"]?.toString()?.trim('"') ?: "") }
    var toolId by rememberSaveable { mutableStateOf(initialActionConfig["toolId"]?.toString()?.trim('"') ?: "") }
    var toolParams by rememberSaveable { mutableStateOf(when (val p = initialActionConfig["params"]) { is JsonObject -> AppJson.encodeToString(JsonObject.serializer(), p) else -> p?.toString()?.trim('"') ?: "{}" }) }
    var notifyTitle by rememberSaveable { mutableStateOf(initialActionConfig["title"]?.toString()?.trim('"') ?: "") }
    var notifyMessage by rememberSaveable { mutableStateOf(initialActionConfig["message"]?.toString()?.trim('"') ?: "") }

    var nextTaskIds by rememberSaveable { mutableStateOf(existingTask?.nextTaskIdsJson?.toIdsList() ?: emptyList()) }
    var chainCandidates by remember { mutableStateOf<List<io.zer0.muse.data.schedule.TaskIdName>>(emptyList()) }
    // v1.0.17: 全量任务映射,用于链式任务循环检测(DFS 遍历 nextTaskIdsJson)
    var allTasksMap by remember { mutableStateOf<Map<String, ScheduledTaskEntity>>(emptyMap()) }
    LaunchedEffect(existingTask?.id) {
        chainCandidates = dao.listChainCandidates(existingTask?.id ?: "")
        allTasksMap = dao.getAll().associateBy { it.id }
    }

    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }

    val errNameRequired = stringResource(R.string.schedule_err_name_required)
    val errContentRequired = stringResource(R.string.schedule_err_content_required)
    val errCronInvalid = stringResource(R.string.schedule_err_cron_invalid)

    val isEdit = existingTask != null
    val selectedAssistantName = assistants.firstOrNull { it.id == assistantId }?.name
        ?: if (assistantId == "default" || assistantId.isBlank()) stringResource(R.string.schedule_default_assistant) else assistantId

    val nextRunPreview = remember(interval, cronExpr) {
        val now = System.currentTimeMillis()
        runCatching {
            when (interval) {
                "cron" -> if (cronExpr.isBlank()) null else CronExpression.parse(cronExpr.trim()).nextRunAfter(now)
                "hourly" -> now + 3_600_000L
                "daily" -> now + 86_400_000L
                "weekly" -> now + 604_800_000L
                else -> null
            }
        }.getOrNull()
    }
    val previewFmt = remember { SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, Locale.getDefault()) }
    val previewText = nextRunPreview?.let { stringResource(R.string.schedule_next_run_preview, previewFmt.format(Date(it))) }

    // 未保存修改检测
    var showDiscardConfirm by rememberSaveable { mutableStateOf(false) }
    val initialName = remember(existingTask?.name) { existingTask?.name ?: "" }
    val initialPrompt = remember(existingTask?.prompt) { existingTask?.prompt ?: "" }
    val initialAssistantId = remember(existingTask?.assistantId) { existingTask?.assistantId ?: "default" }
    val initialInterval = remember(existingTask?.interval) { existingTask?.interval ?: "daily" }
    val initialCronExpr = remember(existingTask?.cronExpr) { existingTask?.cronExpr ?: "" }
    val initialActionType = remember(existingTask?.actionType) { existingTask?.actionType ?: AutomationConfig.Action.AI_PROMPT }
    val initialNextTaskIds = remember(existingTask?.nextTaskIdsJson) { existingTask?.nextTaskIdsJson?.toIdsList() ?: emptyList<String>() }
    val hasUnsavedChanges = remember(
        name, prompt, assistantId, interval, cronExpr, actionType, conditionType,
        conditionKeyword, conditionTag, conditionStartHour, conditionEndHour,
        noteTitle, noteContent, noteTags, toolId, toolParams, notifyTitle, notifyMessage, nextTaskIds,
        initialName, initialPrompt, initialAssistantId, initialInterval, initialCronExpr, initialActionType, initialNextTaskIds,
    ) {
        val conditionChanged = conditionType != initialCondition.type ||
            conditionKeyword != (initialCondition.config["keyword"]?.toString()?.trim('"') ?: "") ||
            conditionTag != (initialCondition.config["tag"]?.toString()?.trim('"') ?: "") ||
            conditionStartHour != (initialCondition.config["startHour"]?.toString()?.trim('"') ?: "0") ||
            conditionEndHour != (initialCondition.config["endHour"]?.toString()?.trim('"') ?: "23")
        name != initialName || prompt != initialPrompt || assistantId != initialAssistantId ||
            interval != initialInterval || cronExpr != initialCronExpr || actionType != initialActionType ||
            conditionChanged || nextTaskIds != initialNextTaskIds
    }
    val dismissWithCheck = { if (hasUnsavedChanges) showDiscardConfirm = true else onDismiss() }
    BackHandler { dismissWithCheck() }

    MuseDialog(
        onDismissRequest = { dismissWithCheck() },
        title = if (isEdit) stringResource(R.string.schedule_edit_title) else stringResource(R.string.schedule_new_task),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MuseTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.schedule_task_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                MuseTextField(value = prompt, onValueChange = { prompt = it }, label = { Text(stringResource(R.string.schedule_task_content)) }, placeholder = { Text(stringResource(R.string.schedule_content_placeholder)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp))
                AssistantSelector(
                    selectedName = selectedAssistantName,
                    assistants = assistants,
                    onSelect = { assistantId = it },
                )
                // v1.0.28: 改用 FlowRow 自动换行,避免 5 个选项在横向 Row 中空间不足
                // 导致"自定义 Cron"等长文本竖排崩坏。
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        "once" to stringResource(R.string.schedule_interval_once),
                        "hourly" to stringResource(R.string.schedule_interval_hourly),
                        "daily" to stringResource(R.string.schedule_interval_daily),
                        "weekly" to stringResource(R.string.schedule_interval_weekly),
                        "cron" to stringResource(R.string.schedule_interval_cron),
                    ).forEach { (value, label) ->
                        Surface(shape = MuseShapes.small, color = if (interval == value) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.clickable(role = Role.Button) { interval = value }) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = if (interval == value) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }
                }
                if (interval == "cron") {
                    MuseTextField(value = cronExpr, onValueChange = { cronExpr = it }, label = { Text(stringResource(R.string.schedule_cron_label)) }, placeholder = { Text(stringResource(R.string.schedule_cron_placeholder)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(
                            stringResource(R.string.schedule_preset_workday_9) to "0 9 * * 1-5",
                            stringResource(R.string.schedule_preset_daily_8) to "0 8 * * *",
                            stringResource(R.string.schedule_interval_hourly) to "0 * * * *",
                            stringResource(R.string.schedule_preset_midnight) to "0 0 * * *",
                        ).forEach { (label, expr) ->
                            Surface(shape = MuseShapes.small, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.clickable(role = Role.Button) { cronExpr = expr }) {
                                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                            }
                        }
                    }
                    if (cronExpr.isNotBlank()) {
                        val desc = remember(cronExpr) { describeCron(cronExpr.trim()) }
                        if (desc != null) Text(text = desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (previewText != null) Text(previewText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)

                // v1.137: 高级自动化配置
                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(stringResource(if (showAdvanced) R.string.schedule_hide_advanced else R.string.schedule_show_advanced))
                }
                if (showAdvanced) {
                    AutomationConditionSection(
                        conditionType = conditionType,
                        onConditionTypeChange = { conditionType = it },
                        keyword = conditionKeyword,
                        onKeywordChange = { conditionKeyword = it },
                        tag = conditionTag,
                        onTagChange = { conditionTag = it },
                        startHour = conditionStartHour,
                        onStartHourChange = { conditionStartHour = it },
                        endHour = conditionEndHour,
                        onEndHourChange = { conditionEndHour = it },
                    )
                    AutomationActionSection(
                        actionType = actionType,
                        onActionTypeChange = { actionType = it },
                        noteTitle = noteTitle,
                        onNoteTitleChange = { noteTitle = it },
                        noteContent = noteContent,
                        onNoteContentChange = { noteContent = it },
                        noteTags = noteTags,
                        onNoteTagsChange = { noteTags = it },
                        toolId = toolId,
                        onToolIdChange = { toolId = it },
                        toolParams = toolParams,
                        onToolParamsChange = { toolParams = it },
                        notifyTitle = notifyTitle,
                        onNotifyTitleChange = { notifyTitle = it },
                        notifyMessage = notifyMessage,
                        onNotifyMessageChange = { notifyMessage = it },
                    )
                    AutomationChainSection(
                        candidates = chainCandidates,
                        selectedIds = nextTaskIds,
                        onSelectionChange = { newIds ->
                            // v1.0.17: 链式任务循环检测 — 勾选新任务时检查是否会形成环
                            val currentTaskId = existingTask?.id ?: ""
                            val addedId = newIds.firstOrNull { it !in nextTaskIds }
                            if (addedId != null && currentTaskId.isNotBlank() &&
                                hasChainCycle(addedId, currentTaskId, allTasksMap)
                            ) {
                                MuseToast.show("不能选择会形成循环链的任务")
                            } else {
                                nextTaskIds = newIds
                            }
                        },
                    )
                }

                errorMessage?.let { msg ->
                    Text(text = msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmText = if (isEdit) stringResource(R.string.schedule_save) else stringResource(R.string.schedule_create_btn),
        onConfirm = {
            if (name.isBlank()) { errorMessage = errNameRequired; return@MuseDialog }
            if (prompt.isBlank()) { errorMessage = errContentRequired; return@MuseDialog }
            val now = System.currentTimeMillis()
            val cronValue = if (interval == "cron") cronExpr.trim() else ""
            val nextRun = if (interval == "cron") {
                val parsed = runCatching { CronExpression.parse(cronValue) }.getOrNull()
                if (parsed == null) { errorMessage = errCronInvalid; return@MuseDialog }
                parsed.nextRunAfter(now)
            } else {
                now + when (interval) { "hourly" -> 3_600_000L; "daily" -> 86_400_000L; "weekly" -> 604_800_000L; else -> 60_000L }
            }

            val conditionConfig = when (conditionType) {
                AutomationConfig.Condition.TIME_RANGE -> JsonObject(mapOf(
                    "startHour" to kotlinx.serialization.json.JsonPrimitive(conditionStartHour.toIntOrNull() ?: 0),
                    "endHour" to kotlinx.serialization.json.JsonPrimitive(conditionEndHour.toIntOrNull() ?: 23),
                ))
                AutomationConfig.Condition.QUICK_NOTE_EXISTS -> JsonObject(mapOf(
                    "tag" to kotlinx.serialization.json.JsonPrimitive(conditionTag),
                    "keyword" to kotlinx.serialization.json.JsonPrimitive(conditionKeyword),
                ))
                AutomationConfig.Condition.CONTAINS -> JsonObject(mapOf("keyword" to kotlinx.serialization.json.JsonPrimitive(conditionKeyword)))
                else -> JsonObject(emptyMap())
            }
            val condition = AutomationConfig.Condition(type = conditionType, config = conditionConfig)

            val actionConfig = when (actionType) {
                AutomationConfig.Action.CREATE_QUICK_NOTE -> JsonObject(mapOf(
                    "title" to kotlinx.serialization.json.JsonPrimitive(noteTitle),
                    "content" to kotlinx.serialization.json.JsonPrimitive(noteContent),
                    "tags" to kotlinx.serialization.json.JsonPrimitive(noteTags),
                ))
                AutomationConfig.Action.CALL_TOOL -> JsonObject(mapOf(
                    "toolId" to kotlinx.serialization.json.JsonPrimitive(toolId),
                    "params" to runCatching { AppJson.decodeFromString(kotlinx.serialization.json.JsonElement.serializer(), toolParams) }.getOrElse { JsonObject(emptyMap()) },
                ))
                AutomationConfig.Action.NOTIFY -> JsonObject(mapOf(
                    "title" to kotlinx.serialization.json.JsonPrimitive(notifyTitle),
                    "message" to kotlinx.serialization.json.JsonPrimitive(notifyMessage),
                ))
                else -> JsonObject(emptyMap())
            }
            val action = AutomationConfig.Action(type = actionType, config = actionConfig)

            errorMessage = null
            val id = existingTask?.id ?: "task-$now"
            onSave(
                ScheduledTaskEntity(
                    id = id,
                    name = name.trim(),
                    prompt = prompt.trim(),
                    assistantId = assistantId,
                    interval = interval,
                    cronExpr = cronValue,
                    enabled = existingTask?.enabled ?: true,
                    nextRunAt = nextRun,
                    lastRunAt = existingTask?.lastRunAt ?: 0,
                    createdAt = existingTask?.createdAt ?: now,
                    updatedAt = now,
                    dedicatedSessionId = existingTask?.dedicatedSessionId ?: "",
                    conditionJson = condition.toJson(),
                    actionType = action.type,
                    actionConfigJson = action.toJson(),
                    nextTaskIdsJson = nextTaskIds.toIdsJson(),
                    parentTaskId = existingTask?.parentTaskId ?: "",
                    retryCount = existingTask?.retryCount ?: 0,
                    maxRetries = existingTask?.maxRetries ?: 3,
                ),
            )
        },
        dismissText = stringResource(R.string.schedule_cancel),
        onDismiss = { dismissWithCheck() },
    )
    if (showDiscardConfirm) {
        MuseDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = stringResource(R.string.schedule_unsaved_title),
            content = { Text(text = stringResource(R.string.schedule_unsaved_message), style = MaterialTheme.typography.bodyMedium) },
            confirmText = stringResource(R.string.schedule_discard),
            onConfirm = { showDiscardConfirm = false; onDismiss() },
            dismissText = stringResource(R.string.schedule_cancel),
            onDismiss = { showDiscardConfirm = false },
            destructive = true,
        )
    }
}

/**
 * 助手选择器:展示当前选中助手名称,点击展开下拉菜单选择,绑定 assistantId。
 */
@Composable
private fun AssistantSelector(
    selectedName: String,
    assistants: List<AssistantEntity>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = MuseShapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().clickable { expanded = true },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.schedule_assistant), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(8.dp))
                Text(
                    selectedName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.schedule_select_assistant), tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (assistants.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.schedule_no_assistants)) },
                    onClick = { expanded = false },
                )
            } else {
                assistants.forEach { a ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(a.name.ifBlank { a.id })
                                // v1.0.17: 能力标签 — 模型名 + 绑定工具数
                                val toolCount = runCatching {
                                    AppJson.decodeFromString<List<String>>(a.toolIdsJson).size
                                }.getOrNull() ?: 0
                                val modelLabel = a.modelId ?: "默认模型"
                                Text(
                                    "$modelLabel · $toolCount 个工具",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        },
                        onClick = { onSelect(a.id); expanded = false },
                    )
                }
            }
        }
    }
}

/**
 * v1.137: 条件触发配置区。
 *
 * 支持选择条件类型并填写对应参数:
 *  - always: 无条件执行
 *  - network_available: 有可用网络时执行
 *  - time_range: 在指定小时范围内执行
 *  - contains: task.prompt 包含指定关键词时执行
 *  - quick_note_exists: 存在匹配标签/关键词的快速记录时执行
 */
@Composable
private fun AutomationConditionSection(
    conditionType: String,
    onConditionTypeChange: (String) -> Unit,
    keyword: String,
    onKeywordChange: (String) -> Unit,
    tag: String,
    onTagChange: (String) -> Unit,
    startHour: String,
    onStartHourChange: (String) -> Unit,
    endHour: String,
    onEndHourChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.schedule_condition_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                AutomationConfig.Condition.ALWAYS to stringResource(R.string.schedule_condition_type_always),
                AutomationConfig.Condition.NETWORK_AVAILABLE to stringResource(R.string.schedule_condition_type_network_available),
                AutomationConfig.Condition.TIME_RANGE to stringResource(R.string.schedule_condition_type_time_range),
                AutomationConfig.Condition.CONTAINS to stringResource(R.string.schedule_condition_type_contains),
                AutomationConfig.Condition.QUICK_NOTE_EXISTS to stringResource(R.string.schedule_condition_type_quick_note_exists),
            ).forEach { (value, label) ->
                Surface(
                    shape = MuseShapes.small,
                    color = if (conditionType == value) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable(role = Role.Button) { onConditionTypeChange(value) },
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (conditionType == value) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        when (conditionType) {
            AutomationConfig.Condition.TIME_RANGE -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    MuseTextField(
                        value = startHour,
                        onValueChange = { onStartHourChange(it.filter { c -> c.isDigit() }.take(2)) },
                        label = { Text(stringResource(R.string.schedule_condition_start_hour)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    MuseTextField(
                        value = endHour,
                        onValueChange = { onEndHourChange(it.filter { c -> c.isDigit() }.take(2)) },
                        label = { Text(stringResource(R.string.schedule_condition_end_hour)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            AutomationConfig.Condition.CONTAINS -> {
                MuseTextField(
                    value = keyword,
                    onValueChange = onKeywordChange,
                    label = { Text(stringResource(R.string.schedule_condition_keyword)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AutomationConfig.Condition.QUICK_NOTE_EXISTS -> {
                MuseTextField(
                    value = tag,
                    onValueChange = onTagChange,
                    label = { Text(stringResource(R.string.schedule_condition_tag)) },
                    placeholder = { Text(stringResource(R.string.schedule_condition_tag_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MuseTextField(
                    value = keyword,
                    onValueChange = onKeywordChange,
                    label = { Text(stringResource(R.string.schedule_condition_keyword)) },
                    placeholder = { Text(stringResource(R.string.schedule_condition_keyword_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * v1.137: 动作配置区。
 *
 * 支持选择动作类型并填写对应参数:
 *  - ai_prompt: 调用 AI 执行 prompt(默认)
 *  - create_quick_note: 创建一条快速记录
 *  - call_tool: 调用指定工具
 *  - notify: 发送通知
 */
@Composable
private fun AutomationActionSection(
    actionType: String,
    onActionTypeChange: (String) -> Unit,
    noteTitle: String,
    onNoteTitleChange: (String) -> Unit,
    noteContent: String,
    onNoteContentChange: (String) -> Unit,
    noteTags: String,
    onNoteTagsChange: (String) -> Unit,
    toolId: String,
    onToolIdChange: (String) -> Unit,
    toolParams: String,
    onToolParamsChange: (String) -> Unit,
    notifyTitle: String,
    onNotifyTitleChange: (String) -> Unit,
    notifyMessage: String,
    onNotifyMessageChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.schedule_action_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                AutomationConfig.Action.AI_PROMPT to stringResource(R.string.schedule_action_type_ai_prompt),
                AutomationConfig.Action.CREATE_QUICK_NOTE to stringResource(R.string.schedule_action_type_create_quick_note),
                AutomationConfig.Action.CALL_TOOL to stringResource(R.string.schedule_action_type_call_tool),
                AutomationConfig.Action.NOTIFY to stringResource(R.string.schedule_action_type_notify),
            ).forEach { (value, label) ->
                Surface(
                    shape = MuseShapes.small,
                    color = if (actionType == value) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable(role = Role.Button) { onActionTypeChange(value) },
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (actionType == value) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        when (actionType) {
            AutomationConfig.Action.CREATE_QUICK_NOTE -> {
                MuseTextField(
                    value = noteTitle,
                    onValueChange = onNoteTitleChange,
                    label = { Text(stringResource(R.string.schedule_action_note_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MuseTextField(
                    value = noteContent,
                    onValueChange = onNoteContentChange,
                    label = { Text(stringResource(R.string.schedule_action_note_content)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                )
                MuseTextField(
                    value = noteTags,
                    onValueChange = onNoteTagsChange,
                    label = { Text(stringResource(R.string.schedule_action_note_tags)) },
                    placeholder = { Text(stringResource(R.string.schedule_action_note_tags_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AutomationConfig.Action.CALL_TOOL -> {
                MuseTextField(
                    value = toolId,
                    onValueChange = onToolIdChange,
                    label = { Text(stringResource(R.string.schedule_action_tool_id)) },
                    placeholder = { Text(stringResource(R.string.schedule_action_tool_id_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MuseTextField(
                    value = toolParams,
                    onValueChange = onToolParamsChange,
                    label = { Text(stringResource(R.string.schedule_action_tool_params)) },
                    placeholder = { Text(stringResource(R.string.schedule_action_tool_params_placeholder)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                )
            }
            AutomationConfig.Action.NOTIFY -> {
                MuseTextField(
                    value = notifyTitle,
                    onValueChange = onNotifyTitleChange,
                    label = { Text(stringResource(R.string.schedule_action_notify_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MuseTextField(
                    value = notifyMessage,
                    onValueChange = onNotifyMessageChange,
                    label = { Text(stringResource(R.string.schedule_action_notify_message)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                )
            }
        }
    }
}

/**
 * v1.0.17: 检测从 taskId 出发沿 nextTaskIdsJson 链是否能回到 targetId(形成环)。
 *
 * DFS 遍历后续任务链,若途中遇到 targetId 则说明加入该任务会形成循环链。
 */
private fun hasChainCycle(
    taskId: String,
    targetId: String,
    allTasks: Map<String, ScheduledTaskEntity>,
    visited: MutableSet<String> = mutableSetOf(),
): Boolean {
    if (taskId == targetId) return true
    if (taskId in visited) return false
    visited.add(taskId)
    val task = allTasks[taskId] ?: return false
    val nextIds = task.nextTaskIdsJson.toIdsList()
    for (nextId in nextIds) {
        if (hasChainCycle(nextId, targetId, allTasks, visited)) return true
    }
    return false
}

/**
 * v1.137: 链式任务配置区。
 *
 * 从候选任务列表多选后续任务,当前任务成功执行后会触发这些任务立即执行。
 */
@Composable
private fun AutomationChainSection(
    candidates: List<TaskIdName>,
    selectedIds: List<String>,
    onSelectionChange: (List<String>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.schedule_chain_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        if (candidates.isEmpty()) {
            Text(stringResource(R.string.schedule_chain_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        } else {
            candidates.forEach { candidate ->
                val selected = selectedIds.contains(candidate.id)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectionChange(if (selected) selectedIds - candidate.id else selectedIds + candidate.id) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MuseSwitch(checked = selected, onCheckedChange = null)
                    Spacer(Modifier.size(12.dp))
                    Text(candidate.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
