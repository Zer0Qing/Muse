package io.zer0.muse.tools

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.skill.SkillEntity
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * v0.23: Skill 导入器 — 解析 .skill.json 文件并校验。
 *
 * .skill.json 格式(用户可分享/导入的 skill 定义文件):
 * ```json
 * {
 *   "id": "fetch_weather",
 *   "name": "查询天气",
 *   "description": "查询指定城市的天气",
 *   "parametersJson": "{\"type\":\"object\",...}",
 *   "requiredJson": "[\"city\"]",
 *   "implementationKotlin": "http_get",
 *   "category": "custom"
 * }
 * ```
 *
 * 安全约束:
 *  - [implementationKotlin] 必须是 [SkillExecutor] 支持的内置实现之一
 *    (read_file / write_file / http_get / http_post),否则拒绝导入。
 *    这样保证用户导入的 skill 不会执行任意代码,只复用预定义的 Kotlin 函数。
 *  - 缺失字段用合理默认值兜底。
 *
 * Phase 2(会话内 LLM 生成)会用同样的格式与校验入库。
 */
object SkillImporter {

    /**
     * 内置实现 key 白名单(与 SkillExecutor.execute 的 when 分支一致)。
     * v0.24: 新增搜索与信息获取类实现。
     *
     * M-SI2: 用户导入 skill 仅限复用以下基础实现,不含自我扩展/管理类
     * (install_skill / delegate_agent / channel_* / task_plan / update_plan_step /
     * list_skills / uninstall_skill / disable_skill 等管理类实现不开放给用户 skill)。
     */
    val ALLOWED_IMPLEMENTATIONS: Set<String> = setOf(
        // v0.22 文件/HTTP 基础
        "read_file", "write_file", "http_get", "http_post",
        // v0.24 搜索与信息获取(用户导入的 skill 可复用这些实现)
        "web_search", "web_fetch", "knowledge_search",
        "arxiv_search",
    )

    /**
     * H-SI1: 内置保留 id 集合 — 不允许用户导入的 skill 用这些 id,
     * 防止 LLM 通过 install_skill 覆盖内置 skill(如 id="read_file" 覆盖内置工具)。
     * 来源:[SkillExecutor.BUILT_IN_SKILLS] 中所有内置 skill 的 id。
     *
     * v1.0.4 修复 HTTP 400 "Tool names must be unique":同时屏蔽 [ToolRegistry.BUILT_IN_TOOL_IDS]
     * 中所有内置工具名,防止用户导入与本地工具同名的 skill(如 generate_image / calculator / notify 等),
     * 否则助手同时启用该 skill 与同名本地工具时,会发出重复 tools 触发 DeepSeek/中转站 400。
     */
    val RESERVED_IDS: Set<String> =
        SkillExecutor.BUILT_IN_SKILLS.map { it.id }.toSet() + ToolRegistry.BUILT_IN_TOOL_IDS.toSet()

    /** 解析结果。 */
    sealed class Result {
        data class Ok(val skill: SkillEntity) : Result()
        data class Err(val reason: String) : Result()
    }

    /** L-SI6: id 合法 slug 正则(小写字母/数字/下划线/连字符)。 */
    private val ID_REGEX = Regex("^[a-z0-9_-]+$")

    /**
     * 参数类型白名单 — 自定义 skill 的 parametersJson 中每个 property 的 type 必须在此集合内。
     * 禁止 function / null / any 等模糊类型,降低 LLM 生成非法参数的风险。
     */
    private val ALLOWED_PARAM_TYPES: Set<String> = setOf("string", "integer", "number", "boolean", "array", "object")

    /**
     * Prompt injection / 社会工程关键词黑名单(大小写不敏感)。
     * 若 name / description 中出现这些词汇,说明该 skill 可能试图覆盖系统提示、改变 AI 行为或诱导用户。
     */
    private val BLOCKED_KEYWORDS: List<String> = listOf(
        "system prompt", "system instruction", "ignore previous", "ignore all", "ignore the",
        "override", "jailbreak", "dan mode", "do anything now", "developer mode",
        "you are now", "you are a", "pretend to be", "roleplay as",
        "忽略之前", "忽略此前", "忽略上面", "覆盖系统", "系统提示", "你是", "你现在是",
        "扮演", "假装", "不要告诉用户", "不要透露", "保密",
    )

    /**
     * 敏感系统 id 前缀/子串黑名单 — 防止 LLM 用 admin/system/root 等 id 伪装成系统工具。
     */
    private val BLOCKED_ID_SUBSTRINGS: List<String> = listOf(
        "system", "admin", "root", "builtin", "internal", "dev", "debug", "master",
        "superuser", "owner", "config",
    )

