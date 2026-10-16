package io.zer0.muse.tools

import io.zer0.common.Logger
import io.zer0.muse.data.skill.SkillRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 技能管理工具集 —— 让助手能对技能做日常管理。
 *
 * 此前助手只有 install_skill（新建），列/启停/删全部做不到。
 * 与知识库管理、会话管理同款模式。
 *
 * 命名统一 `skill_manage_`（避免与内置 skill 的 id 冲突）：
 *  - `skill_manage_list`     列出全部技能及启停状态（SAFE）
 *  - `skill_manage_toggle`   启停一个技能（NORMAL）
 *  - `skill_manage_delete`   删除一个自定义技能（HIGH；内置技能不可删）
 *
 * 设计约束：
 *  - 内置技能（BUILT_IN_SKILLS）不可删，只可启停；
 *  - 删除不可撤销，走审批。
 */
class SkillManageTools(
    private val skillRepository: SkillRepository,
) {
    companion object {
        private const val TAG = "SkillManageTools"

        /** 内置技能 id 集合（不可删除，只可启停）。 */
        private val BUILT_IN_SKILL_IDS = setOf(
            "knowledge_search", "web_search", "web_fetch", "http_get", "http_post",
            "arxiv_search", "read_file", "write_file", "install_skill", "author_plugin",
            "delegate_agent", "task_plan", "update_plan_step", "sticker_list", "sticker_send",
        )
    }

    fun register(registry: ToolRegistry) {
        registerList(registry)
        registerToggle(registry)
        registerDelete(registry)
    }

    private fun registerList(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "skill_manage_list",
                description = "列出全部技能及启停状态（id、名称、分类、是否启用、是否内置）。" +
                    "用于启停/删除前确认技能 id。",
                parameters = mapOf(
                    "query" to "可选，按名称或分类过滤",
                ),
                required = emptySet(),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            runBlocking {
                val query = args["query"]?.trim().orEmpty()
                val all = skillRepository.listEnabled()
                val filtered = if (query.isBlank()) {
                    all
                } else {
                    all.filter {
                        it.name.contains(query, ignoreCase = true) ||
                            it.category.contains(query, ignoreCase = true)
                    }
                }
                if (filtered.isEmpty()) {
                    "没有匹配的技能。"
                } else {
                    filtered.joinToString("\n") { s ->
                        "${s.id} | ${s.name} | ${s.category} | " +
                            "${if (s.enabled) "启用" else "停用"} | " +
                            "${if (s.id in BUILT_IN_SKILL_IDS) "内置" else "自定义"}"
                    }
                }
            }
        }
    }

    private fun registerToggle(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "skill_manage_toggle",
                description = "启用或停用一个技能。停用后助手无法再调用该技能，用户可在设置→Skill 重新开启。",
                parameters = mapOf(
                    "skill_id" to "必填，技能 id（可用 skill_manage_list 获取）",
                    "enabled" to "true=启用，false=停用；默认 true",
                ),
                required = setOf("skill_id"),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args ->
            val id = args["skill_id"]?.trim().orEmpty()
            val enabled = args["enabled"]?.trim()?.lowercase() != "false"
            if (id.isBlank()) {
                errorJson("参数 skill_id 缺失或为空")
            } else {
                runBlocking {
                    val skill = skillRepository.getById(id)
                    if (skill == null) {
                        errorJson("未找到技能 $id")
                    } else {
                        skillRepository.setEnabled(id, enabled)
                        Logger.i(TAG, "工具${if (enabled) "启用" else "停用"}技能: $id")
                        okJson("已${if (enabled) "启用" else "停用"}技能「${skill.name}」（$id）。")
                    }
                }
            }
        }
    }

    private fun registerDelete(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "skill_manage_delete",
                description = "删除一个自定义技能（不可撤销）。内置技能不可删除，只可启停。" +
                    "执行前应先用 skill_manage_list 确认技能 id。",
                parameters = mapOf(
                    "skill_id" to "必填，技能 id",
                ),
                required = setOf("skill_id"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val id = args["skill_id"]?.trim().orEmpty()
            when {
                id.isBlank() -> errorJson("参数 skill_id 缺失或为空")
                id in BUILT_IN_SKILL_IDS -> errorJson("内置技能 $id 不可删除（只可启停），与 UI 层保护一致")
                else -> runBlocking {
                    val skill = skillRepository.getById(id)
                    if (skill == null) {
                        errorJson("未找到技能 $id")
                    } else {
                        skillRepository.delete(id)
                        Logger.i(TAG, "工具删除技能: $id (${skill.name})")
                        okJson("已删除技能「${skill.name}」（$id）。")
                    }
                }
            }
        }
    }

    private fun okJson(message: String): String = buildJsonObject {
        put("ok", JsonPrimitive(true))
        put("message", JsonPrimitive(message))
    }.toString()

    private fun errorJson(message: String): String = buildJsonObject {
        put("ok", JsonPrimitive(false))
        put("error", JsonPrimitive(message))
    }.toString()
}

/** 注册器：走 Koin 注入，由 ToolRegistrarBootstrapper 统一引导。 */
class SkillManageToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val tools: SkillManageTools,
) {
    init {
        tools.register(toolRegistry)
    }
}
