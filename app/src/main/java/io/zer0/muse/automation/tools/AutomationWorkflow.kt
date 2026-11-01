package io.zer0.muse.automation.tools

import io.zer0.common.AppJson
import io.zer0.muse.automation.appcontrol.AppControlCore
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.automation.core.DeviceCommandPolicy
import io.zer0.muse.automation.vdisplay.VirtualDisplayClient
import io.zer0.muse.automation.vdisplay.VirtualDisplayInputCommand
import io.zer0.muse.automation.vdisplay.VirtualDisplayLeaseRegistry
import io.zer0.muse.automation.vdisplay.VirtualDisplaySemanticActions
import io.zer0.muse.automation.vdisplay.VirtualDisplayServerManager
import io.zer0.muse.tools.NodeScriptTool
import io.zer0.muse.tools.ToolOutcome
import io.zer0.muse.tools.WorkflowJournal
import io.zer0.muse.tools.script.SkillEngineResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

private val DISPLAY_REQUIRED_VIRTUAL_ACTIONS = setOf(
    "virtual_launch",
    "virtual_tap",
    "virtual_tap_text",
    "virtual_swipe",
    "virtual_text",
    "virtual_key",
    "virtual_wait",
    "virtual_read",
)
private val CACHED_DISPLAY_ID_REGEX = Regex("""displayId=(\d+)""")

/**
 * 受控设备工作流 —— 把安全的手机动作原语组合成一次可审计调用。
 *
 * 这是 Agent 编排层的第一块：动作集合是显式白名单，不接受任意 shell；每一步失败就停止，
 * 结果同时保留给模型的摘要和给编排/UI 使用的结构化 details。
 */
