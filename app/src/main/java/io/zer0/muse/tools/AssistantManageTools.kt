package io.zer0.muse.tools

import io.zer0.common.Logger
import io.zer0.muse.data.assistant.AssistantRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 助手自管理工具集 —— 让助手能创建/修改/删除助手。
 *
 * ## 限权设计（这是全工具面里最敏感的）
 *
 * AI 改助手的配置可能形成越权闭环（例如把自己绑到更多工具上）。
 * 因此本工具集**只开放安全子集**：
 *
 *  | 操作 | 允许修改的字段 | 禁止修改的字段 |
 *  |---|---|---|
 *  | 创建 | name, systemPrompt, avatarEmoji | toolIdsJson, skillIdsJson, mcpServerIdsJson, modelId, providerId |
 *  | 更新 | systemPrompt（人设文案） | toolIdsJson, skillIdsJson, mcpServerIdsJson, modelId, providerId, reasoningLevel |
 *  | 删除 | 整个助手 | 默认助手不可删；不可删自己当前所属助手 |
 *
 * 不允许改绑定（工具/技能/MCP）——那是用户在助手设置页的显式配置，
 * AI 修改会导致越权（给自己挂更多工具）。模型/供应商同理（改了可能产生费用）。
 *
 * 命名统一 `assistant_manage_`。
 */
class AssistantManageTools(
    private val assistantRepository: AssistantRepository,
) {
    companion object {
        private const val TAG = "AssistantManageTools"
        private const val DEFAULT_ASSISTANT_ID = "default"
    }

    fun register(registry: ToolRegistry) {
        registerList(registry)
        registerCreate(registry)
        registerUpdatePersona(registry)
        registerDelete(registry)
    }

    private fun registerList(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "assistant_manage_list",
                description = "列出全部助手（id、名称、描述）。用于修改/删除前确认助手 id。",
                parameters = emptyMap(),
                required = emptySet(),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { _ ->
            runBlocking {
                val all = assistantRepository.getAll()
                if (all.isEmpty()) {
                    "没有助手。"
                } else {
                    all.joinToString("\n") { a ->
                        val isDefault = if (a.id == DEFAULT_ASSISTANT_ID) " (默认)" else ""
                        "${a.id} | ${a.name}$isDefault"
                    }
                }
            }
        }
    }

    private fun registerCreate(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "assistant_manage_create",
                description = "创建一个新助手（只设置名称和人设文案；工具/模型/绑定由用户在设置页配置）。" +
                    "id 可省略（自动生成）。",
                parameters = mapOf(
                    "name" to "必填，助手名称",
                    "system_prompt" to "可选，人设文案（系统提示词）",
                    "assistant_id" to "可选，助手 id（省略时自动生成）",
                ),
                required = setOf("name"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val name = args["name"]?.trim().orEmpty()
            if (name.isBlank()) {
                errorJson("参数 name 缺失或为空")
            } else {
                runBlocking {
                    val id = args["assistant_id"]?.trim().takeUnless { it.isNullOrBlank() }
                        ?: "assistant-${System.currentTimeMillis()}"
                    val existing = assistantRepository.getById(id)
                    if (existing != null) {
                        errorJson("助手 id=$id 已存在（名称：${existing.name}）")
                    } else {
                        val entity = io.zer0.muse.data.assistant.AssistantEntity(
                            id = id,
                            name = name,
                            systemPrompt = args["system_prompt"]?.trim().orEmpty(),
                        )
                        assistantRepository.upsert(entity)
                        Logger.i(TAG, "工具创建助手: $id ($name)")
                        okJson("已创建助手「$name」（id=$id）。工具/模型/绑定请到助手设置页配置。")
                    }
                }
            }
        }
    }

    private fun registerUpdatePersona(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "assistant_manage_update_persona",
                description = "修改一个助手的人设文案（系统提示词）。只允许改人设，" +
                    "不允许改工具绑定/模型/供应商——那些由用户在设置页管理。",
                parameters = mapOf(
                    "assistant_id" to "必填，助手 id",
                    "system_prompt" to "必填，新人设文案",
                ),
                required = setOf("assistant_id", "system_prompt"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val id = args["assistant_id"]?.trim().orEmpty()
            val prompt = args["system_prompt"]?.trim().orEmpty()
            when {
                id.isBlank() -> errorJson("参数 assistant_id 缺失或为空")
                prompt.isBlank() -> errorJson("参数 system_prompt 不能为空（清空人设请用空格占位）")
                else -> runBlocking {
                    val assistant = assistantRepository.getById(id)
                    if (assistant == null) {
                        errorJson("未找到助手 $id")
                    } else {
                        assistantRepository.upsert(assistant.copy(systemPrompt = prompt))
                        Logger.i(TAG, "工具改助手人设: $id (${assistant.name})")
                        okJson("已修改助手「${assistant.name}」（$id）的人设文案。")
                    }
                }
            }
        }
    }

    private fun registerDelete(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "assistant_manage_delete",
                description = "删除一个助手。不可撤销。默认助手不可删除。" +
                    "删除前应先用 assistant_manage_list 确认助手 id。",
                parameters = mapOf(
                    "assistant_id" to "必填，助手 id",
                ),
                required = setOf("assistant_id"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val id = args["assistant_id"]?.trim().orEmpty()
            when {
                id.isBlank() -> errorJson("参数 assistant_id 缺失或为空")
                id == DEFAULT_ASSISTANT_ID -> errorJson("默认助手不可删除（与 UI 层保护一致）")
                else -> runBlocking {
                    val assistant = assistantRepository.getById(id)
                    if (assistant == null) {
                        errorJson("未找到助手 $id")
                    } else {
                        assistantRepository.delete(id)
                        Logger.i(TAG, "工具删除助手: $id (${assistant.name})")
                        okJson("已删除助手「${assistant.name}」（$id）。")
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
class AssistantManageToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val tools: AssistantManageTools,
) {
    init {
        tools.register(toolRegistry)
    }
}
