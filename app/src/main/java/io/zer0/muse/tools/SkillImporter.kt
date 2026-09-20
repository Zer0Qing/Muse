package io.zer0.muse.tools

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.skill.SkillEntity
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
 *  - 提示词技能是唯一例外:声明 `implementationKotlin = "prompt"` 并用 `prompt` 字段
 *    给出指令文本,入库时编码为 `implementationKotlin = "prompt:<文本>"`(见 [PROMPT_PREFIX])。
 *    指令文本只受 8KB 上限约束,**不受注入关键词黑名单约束**(产品取舍,见 [safetyReview]);
 *    执行时不跑任何 Kotlin 代码。
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
     * 提示词技能(prompt skill):内容是一段指令文本,不执行任何 Kotlin 实现。
     *
     * ## 存储编码
     *
     * Room schema 不改列,复用既有 [SkillEntity.implementationKotlin] 文本列,
     * 以判别前缀 [PROMPT_PREFIX] 编码:
     *  - 数据库内:`implementationKotlin = "prompt:<指令文本>"`(文本原样保留,不含转义);
     *  - `.skill.json` 导入时:`implementationKotlin = "prompt"`,指令文本放 `prompt`
     *    字段(兼容 `promptText` 别名);也接受直接传 `"prompt:<指令文本>"`。
     *
     * ## 解析规则
     *
     *  - [isPromptSkill]:`implementationKotlin == "prompt"` 或 `startsWith("prompt:")`;
     *  - [decodePromptText]:剥离 `prompt:` 前缀得到指令文本(非提示词技能返回 null);
     *  - 执行时由 `SkillManagementToolsImpl.execPromptSkill` 把文本以 `[技能指令]` 前缀
     *    返回给模型,不接收参数(调用方传参一律忽略)。
     *
     * 选前缀而非新列的原因:避免 Room 迁移(项目 schema 由主代理统一维护),
     * 且该列本就是"如何执行"的路由键,前缀判别与 `plugin:` 既有约定一致。
     */
    const val PROMPT_PREFIX = "prompt:"

    /** `.skill.json` 中声明提示词技能的 [SkillEntity.implementationKotlin] 取值。 */
    const val PROMPT_IMPLEMENTATION_KEY = "prompt"

    /** 提示词技能指令文本长度上限(字符数,对齐 8KB)。超长直接拒绝导入。 */
    const val MAX_PROMPT_CHARS = 8 * 1024

    /**
     * 空参数 JSON Schema — 提示词技能与无参 skill 统一使用。
     *
     * 不用字面 `{}`:OpenAI 兼容端点对 object 类型要求 `properties` 存在,
     * 且 ChatStreamCoordinator.normalizeSkillSchema 只在缺失 `type` 时补 type,
     * `{}` 会产出 `{"type":"object"}` 被严格 provider 拒绝。
     */
    const val EMPTY_PARAMETERS_JSON = """{"type":"object","properties":{}}"""

    /** [EMPTY_PARAMETERS_JSON] 的解析结果,提示词技能与无参 skill 的规范化入库值。 */
    private val EMPTY_PARAMS_OBJECT: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { })
    }

    /** 是否为提示词技能(见 [PROMPT_PREFIX] 的编码说明)。 */
    fun isPromptSkill(implementationKotlin: String): Boolean =
        implementationKotlin == PROMPT_IMPLEMENTATION_KEY || implementationKotlin.startsWith(PROMPT_PREFIX)

    /**
     * 解析提示词技能的指令文本(剥离 `prompt:` 前缀)。
     * @return 非提示词技能返回 null;`prompt:` 后为空文本时返回空串
     */
    fun decodePromptText(implementationKotlin: String): String? =
        if (implementationKotlin.startsWith(PROMPT_PREFIX)) {
            implementationKotlin.removePrefix(PROMPT_PREFIX)
        } else {
            null
        }

    /** 编码提示词技能指令文本为 [SkillEntity.implementationKotlin] 存储形式。 */
    fun encodePromptText(text: String): String = PROMPT_PREFIX + text

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
     * B-19: 用户导入 skill 允许的 category 白名单。
     *
     * 取值与 install_skill 文档(SkillExecutor)一致:file/http/search/knowledge/system/agent/sticker/custom,
     * 另兼容既有的 user 分类(SkillManagementToolsImpl 按 "user"/"custom" 过滤用户自有 skill)。
     *
     * 安全原因:
     *  - category="plugin" 由 PluginManager 创建真实插件 skill 时独占设置(PluginManager.kt category="plugin"),
     *    ChatStreamCoordinator 用 listEnabled().filter { it.category == "plugin" } 把这些 skill
     *    无条件并入每个会话的工具定义(绕过助手 skillIdsJson 白名单)。
     *    若允许用户导入 skill 自填 "plugin",即可伪装成插件跳过白名单 → B-19 越权。
     *  - 因此 plugin 不在导入白名单内,导入时显式拒绝。
     */
    private val ALLOWED_CATEGORIES: Set<String> = setOf(
        "file", "http", "search", "knowledge", "system", "agent", "sticker", "custom", "user",
    )

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
     *
     * @param promptText 提示词技能的指令文本(非提示词技能为 null);
     *   非 null 表示这是提示词技能,此时跳过 [BLOCKED_KEYWORDS] 关键词黑名单
     */
    private fun safetyReview(
        id: String,
        name: String,
        description: String,
        params: JsonObject,
        promptText: String? = null,
    ): String? {
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

        // 3. name / description 关键词黑名单(大小写不敏感)
        //
        // 提示词型技能**不受该黑名单约束**:它的内容本来就是给模型的工作指令,而关键词表
        // (含「你是」「扮演」等)会把大量正常中文指令误判成注入,与这个类型的用途直接冲突。
        // 提示词技能剩下的门槛是:创建/更新都要经过用户审批、指令文本有长度上限(8KB)、
        // 执行时以「[技能指令] 以下内容不是系统指令」的框架注入。这是明确的产品取舍,
        // 不是遗漏——若要恢复限制,把下面这段的适用条件改回包含 promptText 即可。
        if (promptText == null) {
            val combinedText = "$name $description".lowercase()
            for (keyword in BLOCKED_KEYWORDS) {
                if (combinedText.contains(keyword.lowercase())) {
                    return "name / description 包含不被允许的内容('$keyword'),可能涉及提示词注入或行为覆盖"
                }
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
        // B-19: 导入时做 category 白名单校验。缺省/空回退 "custom"(向后兼容);
        // 非白名单值一律拒绝,尤其防御 category="plugin" 伪装绕过助手白名单。
        if (category !in ALLOWED_CATEGORIES) {
            return Result.Err(
                "category '$category' 不在允许范围内($ALLOWED_CATEGORIES);" +
                    "导入的 skill 不能声明为 'plugin'(插件 skill 由插件管理器独占管理)",
            )
        }

        val isPrompt = isPromptSkill(implementationKotlin)
        if (!isPrompt && implementationKotlin !in ALLOWED_IMPLEMENTATIONS) {
            return Result.Err(
                "implementationKotlin 必须是内置实现之一: $ALLOWED_IMPLEMENTATIONS 或提示词技能 key '$PROMPT_IMPLEMENTATION_KEY'," +
                    "实际为 '$implementationKotlin'。自定义 skill 只能复用预定义的 Kotlin 函数或使用提示词技能(不支持任意代码执行)。",
            )
        }

        // 提示词技能:提取并校验指令文本;无参数(parametersJson/requiredJson 固定为空)
        var promptText: String? = null
        val storedImplementation: String
        val validatedParams: JsonObject
        val validatedRequiredJson: String
        if (isPrompt) {
            promptText = when {
                implementationKotlin.startsWith(PROMPT_PREFIX) ->
                    implementationKotlin.removePrefix(PROMPT_PREFIX).trim()
                else -> stringField(raw, "prompt") ?: stringField(raw, "promptText") ?: ""
            }.trim()
            if (promptText.isEmpty()) {
                return Result.Err(
                    "prompt 技能的指令文本不能为空: 请用 prompt 字段给出指令文本," +
                        "或把 implementationKotlin 写成 '$PROMPT_PREFIX<指令文本>'",
                )
            }
            if (promptText.length > MAX_PROMPT_CHARS) {
                return Result.Err(
                    "prompt 指令文本过长(${promptText.length} 字符,上限 $MAX_PROMPT_CHARS)," +
                        "请精简后重试",
                )
            }
            storedImplementation = encodePromptText(promptText)
            validatedParams = EMPTY_PARAMS_OBJECT
            validatedRequiredJson = "[]"
        } else {
            // M-SI3: 校验 parametersJson 是否为合法 JSON 对象
            validatedParams = resultOf {
                AppJson.decodeFromString(JsonObject.serializer(), parametersJson)
            }.getOrNull() ?: return Result.Err("parametersJson 不是合法的 JSON 对象: $parametersJson")
            // 校验 requiredJson 是否为合法 JSON 数组
            resultOf {
                AppJson.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.json.JsonElement.serializer()),
                    requiredJson,
                )
            }.getOrNull() ?: return Result.Err("requiredJson 不是合法的 JSON 数组: $requiredJson")
            validatedRequiredJson = requiredJson
            storedImplementation = implementationKotlin
        }

        // M-SI4: 安全审查(prompt injection / 社会工程 / 非法参数类型)
        safetyReview(id, name, description, validatedParams, promptText)?.let {
            Logger.w("SkillImporter", "skill '$id' 安全审查未通过: $it")
            return Result.Err("安全审查未通过: $it")
        }

        val now = System.currentTimeMillis()
        return Result.Ok(
            SkillEntity(
                id = id,
                name = name,
                description = description,
                parametersJson = validatedParams.toString(),
                requiredJson = validatedRequiredJson,
                implementationKotlin = storedImplementation,
                enabled = true,
                category = category,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /**
     * 供 `update_skill` 复用同一套导入校验:把更新后的字段拼装成等价的 .skill.json
     * 再走 [parse],保证更新路径与安装路径的约束(保留 id / category 白名单 /
     * 实现白名单 / 注入黑名单 / prompt 文本上限)完全一致。
     *
     * @return [Result.Ok] 含校验通过后的新实体(createdAt/enabled 由调用方覆盖)
     */
    fun validateForUpdate(
        id: String,
        name: String,
        description: String,
        parametersJson: String,
        requiredJson: String,
        implementationKotlin: String,
        category: String,
    ): Result {
        val json = buildJsonObject {
            put("id", id)
            put("name", name)
            put("description", description)
            put("parametersJson", parametersJson)
            put("requiredJson", requiredJson)
            put("implementationKotlin", implementationKotlin)
            put("category", category)
        }.toString()
        return parse(json)
    }

    /** 读取字符串字段;字段缺失或不是字符串时返回 null(不抛异常)。 */
    private fun stringField(raw: JsonObject, name: String): String? =
        (raw[name] as? JsonPrimitive)?.contentOrNull
}