// 这是个「工作流引擎」：run/断点恢复 + 每类动作一个 executeXxx + 虚拟屏一族。
// 27 个函数确实偏多，但按职责拆成协作类需要把 journal / displayLeases / manager 一起搬，
// 会让「一步执行到底走哪条路」更难跟（且结构测试锚点也要跟着改）。
// 这里选择显式保留并写清理由 —— 与 UiAgentRunner 的处理一致：例外要看得见，不藏在基线里。
@Suppress("TooManyFunctions")
class AutomationWorkflow(
    private val manager: AutomationManager,
    private val journal: WorkflowJournal? = null,
    private val virtualDisplayClient: VirtualDisplayClient? = null,
    private val virtualDisplayManager: VirtualDisplayServerManager? = null,
    private val nodeScriptExecutor: (suspend (String, Long) -> SkillEngineResult)? = null,
    private val displayLeases: VirtualDisplayLeaseRegistry = VirtualDisplayLeaseRegistry(),
) {
    suspend fun run(
        steps: List<AutomationWorkflowStep>,
        runId: String,
        retryUnknown: Boolean = false,
        /** 每步进度（写进任务卡 progressText）。默认空实现，供后台宿主无 UI 时复用。 */
        onProgress: (String) -> Unit = {},
    ): ToolOutcome {
        val durableJournal = journal ?: return ToolOutcome.error("工作流断点日志不可用，拒绝执行不可恢复的设备动作")
        return durableJournal.withRunLock(runId) {
            runLocked(steps, runId, retryUnknown, durableJournal, onProgress)
        }
    }

    private suspend fun runLocked(
        steps: List<AutomationWorkflowStep>,
        runId: String,
        retryUnknown: Boolean,
        durableJournal: WorkflowJournal,
        onProgress: (String) -> Unit,
    ): ToolOutcome {
        val results = mutableListOf<AutomationWorkflowStepResult>()
        // 断点准备（读日志、判定「上一步结果未知」、算虚拟屏刷新点）整块抽走：
        // 它原来占 runLocked 前半段、且贡献了大部分分支。
        val state = when (val plan = prepareResume(steps, runId, retryUnknown, durableJournal)) {
            is ResumePlan.Blocked -> return plan.outcome
            is ResumePlan.Ready -> plan.state
        }
        for ((index, step) in steps.withIndex()) {
            // 20 步的流程中途没有反馈时，用户看到的就是一个转圈。
            onProgress("第 ${index + 1}/${steps.size} 步：${step.action}")
            val result = restoreFromCache(state, runId, index, step)
                ?: runStepDurably(durableJournal, runId, index, state.expectedKeys[index].orEmpty(), step)
            results += result
            if (!result.success) break
        }
        val completed = results.count { it.success }
        val success = results.size == steps.size && completed == steps.size
        val content = buildString {
            append(if (success) "工作流完成" else "工作流在第 ${completed + 1} 步停止")
            append("($completed/${steps.size})")
            results.forEach { item ->
                appendLine()
                append("[${item.index}] ${item.action}: ${item.message}")
            }
        }
        val details = mapOf(
            "runId" to runId,
            "resumeFrom" to state.resumeFrom,
            "completed" to completed,
            "total" to steps.size,
            "steps" to results,
        )
        return if (success) ToolOutcome.ok(content, details) else ToolOutcome.error(content, details)
    }

    /** 断点状态（准备阶段的产物）。 */
    private class ResumeState(
        val expectedKeys: Map<Int, String>,
        val cached: Map<Int, WorkflowJournal.Entry>,
        val resumeFrom: Int,
        val cachedEnsureToRefresh: Int?,
    )

    /** 断点准备结果：要么可以继续，要么直接给调用方一个错误结论。 */
    private sealed interface ResumePlan {
        class Ready(val state: ResumeState) : ResumePlan
        class Blocked(val outcome: ToolOutcome) : ResumePlan
    }

    /**
     * 读断点日志、判定「上一步结果未知」、算出虚拟屏需要在哪一步刷新。
     *
     * 这些判定原来散在 [runLocked] 的前半段，把它拆出来后主流程只剩「逐步执行」。
     */
    @Suppress("ReturnCount")
    private suspend fun prepareResume(
        steps: List<AutomationWorkflowStep>,
        runId: String,
        retryUnknown: Boolean,
        durableJournal: WorkflowJournal,
    ): ResumePlan {
        val expectedKeys = steps.mapIndexed { index, step ->
            val prompt = AppJson.encodeToString(AutomationWorkflowStep.serializer(), step)
            index to durableJournal.computeKey(prompt, "automation_workflow:$index")
        }.toMap()
        val resume = try {
            durableJournal.resume(runId, expectedKeys)
        } catch (error: Exception) {
            return ResumePlan.Blocked(
                ToolOutcome.error("无法读取工作流断点:${error.message ?: "run_id 无效或日志不可读"}"),
            )
        }
        val resumeFrom = resume.resumeFromSeq
        val previousAtResume = try {
            durableJournal.load(runId).get(resumeFrom)
        } catch (error: Exception) {
            return ResumePlan.Blocked(
                ToolOutcome.error("无法读取工作流断点:${error.message ?: "run_id 无效或日志不可读"}"),
            )
        }
        val uncertainPrevious =
            previousAtResume?.takeIf { it.status == STATUS_RUNNING || it.status == STATUS_FAILED }
        if (uncertainPrevious != null && !retryUnknown) {
            val action = steps.getOrNull(resumeFrom)?.action ?: "unknown"
            return ResumePlan.Blocked(
                ToolOutcome.error(
                    "工作流在第 ${resumeFrom + 1} 步($action)中断，动作结果可能未知；为避免重复副作用，" +
                        "请确认后用同一 run_id 并显式设置 retry_unknown=true 重试。",
                    mapOf(
                        "runId" to runId,
                        "resumeFrom" to resumeFrom,
                        "outcomeUnknown" to true,
                        "uncertainAction" to action,
                    ),
                ),
            )
        }
        return ResumePlan.Ready(
            ResumeState(
                expectedKeys = expectedKeys,
                cached = resume.cached,
                resumeFrom = resumeFrom,
                cachedEnsureToRefresh = ensureToRefresh(steps, resumeFrom),
            ),
        )
    }

    /**
     * 需要刷新虚拟屏的那一步：当「下一步要用虚拟屏」而「下一步之前没有 virtual_ensure」时，
     * 必须把断点前最后一次 ensure 也算作过期 —— 否则复用的 displayId 可能已经被回收。
     */
    private fun ensureToRefresh(steps: List<AutomationWorkflowStep>, resumeFrom: Int): Int? {
        val nextDisplayActionIndex = steps.indices.firstOrNull { index ->
            index >= resumeFrom && steps[index].action.trim().lowercase() in DISPLAY_REQUIRED_VIRTUAL_ACTIONS
        }
        val nextEnsureIndex = steps.indices.firstOrNull { index ->
            index >= resumeFrom && steps[index].action.trim().equals("virtual_ensure", ignoreCase = true)
        }
        if (nextDisplayActionIndex == null || (nextEnsureIndex != null && nextDisplayActionIndex >= nextEnsureIndex)) {
            return null
        }
        return steps.indices.lastOrNull { index ->
            index < resumeFrom && steps[index].action.trim().equals("virtual_ensure", ignoreCase = true)
        }
    }

    /**
     * 命中缓存就直接还原（不重放设备动作）。返回 null 表示这一步没命中缓存，需要真正执行。
     */
    private suspend fun restoreFromCache(
        state: ResumeState,
        runId: String,
        index: Int,
        step: AutomationWorkflowStep,
    ): AutomationWorkflowStepResult? {
        val cached = state.cached[index] ?: return null
        if (index >= state.resumeFrom || cached.status != "done") return null
        val cachedStep = runCatching {
            AppJson.decodeFromString(AutomationWorkflowStepResult.serializer(), cached.result)
        }.getOrNull() ?: return null
        val restored = restoreCachedDisplayLease(
            runId = runId,
            index = index,
            step = step,
            cached = cachedStep,
            refreshDisplay = index == state.cachedEnsureToRefresh,
        )
        return when {
            !restored.success -> restored
            step.action.trim().equals("node_script", ignoreCase = true) ->
                restored.copy(message = "Node 脚本结果（安全断点恢复）: ${restored.message}")
            step.action.trim().equals("virtual_ensure", ignoreCase = true) && restored.displayId != null ->
                restored.copy(message = "虚拟屏已就绪 displayId=${restored.displayId}")
            else -> restored.copy(message = "已从工作流断点恢复")
        }
    }

    /** Persist intent before side effects and completion after; if completion persistence fails, next run blocks. */
    private suspend fun runStepDurably(
        journal: WorkflowJournal,
        runId: String,
        index: Int,
        key: String,
        step: AutomationWorkflowStep,
    ): AutomationWorkflowStepResult {
        try {
            journal.recordRequired(runId, index, key, "", STATUS_RUNNING, WorkflowJournal.NODE_KIND_TOOL_ONLY)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return failure(index, step.action, "无法持久化动作开始状态，未执行:${error.message ?: "日志写入失败"}")
        }
        val result = executeStep(index, step, runId)
        return try {
            journal.recordRequired(
                runId,
                index,
                key,
                resultForJournal(step, result),
                when {
                    result.success -> "done"
                    result.outcomeUnknown -> STATUS_FAILED
                    else -> STATUS_FAILED_KNOWN
                },
                if (step.action.trim().equals("node_script", ignoreCase = true)) {
                    WorkflowJournal.NODE_KIND_SENSITIVE_TOOL
                } else {
                    WorkflowJournal.NODE_KIND_TOOL_ONLY
                },
            )
            result
        } catch (cancelled: CancellationException) {
            // The action may already have taken effect. Keep the durable `running` marker so resume blocks
            // instead of silently replaying an unknown side effect.
            throw cancelled
        } catch (error: Exception) {
            failure(index, step.action, "动作已尝试但无法持久化结果，恢复时会按未知副作用处理:${error.message ?: "日志写入失败"}", outcomeUnknown = true)
        }
    }

    private fun resultForJournal(step: AutomationWorkflowStep, result: AutomationWorkflowStepResult): String {
        // Screen snapshots may contain private messages or account data; keep them in the live tool response only.
        if (step.action.trim().lowercase() in setOf("read", "virtual_read")) return ""
        return AppJson.encodeToString(AutomationWorkflowStepResult.serializer(), result)
    }

    private suspend fun executeStep(index: Int, step: AutomationWorkflowStep, runId: String): AutomationWorkflowStepResult {
        val action = step.action.trim().lowercase()
        return runCatching {
            when (action) {
                "launch" -> executeLaunch(index, action, step)
                "tap_text" -> executeTapText(index, action, step)
                "input_text" -> executeInputText(index, action, step)
                "swipe" -> executeSwipe(index, action, step)
                "back" -> result(index, action, manager.back(), "返回")
                "home" -> result(index, action, manager.home(), "回到桌面")
                "wait" -> executeWait(index, action, step)
                "read" -> executeRead(index, action)
                "node_script" -> executeNodeScript(index, action, step)
                "virtual_ensure", "virtual_launch", "virtual_tap", "virtual_tap_text", "virtual_swipe", "virtual_text",
                "virtual_key", "virtual_wait", "virtual_read", "virtual_close",
                -> executeVirtualStep(index, action, step, runId)
                else -> failure(index, action, "不支持的动作")
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
        }.getOrElse { error -> failure(index, action, "执行异常:${error.message ?: "unknown"}", outcomeUnknown = true) }
    }

    private suspend fun executeNodeScript(index: Int, action: String, step: AutomationWorkflowStep): AutomationWorkflowStepResult {
        val code = step.code?.takeIf { it.isNotBlank() }
            ?: return failure(index, action, "缺少 code")
        if (code.length > NodeScriptTool.MAX_SCRIPT_CHARS) {
            return failure(index, action, "脚本超过最大长度(${NodeScriptTool.MAX_SCRIPT_CHARS} 字符)")
        }
        val executor = nodeScriptExecutor ?: return failure(index, action, "Node 脚本运行时未初始化")
        val timeoutMs = (step.timeoutMs ?: DEFAULT_NODE_SCRIPT_TIMEOUT_MS).coerceIn(1_000L, MAX_NODE_SCRIPT_TIMEOUT_MS)
        return when (val outcome = executor(code, timeoutMs)) {
            is SkillEngineResult.Success -> result(
                index,
                action,
                true,
                NodeScriptTool.formatResultJson(outcome),
            )
            is SkillEngineResult.Error -> failure(
                index,
                action,
                "Node 脚本执行失败，副作用可能已发生；不会自动重放。" +
                    NodeScriptTool.formatResultJson(outcome),
                outcomeUnknown = true,
            )
        }
    }

    private suspend fun executeLaunch(index: Int, action: String, step: AutomationWorkflowStep): AutomationWorkflowStepResult {
        val pkg = step.packageName?.trim().orEmpty()
        return if (!AppControlCore.isValidPackageName(pkg)) {
            failure(index, action, "包名无效")
        } else {
            result(index, action, manager.launchApp(pkg), "启动 $pkg")
        }
    }

    private suspend fun executeTapText(index: Int, action: String, step: AutomationWorkflowStep): AutomationWorkflowStepResult {
        val text = step.text?.takeIf { it.isNotBlank() } ?: return failure(index, action, "缺少 text")
        val outcome = manager.tapByTextWithRetryDetailed(
            text = text,
            exact = step.exact,
            maxSwipes = step.maxSwipes,
            verifyText = step.verifyText?.takeIf { it.isNotBlank() },
        )
        return result(index, action, outcome.success, "匹配=${outcome.matched},验证=${outcome.verified},尝试=${outcome.attempts}")
    }

    private suspend fun executeInputText(index: Int, action: String, step: AutomationWorkflowStep): AutomationWorkflowStepResult {
        val text = step.text ?: return failure(index, action, "缺少 text")
        return result(index, action, manager.inputText(text), "已输入文本（${text.length} 个字符）")
    }

    private suspend fun executeSwipe(index: Int, action: String, step: AutomationWorkflowStep): AutomationWorkflowStepResult {
        val points = listOf(step.x1, step.y1, step.x2, step.y2)
        return if (points.any { it == null }) {
            failure(index, action, "缺少 x1/y1/x2/y2")
        } else {
            result(
                index,
                action,
                manager.swipe(step.x1!!, step.y1!!, step.x2!!, step.y2!!, step.durationMs.coerceIn(50L, 5_000L)),
                "滑动",
            )
        }
    }

    private suspend fun executeWait(index: Int, action: String, step: AutomationWorkflowStep): AutomationWorkflowStepResult {
        val ms = step.durationMs.coerceIn(50L, 10_000L)
        delay(ms)
        return result(index, action, true, "等待 ${ms}ms")
    }

    private suspend fun executeRead(index: Int, action: String): AutomationWorkflowStepResult {
        val screen = manager.readScreen()
        return result(index, action, true, screen.toSummary(20))
    }

    /** 创建虚拟屏并把 displayId 登记为本 run 的 lease，回一条就绪结果。 */
    private suspend fun ensureAndLease(
        index: Int,
        action: String,
        step: AutomationWorkflowStep,
        runId: String,
        client: VirtualDisplayClient,
    ): AutomationWorkflowStepResult {
        val display = ensureVirtualDisplay(step, client)
            .getOrElse { return failure(index, action, "创建虚拟屏失败:${it.message}") }
        displayLeases.acquire(runId, display.displayId)
        return result(
            index,
            action,
            true,
            "虚拟屏已就绪 displayId=${display.displayId} (${display.width}x${display.height})",
            displayId = display.displayId,
        )
    }

    /** 虚拟屏 id 解析结果：要么可用，要么已经产出终止结果。 */
    private sealed interface DisplayResolution {
        class Ready(val displayId: Int) : DisplayResolution
        class Done(val result: AutomationWorkflowStepResult) : DisplayResolution
    }

    /**
     * 解析本步要用的虚拟屏 displayId（含 lease 归属校验、`virtual_ensure` 之后的兼容登记、<0 时自动创建）。
     *
     * 从 [executeVirtualStep] 前半段搬出：这段有 4 个提前 return 和 3 层嵌套，
     * 和后面的动作分发叠在一起时读不出主流程。搬出后主流程只剩「解析 id → 分发动作」。
     */
    @Suppress("ReturnCount", "LongMethod")
    private suspend fun resolveDisplay(
        index: Int,
        action: String,
        step: AutomationWorkflowStep,
        runId: String,
        client: VirtualDisplayClient,
        displayManager: VirtualDisplayServerManager,
    ): DisplayResolution {
        val leasedDisplayId = displayLeases.displayFor(runId)
        if (step.displayId != null && leasedDisplayId != null && !displayLeases.isKnownDisplayId(runId, step.displayId)) {
            return DisplayResolution.Done(
                failure(index, action, "displayId=${step.displayId} 不属于当前 run_id 的虚拟屏 lease"),
            )
        }
        if (action == "virtual_close" && leasedDisplayId == null) {
            return DisplayResolution.Done(failure(index, action, "当前 run_id 没有可释放的虚拟屏 lease"))
        }
        val displayId = leasedDisplayId ?: step.displayId ?: displayManager.lastDisplayId
        if (displayId < 0) {
            if (action != "virtual_launch") {
                return DisplayResolution.Done(failure(index, action, "虚拟屏未创建；请先执行 virtual_ensure"))
            }
            val display = ensureVirtualDisplay(step, client).getOrElse {
                return DisplayResolution.Done(failure(index, action, "创建虚拟屏失败:${it.message}"))
            }
            displayLeases.acquire(runId, display.displayId)
            return DisplayResolution.Done(
                launchOnVirtualDisplay(index, action, step, client, display.displayId),
            )
        }
        if (leasedDisplayId == null && action != "virtual_close") {
            // 兼容旧的 workflow：历史步骤依赖 manager.lastDisplayId，
            // 首次使用时把该兼容 display 登记为共享 lease，后续 close 仍受 owner 保护。
            displayLeases.acquire(runId, displayId)
        }
        return DisplayResolution.Ready(displayId)
    }

    /**
     * 关闭虚拟屏：释放 lease，能销毁就销毁；服务重启导致屏幕已消失时按**幂等成功**处理。
     */
    private suspend fun closeVirtualDisplay(
        index: Int,
        action: String,
        runId: String,
        displayId: Int,
        client: VirtualDisplayClient,
        displayManager: VirtualDisplayServerManager,
    ): AutomationWorkflowStepResult {
        val canDestroy = displayLeases.release(runId, displayId)
        if (!canDestroy) {
            return result(index, action, true, "已释放 run_id 对虚拟屏 $displayId 的 lease，屏幕仍被其他 workflow 使用")
        }
        val destroyed = client.destroy(displayId)
        // A service restart can make the old display disappear before close.
        // VirtualDisplayClient clears the stale compatibility id in that case;
        // treat the already-gone display as an idempotent successful close and
        // never re-acquire a lease for a dead display.
        val staleDisplayGone = !destroyed && displayManager.lastDisplayId != displayId
        if (!destroyed && !staleDisplayGone) displayLeases.acquire(runId, displayId)
        return result(
            index,
            action,
            destroyed || staleDisplayGone,
            if (staleDisplayGone) "虚拟屏 $displayId 已不存在，按幂等关闭处理" else "销毁虚拟屏 $displayId",
        )
    }

    private suspend fun executeVirtualStep(
        index: Int,
        action: String,
        step: AutomationWorkflowStep,
        runId: String,
    ): AutomationWorkflowStepResult {
        val client = virtualDisplayClient
        val displayManager = virtualDisplayManager
        if (client == null || displayManager == null) return failure(index, action, "虚拟屏工作流未初始化")
        if (action == "virtual_ensure") return ensureAndLease(index, action, step, runId, client)

        val displayId = when (val resolved = resolveDisplay(index, action, step, runId, client, displayManager)) {
            is DisplayResolution.Done -> return resolved.result
            is DisplayResolution.Ready -> resolved.displayId
        }

        return when (action) {
            "virtual_launch" -> launchOnVirtualDisplay(index, action, step, client, displayId)
            "virtual_read" -> {
                val screen = manager.readScreenOnDisplay(displayId)
                    ?: return failure(index, action, "虚拟屏语义读屏不可用；需要 Shizuku 或 Root")
                result(index, action, true, screen.toSummary(30))
            }
            "virtual_tap_text" -> executeVirtualTapText(index, action, step, displayManager, displayId)
            "virtual_close" -> closeVirtualDisplay(index, action, runId, displayId, client, displayManager)
            else -> executeVirtualInput(index, action, step, displayManager, displayId)
        }
    }

    private suspend fun restoreCachedDisplayLease(
        runId: String,
        index: Int,
        step: AutomationWorkflowStep,
        cached: AutomationWorkflowStepResult,
        refreshDisplay: Boolean,
    ): AutomationWorkflowStepResult {
        if (!step.action.trim().equals("virtual_ensure", ignoreCase = true)) return cached
        val displayId = cached.displayId
            ?: CACHED_DISPLAY_ID_REGEX.find(cached.message)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return cached
        displayLeases.acquire(runId, displayId)
        if (!refreshDisplay) return cached.copy(displayId = displayId)

        val client = virtualDisplayClient ?: return failure(index, step.action, "虚拟屏工作流未初始化")
        val display = ensureVirtualDisplay(step, client)
            .getOrElse { return failure(index, step.action, "恢复虚拟屏失败:${it.message}") }
        displayLeases.acquire(runId, display.displayId)
        return cached.copy(
            message = "虚拟屏服务重启后已刷新 displayId=${display.displayId} (${display.width}x${display.height})",
            displayId = display.displayId,
        )
    }

    private suspend fun ensureVirtualDisplay(
        step: AutomationWorkflowStep,
        client: VirtualDisplayClient,
    ): Result<VirtualDisplayClient.DisplayHandle> {
        val width = step.width ?: 720
        val height = step.height ?: 1280
        val dpi = step.dpi ?: 320
        if (width !in 320..1920 || height !in 320..2560 || dpi !in 120..640) {
            return Result.failure(IllegalArgumentException("虚拟屏尺寸或密度超出安全范围"))
        }
        return client.ensureDisplay(width, height, dpi)
    }

    private suspend fun launchOnVirtualDisplay(
        index: Int,
        action: String,
        step: AutomationWorkflowStep,
        client: VirtualDisplayClient,
        displayId: Int,
    ): AutomationWorkflowStepResult {
        val packageName = step.packageName?.trim().orEmpty()
        if (!AppControlCore.isValidPackageName(packageName)) return failure(index, action, "包名无效")
        return result(index, action, client.openApp(packageName, displayId), "在虚拟屏 $displayId 启动 $packageName")
    }

    /** Semantic interaction stays on the selected virtual display and never falls back to the user's foreground UI. */
    private suspend fun executeVirtualTapText(
        index: Int,
        action: String,
        step: AutomationWorkflowStep,
        displayManager: VirtualDisplayServerManager,
        displayId: Int,
    ): AutomationWorkflowStepResult {
        val text = step.text?.trim()?.takeIf { it.isNotEmpty() }
            ?: return failure(index, action, "缺少 text")
        val outcome = VirtualDisplaySemanticActions.tapText(
            manager = manager,
            displayManager = displayManager,
            displayId = displayId,
            text = text,
            exact = step.exact,
            maxSwipes = step.maxSwipes,
            verifyText = step.verifyText,
        )
        return if (outcome.success) {
            result(index, action, true, "匹配=${outcome.matched},验证=${outcome.verified},尝试=${outcome.attempts}")
        } else {
            failure(
                index,
                action,
                "匹配=${outcome.matched},验证=${outcome.verified},尝试=${outcome.attempts}${outcome.error?.let { ",$it" }.orEmpty()}",
                outcomeUnknown = outcome.outcomeUnknown,
            )
        }
    }

    // 参数校验型函数：每个不合法输入都是一句直接回给模型的话（文本不支持/坐标非法/按键非法/策略拒绝），
    // 拆成 Result 只会让调用点更长。
    @Suppress("ReturnCount")
    private suspend fun executeVirtualInput(
        index: Int,
        action: String,
        step: AutomationWorkflowStep,
        displayManager: VirtualDisplayServerManager,
        displayId: Int,
    ): AutomationWorkflowStepResult {
        val inputAction = action.removePrefix("virtual_")
        val args = buildMap {
            step.x?.let { put("x", it.toString()) }
            step.y?.let { put("y", it.toString()) }
            step.x1?.let { put("x1", it.toString()) }
            step.y1?.let { put("y1", it.toString()) }
            step.x2?.let { put("x2", it.toString()) }
            step.y2?.let { put("y2", it.toString()) }
            put("duration_ms", step.durationMs.toString())
            put("ms", step.durationMs.toString())
            step.text?.let { put("text", it) }
            step.key?.let { put("key", it) }
        }
        step.text?.let { text ->
            VirtualDisplayInputCommand.textRejectionReason(text)?.let {
                return failure(index, action, "虚拟屏输入参数无效:$it")
            }
        }
        val command = VirtualDisplayInputCommand.build(displayId, inputAction, args)
            ?: return failure(index, action, "虚拟屏输入参数无效（检查坐标/按键是否为合法值）")
        if (command.waitMs != null) {
            delay(command.waitMs)
            return result(index, action, true, "虚拟屏 $displayId 等待 ${command.waitMs}ms")
        }
        when (val check = DeviceCommandPolicy.validate(command.shellCommand)) {
            is DeviceCommandPolicy.Check.Invalid -> return failure(index, action, "输入命令被策略拒绝:${check.reason}")
            DeviceCommandPolicy.Check.Valid -> Unit
        }
        val execution = displayManager.exec(command.shellCommand)
        return result(
            index,
            action,
            execution.exitCode == 0,
            if (execution.exitCode == 0) {
                "虚拟屏 $displayId ${command.description}成功"
            } else {
                "虚拟屏 $displayId ${command.description}失败(exit=${execution.exitCode})"
            },
        )
    }

    private fun result(index: Int, action: String, success: Boolean, message: String, displayId: Int? = null) =
        AutomationWorkflowStepResult(index, action, success, message, outcomeUnknown = !success, displayId = displayId)

    private fun failure(index: Int, action: String, message: String, outcomeUnknown: Boolean = false) =
        AutomationWorkflowStepResult(index, action, false, message, outcomeUnknown)
}

@Serializable
data class AutomationWorkflowStep(
    val action: String,
    val packageName: String? = null,
    val displayId: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val dpi: Int? = null,
    val x: Int? = null,
    val y: Int? = null,
    val key: String? = null,
    val text: String? = null,
    val verifyText: String? = null,
    val exact: Boolean = false,
    val maxSwipes: Int = 0,
    val x1: Int? = null,
    val y1: Int? = null,
    val x2: Int? = null,
    val y2: Int? = null,
    val durationMs: Long = 400L,
    val code: String? = null,
    val timeoutMs: Long? = null,
)

@Serializable
data class AutomationWorkflowStepResult(
    val index: Int,
    val action: String,
    val success: Boolean,
    val message: String,
    val outcomeUnknown: Boolean = false,
    val displayId: Int? = null,
)

internal object AutomationWorkflowParser {
    private const val MAX_STEPS = 20

    fun parse(json: String): Result<List<AutomationWorkflowStep>> = runCatching {
        val steps = AppJson.decodeFromString(
            ListSerializer(AutomationWorkflowStep.serializer()),
            json,
        )
        require(steps.isNotEmpty()) { "steps 不能为空" }
        require(steps.size <= MAX_STEPS) { "steps 最多支持 $MAX_STEPS 步" }
        steps
    }
}

private const val STATUS_RUNNING = "running"
private const val STATUS_FAILED = "failed"
private const val STATUS_FAILED_KNOWN = "failed_known"
private const val DEFAULT_NODE_SCRIPT_TIMEOUT_MS = 30_000L
private const val MAX_NODE_SCRIPT_TIMEOUT_MS = 300_000L