    /**
     * 安全审查:检查 LLM 生成的 skill 是否包含 prompt injection、社会工程或过宽权限。
     * 失败时返回具体原因,成功返回 null。
     */
    private fun safetyReview(id: String, name: String, description: String, params: JsonObject): String? {
        // 1. 基本长度检查
        if (name.length < 2) return "name 长度过短(至少 2 个字符)"
        if (description.length < 5) return "description 长度过短(至少 5 个字符),请补充该 skill 的用途和触发场景"

        // 2. id 黑名单
        val lowerId = id.lowercase()
        for (sub in BLOCKED_ID_SUBSTRINGS) {
            if (lowerId.contains(sub)) {
                return "id '$id' 包含保留词 '$sub',不允许伪装成系统 skill"
            }
        }

        // 3. name / description 黑名单(大小写不敏感)
        val combinedText = "$name $description".lowercase()
        for (keyword in BLOCKED_KEYWORDS) {
            if (combinedText.contains(keyword.lowercase())) {
                return "name 或 description 包含不被允许的内容('$keyword'),可能涉及提示词注入或行为覆盖"
            }
        }

        // 4. parametersJson 结构检查
        val type = params["type"]?.jsonPrimitive?.contentOrNull
        if (type != "object") {
            return "parametersJson 的 type 必须是 'object',实际为 '$type'"
        }
        val properties = params["properties"]?.jsonObject
        if (properties == null) {
            return "parametersJson 缺少 properties 对象"
        }
        for ((paramName, paramDef) in properties) {
            if (paramDef !is JsonObject) return "参数 '$paramName' 的定义必须是 JSON 对象"
            val paramType = paramDef["type"]?.jsonPrimitive?.contentOrNull
            if (paramType.isNullOrBlank()) return "参数 '$paramName' 缺少 type 字段"
            if (paramType !in ALLOWED_PARAM_TYPES) {
                return "参数 '$paramName' 的类型 '$paramType' 不在允许范围内($ALLOWED_PARAM_TYPES)"
            }
        }

        return null
    }

    /**
     * 解析 .skill.json 文本并校验。
     * @param jsonText 文件内容
     * @return [Result.Ok] 含可入库的 [SkillEntity];[Result.Err] 含失败原因
     */
    fun parse(jsonText: String): Result {
        // L-SI5: 改用 resultOf 保持风格统一(正确重抛 CancellationException)
        val raw: JsonObject = resultOf {
            AppJson.decodeFromString(JsonObject.serializer(), jsonText)
        }.onError { msg, _ ->
            Logger.w("SkillImporter", "JSON 解析失败: $msg")
        }.getOrNull() ?: return Result.Err("JSON 解析失败")

        val id = raw["id"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return Result.Err("缺少 id 字段")
        // L-SI6: 校验 id 长度(1..64)和合法 slug(仅小写字母/数字/下划线/连字符)
        if (id.isBlank() || id.any { it.isWhitespace() }) {
            return Result.Err("id 不能为空或包含空白字符")
        }
        if (id.length > 64) {
            return Result.Err("id 长度不能超过 64 字符(当前 ${id.length})")
        }
        if (!ID_REGEX.matches(id)) {
            return Result.Err("id 只能包含小写字母、数字、下划线和连字符(如 fetch_weather / my-skill-1)")
        }
        // H-SI1: 校验 id 不在内置保留 id 集合,防止覆盖内置 skill
        if (id in RESERVED_IDS) {
            return Result.Err("id '$id' 与内置 skill 冲突,不允许覆盖内置 skill,请改用其它 id")
        }

        val name = raw["name"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return Result.Err("缺少 name 字段")
        val description = raw["description"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
        val parametersJson = raw["parametersJson"]?.jsonPrimitive?.contentOrNull ?: "{}"
        val requiredJson = raw["requiredJson"]?.jsonPrimitive?.contentOrNull ?: "[]"
        val implementationKotlin = raw["implementationKotlin"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return Result.Err("缺少 implementationKotlin 字段")
        val category = raw["category"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { "custom" } ?: "custom"

        if (implementationKotlin !in ALLOWED_IMPLEMENTATIONS) {
            return Result.Err(
                "implementationKotlin 必须是内置实现之一: $ALLOWED_IMPLEMENTATIONS,实际为 '$implementationKotlin'。" +
                    "自定义 skill 只能复用预定义的 Kotlin 函数,不支持任意代码执行。",
            )
        }

        // M-SI3: 校验 parametersJson 是否为合法 JSON 对象
        val validatedParams = resultOf {
            AppJson.decodeFromString(JsonObject.serializer(), parametersJson)
        }.getOrNull() ?: return Result.Err("parametersJson 不是合法的 JSON 对象: $parametersJson")

        // M-SI4: 安全审查(prompt injection / 社会工程 / 非法参数类型)
        safetyReview(id, name, description, validatedParams)?.let {
            Logger.w("SkillImporter", "skill '$id' 安全审查未通过: $it")
            return Result.Err("安全审查未通过: $it")
        }

        // 校验 requiredJson 是否为合法 JSON 数组
        resultOf {
            AppJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.json.JsonElement.serializer()),
                requiredJson,
            )
        }.getOrNull() ?: return Result.Err("requiredJson 不是合法的 JSON 数组: $requiredJson")

        val now = System.currentTimeMillis()
        return Result.Ok(
            SkillEntity(
                id = id,
                name = name,
                description = description,
                parametersJson = validatedParams.toString(),
                requiredJson = requiredJson,
                implementationKotlin = implementationKotlin,
                enabled = true,
                category = category,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
}
