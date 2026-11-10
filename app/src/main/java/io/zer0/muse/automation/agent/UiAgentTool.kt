package io.zer0.muse.automation.agent

import io.zer0.muse.tools.ToolOutcome
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel

/**
 * v2.2.1: 把 GUI Agent 环注册为 `ui_agent` 工具(HIGH,强制审批)。
 */
class UiAgentTool(private val runner: UiAgentRunner) {

    fun register(registry: ToolRegistry) {
        registry.registerOutcomeWithContext(
            ToolRegistry.ToolDef(
                name = "ui_agent",
                description = "GUI Agent 环:用视觉模型看着屏幕,一步步操作手机完成跨 App 的多步任务" +
                    "(如\"打开微信给张三发消息\")。内部自动循环:截图→视觉决策→点击/滑动/输入,默认最多 15 步。" +
                    "display=auto(默认) 在有 package_name 且独立虚拟屏可用时优先隔离执行,否则降级到前台无障碍;" +
                    "display=foreground 强制当前主屏;display=virtual 强制独立屏并要求 Shizuku/Root。" +
                    "需要视觉辅助已配置(设置→视觉辅助)。每步会截取当前执行界面并发送到所选视觉模型分析，截图可能包含聊天或账号等敏感信息；仅在用户明确授权的任务范围内调用。" +
                    "单步操作请直接用 screen_* 工具,多步流程再用本工具;" +
                    "如果步骤已经明确(知道点哪里、输什么),优先 automation_workflow —— 更省、可断点续跑。",
                parameters = mapOf(
                    "task" to "必填。要完成的任务描述,尽量具体(目标 App、对象、操作意图)",
                    "max_steps" to "可选。最大步数 3-30,默认 15",
                    "budget_minutes" to "可选。总时间预算 1-15 分钟,默认 5;长任务或界面很慢时可调大",
                    "display" to "可选。auto(默认) 有目标包名时优先独立虚拟屏，否则前台；也可指定 foreground 或 virtual",
                    "package_name" to "可选目标应用包名，例如 com.example.app；virtual 模式必填，auto 模式提供后会先尝试隔离运行",
                ),
                required = setOf("task"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args, executionContext ->
            val task = args["task"]?.trim().orEmpty()
            if (task.isBlank()) return@registerOutcomeWithContext ToolOutcome.error("错误:缺少 task 参数")
            val maxSteps = args["max_steps"]?.toIntOrNull() ?: UiAgentRunner.DEFAULT_MAX_STEPS
            val displayMode = when (args["display"]?.trim()?.lowercase()) {
                null, "", "auto" -> UiAgentRunner.DisplayMode.AUTO
                "foreground" -> UiAgentRunner.DisplayMode.FOREGROUND
                "virtual" -> UiAgentRunner.DisplayMode.VIRTUAL
                else -> return@registerOutcomeWithContext ToolOutcome.error("错误:display 只支持 auto、foreground 或 virtual")
            }
            // 把编排器给的进度回调透给 runner：15 步的任务中途用户能看到「第 N 步:...」。
            val budgetMinutes = args["budget_minutes"]?.toIntOrNull()
            val result = runner.run(
                task = task,
                maxSteps = maxSteps,
                budgetMs = (budgetMinutes ?: 5).coerceIn(1, 15) * 60_000L,
                onProgress = { msg -> executionContext?.onProgress?.invoke(msg) },
                displayMode = displayMode,
                packageName = args["package_name"],
            )
            val content = buildString {
                append(if (result.finished) "任务结束:" else "任务未完成:")
                append('\n')
                append(result.summary)
            }
            if (result.finished) {
                ToolOutcome.ok(content, mapOf("finished" to true))
            } else {
                // A vision/screenshot/channel/max-step failure must not be reported to the
                // outer Agent loop as a successful tool invocation.
                ToolOutcome.error(content, mapOf("finished" to false))
            }
        }
    }
}
