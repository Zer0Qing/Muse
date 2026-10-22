package io.zer0.muse.tools

import android.content.Context

/**
 * P1-3b 拆域：定时任务工具注册器。
 *
 * 注册 scheduled_task_create / list / update / delete / execute / get_history。
 * 实现位于 [ScheduledTaskToolsImpl.kt]。
 */
class ScheduledTaskToolsRegistrar(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
) {
    private val impl = ScheduledTaskToolsImpl(context)

    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "scheduled_task_create",
                // v1.0.75 fix (工具审查 01): 补 cron 条件必填说明与示例
                // v2.6.7: 删掉「仅 always 可靠」——条件参数已补齐,这句会让模型主动避开所有条件
                description = "创建一个定时任务,到点后由 AI 执行 prompt。" +
                    "如每天 9 点提醒: interval='daily' 或 interval='cron' + cron_expr='0 9 * * *'。" +
                    "可按条件跳过执行(condition_type),条件参数必须一起给全,否则创建会被拒绝。",
                parameters = mapOf(
                    "name" to "必填,任务名称",
                    "prompt" to "必填,任务提示词",
                    "interval" to "可选,once/hourly/daily/weekly/cron,默认 daily",
                    "cron_expr" to "可选,interval='cron' 时必填。标准 5 段 cron,如 '0 9 * * *'(每天 9 点)、'0 */2 * * *'(每 2 小时)。非 cron 模式忽略",
                    "assistant_id" to "可选,执行助手,默认 default",
                    "action_type" to "可选,ai_prompt/create_quick_note/call_tool/notify",
                    "condition_type" to CONDITION_TYPE_DESC,
                    "condition_start_hour" to "可选,condition_type=time_range 时必填,0-23",
                    "condition_end_hour" to "可选,condition_type=time_range 时必填,0-23",
                    "condition_keyword" to "可选,condition_type=contains 时必填;quick_note_exists 时与 condition_tag 至少给一个",
                    "condition_tag" to "可选,condition_type=quick_note_exists 时的笔记标签",
                    "condition_min_level" to "可选,condition_type=battery_level 时必填,1-100",
                ),
                required = setOf("name", "prompt"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execScheduledTaskCreate(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "scheduled_task_list",
                description = "列出定时任务。",
                parameters = mapOf(
                    "enabled" to "可选,true/false 过滤启用状态",
                    "limit" to "可选,返回数量,默认 20",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.execScheduledTaskList(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "scheduled_task_update",
                description = "更新定时任务。只传要改的字段;改 condition_type 时必须同时给全该类型的条件参数。",
                parameters = mapOf(
                    "id" to "必填,任务 id",
                    "name" to "可选,新名称",
                    "prompt" to "可选,新提示词",
                    "interval" to "可选,新间隔",
                    "cron_expr" to "可选,新 cron 表达式",
                    "enabled" to "可选,true/false",
                    "condition_type" to CONDITION_TYPE_DESC,
                    "condition_start_hour" to "可选,time_range 时必填,0-23",
                    "condition_end_hour" to "可选,time_range 时必填,0-23",
                    "condition_keyword" to "可选,contains 时必填;quick_note_exists 时与 condition_tag 至少给一个",
                    "condition_tag" to "可选,quick_note_exists 时的笔记标签",
                    "condition_min_level" to "可选,battery_level 时必填,1-100",
                ),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execScheduledTaskUpdate(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "scheduled_task_delete",
                description = "删除定时任务。",
                parameters = mapOf("id" to "必填,任务 id"),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execScheduledTaskDelete(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "scheduled_task_execute",
                description = "立即触发一次定时任务。",
                parameters = mapOf("id" to "必填,任务 id"),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execScheduledTaskExecute(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "scheduled_task_get_history",
                description = "查询定时任务执行历史。",
                parameters = mapOf(
                    "id" to "必填,任务 id",
                    "limit" to "可选,返回数量,默认 10",
                ),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.execScheduledTaskGetHistory(args) }
    }

    private companion object {
        /**
         * 条件类型说明。
         *
         * 必须和 ScheduledTaskToolsImpl.buildConditionJson 支持的类型一一对应 ——
         * 列出引擎不支持的类型会让模型建出永远不满足条件的任务。
         */
        const val CONDITION_TYPE_DESC =
            "可选,默认 always。always=总是执行;network_available=有网络时;" +
                "time_range=指定时段内(需 condition_start_hour/end_hour);" +
                "contains=prompt 含关键词时(需 condition_keyword);" +
                "quick_note_exists=存在匹配笔记时(需 condition_keyword 或 condition_tag);" +
                "battery_level=电量高于阈值时(需 condition_min_level);charging=充电时"
    }
}
