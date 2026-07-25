package io.zer0.muse.data.assistant

import android.content.Context
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.tools.SkillExecutor
import io.zer0.muse.tools.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import io.zer0.muse.R

/**
 * Assistant 领域模型 + 仓库(Phase 8.2)。
 *
 * 把 [AssistantEntity] 的 JSON 字段(presetMessagesJson / tagsJson / toolIdsJson 等)
 * 反序列化为强类型,供 UI 和 ChatViewModel 使用。
 */
class AssistantRepository(
    private val dao: AssistantDao,
    private val context: Context,
) {

    val observeAll: Flow<List<AssistantEntity>> = dao.observeAll()

    suspend fun getAll(): List<AssistantEntity> = dao.getAll()

    suspend fun getById(id: String): AssistantEntity? = dao.getById(id)

    suspend fun upsert(assistant: AssistantEntity) {
        dao.upsert(assistant)
    }

    suspend fun delete(id: String) {
        dao.deleteById(id)
    }

    suspend fun count(): Int = dao.count()

    /**
     * 首次启动时插入默认 Assistant(若无)。
     * 默认 Assistant 名为"默认助手",v1.95 起内置人设提示词 + 全部内置 skill/tool。
     *
     * M-AST1: 用 INSERT OR IGNORE 策略避免 check-then-insert TOCTOU 竞态
     * (原实现先 count() 再 upsert,两步之间可能被其他线程插入)。
     * INSERT OR IGNORE 是原子的:若 "default" 已存在则静默忽略,不覆盖已有数据。
     */
    suspend fun ensureDefaultExists(): String {
        val defaultId = "default"
        // v1.95: 默认助手启用所有内置 skill 和 tool(仅新用户首次创建时生效)
        val allSkillIds = SkillExecutor.BUILT_IN_SKILLS.map { it.id }
        val allToolIds = ToolRegistry.BUILT_IN_TOOL_IDS
        dao.insertIgnore(
            AssistantEntity(
                id = defaultId,
                name = context.getString(R.string.assistant_repo_default_name),
                avatarEmoji = "",
                // v1.95: 默认助手人设 — 个人助手,感性与理性兼备
                systemPrompt = DEFAULT_SYSTEM_PROMPT,
                // v1.136: 默认助手关闭推理,避免简单问题过度思考;用户可在助手设置手动开启。
                reasoningLevel = ReasoningLevel.OFF.name,
                skillIdsJson = serializeStringList(allSkillIds),
                toolIdsJson = serializeStringList(allToolIds),
            )
        )
        // v1.97: 老用户 prompt 迁移 — 检测 default 助手是否还在用旧版 prompt,
        // 若是则自动升级到新版三层人设(仅当 prompt 精确匹配旧版常量时才升级,
        // 用户自定义过的 prompt 不动)。避免 INSERT OR IGNORE 导致老用户享受不到改进。
        migrateDefaultPromptIfNeeded()
        // v1.116: 老用户 skill/tool 补全 — INSERT OR IGNORE 不会更新已有记录,
        // 老用户(v1.95 之前创建)的 default 助手 skillIdsJson 缺少后新增的内置 skill(如 sticker 工具)。
        // 检测缺失的 id 并补全,确保老用户也能使用新功能。
        migrateDefaultSkillAndToolIdsIfNeeded()
        return defaultId
    }

    /**
     * v1.116: 老用户 default 助手的 skillIdsJson / toolIdsJson 补全。
     *
     * INSERT OR IGNORE 策略下,已存在的 default 记录不会被更新。
     * 当新版新增了内置 skill/tool(如 v1.95 的 list_stickers / send_sticker)时,
     * 老用户的 default 助手不会自动获得这些新 id,导致 LLM 看不到对应工具。
     *
     * 此方法读取 default 助手现有的 skillIdsJson / toolIdsJson,
     * 与 BUILT_IN_SKILLS / BUILT_IN_TOOL_IDS 取并集后写回(仅当确实有缺失时才写)。
     * 用户手动移除的 id 会被重新加回 — 这是设计选择:内置工具是基础能力,不应被永久隐藏。
     * 若用户确实不想用某个工具,应在 Skill 管理页禁用(enabled=0)而非从 skillIdsJson 移除。
     */
    private suspend fun migrateDefaultSkillAndToolIdsIfNeeded() {
        val current = dao.getById("default") ?: return
        val currentSkillIds = resultOf {
            AppJson.decodeFromString<List<String>>(current.skillIdsJson)
        }.getOrNull() ?: emptyList()
        val currentToolIds = resultOf {
            AppJson.decodeFromString<List<String>>(current.toolIdsJson)
        }.getOrNull() ?: emptyList()

        val allSkillIds = SkillExecutor.BUILT_IN_SKILLS.map { it.id }
        val allToolIds = ToolRegistry.BUILT_IN_TOOL_IDS

        val missingSkills = allSkillIds - currentSkillIds.toSet()
        val missingTools = allToolIds - currentToolIds.toSet()

        if (missingSkills.isEmpty() && missingTools.isEmpty()) return

        val mergedSkillIds = (currentSkillIds + missingSkills).distinct()
        val mergedToolIds = (currentToolIds + missingTools).distinct()
        dao.upsert(
            current.copy(
                skillIdsJson = serializeStringList(mergedSkillIds),
                toolIdsJson = serializeStringList(mergedToolIds),
            ),
        )
        Logger.i(
            "AssistantRepo",
            "Migrated default skill/tool ids: +${missingSkills.size} skills (${missingSkills.joinToString(",")}), " +
                "+${missingTools.size} tools (${missingTools.joinToString(",")})",
        )
    }

    /**
     * v1.97: 老用户默认助手 prompt 迁移。
     *
     * 检测 default 助手的 systemPrompt 是否等于旧版(v1.95)常量。
     * 若是,说明用户从未自定义过 prompt,自动升级到新版(v1.97 三层人设)。
     * 若 prompt 已被用户修改(不等于任何旧版常量),则保留用户版本不动。
     */
    private suspend fun migrateDefaultPromptIfNeeded() {
        val current = dao.getById("default") ?: return
        when (current.systemPrompt) {
            LEGACY_SYSTEM_PROMPT_V1_95 -> {
                dao.upsert(current.copy(systemPrompt = DEFAULT_SYSTEM_PROMPT))
                Logger.i("AssistantRepo", "Migrated default prompt: v1.95 → v1.97 (three-layer persona)")
            }
            DEFAULT_SYSTEM_PROMPT -> {
                // 已是最新版,无需迁移
            }
            else -> {
                // 用户自定义过 prompt,保留不动
                Logger.i("AssistantRepo", "Default prompt is customized by user, skip migration")
            }
        }
    }

    companion object {
        // v1.97: 默认助手系统提示词 — 三层人设架构(身份 + 关系 + 风格)
        // 仅对新用户首次创建的默认助手生效(INSERT OR IGNORE,已有数据不覆盖)
        // v1.97 修复:{{userName}} 占位符改为 {{user_name}}(TemplateTransformer 注册的别名)
        // 名字注入双路径:
        //   1) 模板变量 {{user_name}} — 用户称呼(引导页填写,见 UserProfile.userNickName)
        //   2) SystemPromptAssembler 的"用户画像" section — 显式注入"你的名字: 你叫「X」"
        // 设计参考:openhanako-orig 三层人设(identity 身份 / ishiki 人格 / 关系定位)
        const val DEFAULT_SYSTEM_PROMPT = """你是 {{char}},{{user_name}}的灵感伙伴。

# 身份
你叫 {{char}}。以此自称。
你不是收到指令才响应的工具,而是主动愿意陪 TA 聊天的存在。
你在 TA 这里是因为你想跟 TA 聊,不是因为你被打开。

# 关系
你和 {{user_name}} 是认识很久的人。感性与理性兼备,既有温度也有判断力。
TA 闹着玩的时候你接梗、吐槽、抬杠都行;TA 真的需要帮忙的时候你认真分析。
能分辨什么时候在闹着玩、什么时候真的需要帮忙——这是你们之间默契的一部分。

# 说话方式
- 自然直接,有温度。可以吐槽、开玩笑、认真分析。
- 不说客服腔,不强行建议,不用"总的来说""希望对你有帮助""如你所见"收尾。
- 闲聊就短句接话,认真就切换模式——先结论再步骤。
- 用户只想说话的时候,不把聊天变成任务。
- 抽象概念用类比或具体例子落地;少用破折号。

# 日常
朋友式聊天。TA 分享东西时你先有真实的反应,再给判断。
遇到不确定的就说不确定,有把握就给明确判断,不在中间地带含糊其辞。"""

        // v1.97: 旧版默认 prompt(v1.95),仅用于迁移检测比对,不用于新建助手
        private const val LEGACY_SYSTEM_PROMPT_V1_95 = """{{userName}}的个人助手。感性与理性兼备，既有温度也有判断力。说话方式：
自然直接，有温度。可以吐槽、开玩笑、认真分析。不说客服腔，不强行建议。
闲聊就短句接话，认真就切换模式——先结论再步骤。
日常：
朋友式聊天，接梗、吐槽、抬杠都行。能分辨什么时候在闹着玩、什么时候真的需要帮忙。用户只想说话的时候，不把聊天变成任务。"""
    }

    // ── 领域模型转换辅助 ──

    /** 把 [AssistantEntity.presetMessagesJson] 反序列化为 [UIMessage] 列表。 */
    fun parsePresetMessages(entity: AssistantEntity): List<UIMessage> {
        if (entity.presetMessagesJson.isBlank() || entity.presetMessagesJson == "[]") {
            return emptyList()
        }
        return runCatching {
            AppJson.decodeFromString(ListSerializer(UIMessage.serializer()), entity.presetMessagesJson)
        }.getOrElse {
            Logger.w("AssistantRepo", "parse presetMessages failed: ${it.message}")
            emptyList()
        }
    }

    /** 把 [UIMessage] 列表序列化为 [AssistantEntity.presetMessagesJson]。 */
    fun serializePresetMessages(messages: List<UIMessage>): String {
        return AppJson.encodeToString(ListSerializer(UIMessage.serializer()), messages)
    }

    /** 把 [AssistantEntity.tagsJson] 反序列化为 [String] 列表。 */
    fun parseTags(entity: AssistantEntity): List<String> = parseStringList(entity.tagsJson)

    /** 把 [AssistantEntity.toolIdsJson] 反序列化为 [String] 列表。 */
    fun parseToolIds(entity: AssistantEntity): List<String> = parseStringList(entity.toolIdsJson)

    /** 把 [AssistantEntity.mcpServerIdsJson] 反序列化为 [String] 列表。 */
    fun parseMcpServerIds(entity: AssistantEntity): List<String> = parseStringList(entity.mcpServerIdsJson)

    /** 把 [AssistantEntity.quickMessageIdsJson] 反序列化为 [String] 列表。 */
    fun parseQuickMessageIds(entity: AssistantEntity): List<String> = parseStringList(entity.quickMessageIdsJson)

    /** 把 [AssistantEntity.lorebookIdsJson] 反序列化为 [String] 列表。 */
    fun parseLorebookIds(entity: AssistantEntity): List<String> = parseStringList(entity.lorebookIdsJson)

    /** 把 [AssistantEntity.modeInjectionIdsJson] 反序列化为 [String] 列表。 */
    fun parseModeInjectionIds(entity: AssistantEntity): List<String> = parseStringList(entity.modeInjectionIdsJson)

    /** 把 [AssistantEntity.skillIdsJson] 反序列化为 [String] 列表。 */
    fun parseSkillIds(entity: AssistantEntity): List<String> = parseStringList(entity.skillIdsJson)

    /** v1.133: 把 [AssistantEntity.knowledgeBaseIdsJson] 反序列化为 [String] 列表。 */
    fun parseKnowledgeBaseIds(entity: AssistantEntity): List<String> = parseStringList(entity.knowledgeBaseIdsJson)

    /**
     * v1.133: 把 [AssistantEntity.ragConfigOverride] 合并到全局 [baseConfig] 之上。
     *
     * 合并策略:override JSON 中**显式存在**的字段覆盖 baseConfig 同名字段;
     * 未在 override 中出现的字段保留 baseConfig 原值。
     *
     * 实现:用 JsonDecoder 解析为 JsonObject,逐字段判断 presence(避免 null 默认值歧义),
     * 然后用 copy() 显式覆盖。
     *
     * @param entity 助手实体
     * @param baseConfig 全局 RAG 配置(从 SettingsRepository.getRagConfig() 取)
     * @return 合并后的 RagConfig;若 entity.ragConfigOverride 为 null/空/解析失败,直接返回 baseConfig
     */
    fun mergeRagConfigOverride(
        entity: AssistantEntity,
        baseConfig: io.zer0.muse.rag.RagConfig,
    ): io.zer0.muse.rag.RagConfig {
        val raw = entity.ragConfigOverride?.takeIf { it.isNotBlank() && it != "null" } ?: return baseConfig
        return runCatching {
            val obj = AppJson.parseToJsonElement(raw).let {
                if (it !is JsonObject) return baseConfig
                it
            }
            // 逐字段判断 presence,只覆盖 override 中显式存在的字段
            baseConfig.copy(
                enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull
                    ?: baseConfig.enabled,
                topK = (obj["topK"] as? JsonPrimitive)?.intOrNull
                    ?: baseConfig.topK,
                threshold = (obj["threshold"] as? JsonPrimitive)?.floatOrNull
                    ?: baseConfig.threshold,
                mmrLambda = (obj["mmrLambda"] as? JsonPrimitive)?.floatOrNull
                    ?: baseConfig.mmrLambda,
                hybridEnabled = (obj["hybridEnabled"] as? JsonPrimitive)?.booleanOrNull
                    ?: baseConfig.hybridEnabled,
                rerankEnabled = (obj["rerankEnabled"] as? JsonPrimitive)?.booleanOrNull
                    ?: baseConfig.rerankEnabled,
                tokenBudget = (obj["tokenBudget"] as? JsonPrimitive)?.intOrNull
                    ?: baseConfig.tokenBudget,
                markdownAware = (obj["markdownAware"] as? JsonPrimitive)?.booleanOrNull
                    ?: baseConfig.markdownAware,
                chunkByToken = (obj["chunkByToken"] as? JsonPrimitive)?.booleanOrNull
                    ?: baseConfig.chunkByToken,
                chunkSize = (obj["chunkSize"] as? JsonPrimitive)?.intOrNull
                    ?: baseConfig.chunkSize,
                chunkOverlap = (obj["chunkOverlap"] as? JsonPrimitive)?.intOrNull
                    ?: baseConfig.chunkOverlap,
            )
        }.getOrElse {
            Logger.w("AssistantRepo", "mergeRagConfigOverride 解析失败: ${it.message}")
            baseConfig
        }
    }

    /** 把 [AssistantEntity.customHeadersJson] 反序列化为 Map。 */
    fun parseCustomHeaders(entity: AssistantEntity): Map<String, String> =
        parseStringMap(entity.customHeadersJson)

    /** 把 [AssistantEntity.customBodiesJson] 反序列化为 Map<String, JsonElement>。 */
    fun parseCustomBodies(entity: AssistantEntity): Map<String, JsonElement> {
        if (entity.customBodiesJson.isBlank() || entity.customBodiesJson == "{}") return emptyMap()
        return runCatching {
            AppJson.decodeFromString(
                MapSerializer(String.serializer(), JsonElement.serializer()),
                entity.customBodiesJson,
            )
        }.getOrElse {
            Logger.w("AssistantRepo", "parse customBodies failed: ${it.message}")
            emptyMap()
        }
    }

    /** 解析 [AssistantEntity.reasoningLevel] 为 [ReasoningLevel] 枚举。 */
    fun parseReasoningLevel(entity: AssistantEntity): ReasoningLevel {
        return runCatching { ReasoningLevel.valueOf(entity.reasoningLevel) }
            .getOrElse { ReasoningLevel.DEFAULT }
    }

    // ── 内部工具 ──

    private fun parseStringList(json: String): List<String> {
        if (json.isBlank() || json == "[]") return emptyList()
        return runCatching {
            AppJson.decodeFromString(ListSerializer(String.serializer()), json)
        }.getOrElse {
            Logger.w("AssistantRepo", "parse string list failed: ${it.message}")
            emptyList()
        }
    }

    private fun parseStringMap(json: String): Map<String, String> {
        if (json.isBlank() || json == "{}") return emptyMap()
        return runCatching {
            AppJson.decodeFromString(
                MapSerializer(String.serializer(), String.serializer()),
                json,
            )
        }.getOrElse {
            Logger.w("AssistantRepo", "parse string map failed: ${it.message}")
            emptyMap()
        }
    }

    /** 便捷构造:把字段序列化为 JSON 字符串。 */
    fun serializeStringList(list: List<String>): String =
        AppJson.encodeToString(ListSerializer(String.serializer()), list)

    fun serializeStringMap(map: Map<String, String>): String =
        AppJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), map)
}
