package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.data.skill.SkillRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * P1-3b 拆域：Skill 管理工具实现（install/list/uninstall/disable/enable/update）。
 * 由 SkillExecutor 委托调用。
 *
 * 另负责提示词技能(prompt skill)的执行入口 [execPromptSkill]：
 * 该类型不跑 Kotlin 实现，只把 `implementationKotlin = "prompt:<文本>"` 中编码的
 * 用户指令原样交回模型（见 [SkillImporter.PROMPT_PREFIX]）。
 */
class SkillManagementToolsImpl(
    private val context: Context,
    private val skillRepository: SkillRepository?,
) {

    suspend fun installSkill(args: Map<String, String>): String {
        val repo = skillRepository ?: return context.getString(R.string.skill_install_not_configured)
        val skillJson = args["skill_json"] ?: return context.getString(R.string.skill_missing_param_skill_json)
        precheckSkillJson(skillJson)?.let { return it }
        when (val result = SkillImporter.parse(skillJson)) {
            is SkillImporter.Result.Ok -> {
                if (result.skill.id in SkillImporter.RESERVED_IDS) {
                    return context.getString(R.string.skill_install_conflict, result.skill.id)
                }
                resultOf { repo.upsert(result.skill) }
                    .onError { msg, _ -> return context.getString(R.string.skill_install_db_failed, msg) }
                return context.getString(R.string.skill_installed, result.skill.name, result.skill.id, result.skill.implementationKotlin)
            }
            is SkillImporter.Result.Err -> return context.getString(R.string.skill_install_validate_failed, result.reason)
        }
        return context.getString(R.string.skill_unknown_error)
    }

    suspend fun listSkills(args: Map<String, String>): String {
        val repo = skillRepository ?: return context.getString(R.string.skill_list_not_configured)
        val category = args["category"]?.takeIf { it.isNotBlank() }
        val all = repo.observeAll.first()
        val builtInIds = SkillImporter.RESERVED_IDS
        val builtInCount = all.count { it.id in builtInIds }
        val userCount = all.size - builtInCount
        if (all.isEmpty()) {
            return if (category != null) {
                context.getString(R.string.skill_no_category_skill, category)
            } else {
                context.getString(R.string.skill_no_skill_installed)
            }
        }
        val filtered = when (category?.lowercase()) {
            "user", "custom" -> all.filter { it.id !in builtInIds }
            "skill" -> all
            null -> all
            else -> all.filter { it.category == category }
        }
        if (filtered.isEmpty()) {
            return context.getString(R.string.skill_no_category_skill, category)
        }
        val header = "共 ${all.size} 个 skill(内置 $builtInCount 个,用户安装 $userCount 个)"
        val body = filtered.joinToString("\n") { s ->
            val source = if (s.id in builtInIds) "[内置]" else "[用户]"
            "${s.id} | ${s.name} | ${s.category} | ${if (s.enabled) "enabled" else "disabled"} $source"
        }
        return "$header\n$body"
    }

    /**
     * uninstall_skill — 卸载用户安装的 skill。
     *
     * 归属校验(与 [updateSkill]/[enableSkill] 对齐):
     *  - [SkillImporter.RESERVED_IDS] 内置保留 id 一律拒绝 —— 内置技能只能禁用,不能卸载;
     *  - `implementationKotlin` 以 `plugin:` 开头或 `category == "plugin"` 的插件技能拒绝 ——
     *    插件技能随插件卸载,单独删除会造成"插件启用但工具消失"。
     */
    suspend fun uninstallSkill(args: Map<String, String>): String {
        val repo = skillRepository ?: return context.getString(R.string.skill_uninstall_not_configured)
        val id = args["id"]?.takeIf { it.isNotBlank() }
        val name = args["name"]?.takeIf { it.isNotBlank() }
        if (id == null && name == null) return context.getString(R.string.skill_missing_param_id_or_name)
        val targetId = id ?: run {
            val matched = repo.observeAll.first().find { it.name == name }
            matched?.id ?: return context.getString(R.string.skill_skill_not_found_by_name, name)
        }
        if (targetId in SkillImporter.RESERVED_IDS) {
            return context.getString(R.string.skill_uninstall_reserved_rejected, targetId)
        }
        val existing = repo.getById(targetId) ?: return context.getString(R.string.skill_skill_not_found, targetId)
        if (existing.implementationKotlin.startsWith(PLUGIN_IMPLEMENTATION_PREFIX) || existing.category == "plugin") {
            return context.getString(R.string.skill_uninstall_plugin_rejected, existing.name, targetId)
        }
        repo.delete(targetId)
        return context.getString(R.string.skill_uninstalled, existing.name, targetId)
    }

    /**
     * disable_skill — 禁用用户安装的 skill。
     *
     * 归属校验:
     *  - 内置保留技能允许禁用(它们本就是内置在用,用户可手动关闭,见 [enableSkill]);
     *  - 插件拥有的技能拒绝 —— 插件技能随插件启停,单独禁用会绕过插件管理页的生命周期校验。
     */
    suspend fun disableSkill(args: Map<String, String>): String {
        val repo = skillRepository ?: return context.getString(R.string.skill_disable_not_configured)
        val id = args["id"]?.takeIf { it.isNotBlank() } ?: return context.getString(R.string.skill_missing_param_id)
        val existing = repo.getById(id) ?: return context.getString(R.string.skill_skill_not_found, id)
        if (existing.implementationKotlin.startsWith(PLUGIN_IMPLEMENTATION_PREFIX) || existing.category == "plugin") {
            return context.getString(R.string.skill_disable_plugin_rejected, existing.name, id)
        }
        repo.setEnabled(id, enabled = false)
        return context.getString(R.string.skill_disabled_result, existing.name, id)
    }

    /**
     * enable_skill — 启用指定 skill(与 [disableSkill] 对称)。
     *
     * 归属约束:
     *  - 内置保留技能允许启用(它们本就是内置在用,只存在被用户手动关闭的情况);
     *  - 插件拥有的技能(implementationKotlin 以 `plugin:` 开头或 category == "plugin")拒绝 ——
     *    插件技能随插件启停,单独启用会绕过插件管理页的生命周期校验;
     *  - 其它未知 id 直接返回未找到。
     */
    suspend fun enableSkill(args: Map<String, String>): String {
        val repo = skillRepository ?: return context.getString(R.string.skill_enable_not_configured)
        val id = args["id"]?.takeIf { it.isNotBlank() } ?: return context.getString(R.string.skill_missing_param_id)
        val existing = repo.getById(id) ?: return context.getString(R.string.skill_skill_not_found, id)
        if (existing.implementationKotlin.startsWith(PLUGIN_IMPLEMENTATION_PREFIX) || existing.category == "plugin") {
            return context.getString(R.string.skill_enable_plugin_rejected, existing.name, id)
        }
        repo.setEnabled(id, enabled = true)
        return context.getString(R.string.skill_enabled_result, existing.name, id)
    }

    /**
     * update_skill — 迭代助手自己创建的用户 skill。
     *
     * 允许更新 name / description / parametersJson;提示词技能额外允许用 `prompt`
     * 参数替换指令文本。
     *
     * 归属约束(不属于用户的一律拒绝,理由用中文返回给模型):
     *  - [SkillImporter.RESERVED_IDS] 内置保留 id;
     *  - `implementationKotlin` 以 `plugin:` 开头或 `category == "plugin"` 的插件技能。
     *
     * 安全约束:更新内容不直接写库,先经 [SkillImporter.validateForUpdate] 走与安装
     * 完全相同的校验(保留 id / category 白名单 / 实现白名单 / 注入黑名单 / prompt 上限)。
     * 更新成功后 id 不变、强制保持启用。
     */
    suspend fun updateSkill(args: Map<String, String>): String {
        val repo = skillRepository ?: return context.getString(R.string.skill_update_not_configured)
        val id = args["id"]?.takeIf { it.isNotBlank() } ?: return context.getString(R.string.skill_missing_param_id)
        val existing = repo.getById(id) ?: return context.getString(R.string.skill_skill_not_found, id)
        if (id in SkillImporter.RESERVED_IDS) {
            return context.getString(R.string.skill_update_reserved_rejected, id)
        }
        if (existing.implementationKotlin.startsWith(PLUGIN_IMPLEMENTATION_PREFIX) || existing.category == "plugin") {
            return context.getString(R.string.skill_update_plugin_rejected, existing.name, id)
        }

        val isPrompt = SkillImporter.isPromptSkill(existing.implementationKotlin)
        val promptArg = args["prompt"]
        if (promptArg != null && !isPrompt) {
            return context.getString(R.string.skill_update_prompt_not_supported, existing.name, id)
        }
        val newPrompt = promptArg?.trim()
        if (promptArg != null && newPrompt.isNullOrEmpty()) {
            return context.getString(R.string.skill_prompt_blank)
        }
        val newName = args["name"]?.trim()?.takeIf { it.isNotBlank() }
        val newDescription = args["description"]?.trim()?.takeIf { it.isNotBlank() }
        val newParameters = args["parametersJson"]?.takeIf { it.isNotBlank() }
        if (newName == null && newDescription == null && newParameters == null && newPrompt == null) {
            return context.getString(R.string.skill_update_no_fields)
        }

        // 提示词技能无参数:parametersJson / requiredJson 固定为空,忽略调用方传入的 parametersJson
        val validated = SkillImporter.validateForUpdate(
            id = existing.id,
            name = newName ?: existing.name,
            description = newDescription ?: existing.description,
            parametersJson = if (isPrompt) SkillImporter.EMPTY_PARAMETERS_JSON else newParameters ?: existing.parametersJson,
            requiredJson = if (isPrompt) "[]" else existing.requiredJson,
            implementationKotlin = newPrompt?.let { SkillImporter.encodePromptText(it) } ?: existing.implementationKotlin,
            category = existing.category,
        )
        val parsed = when (validated) {
            is SkillImporter.Result.Ok -> validated.skill
            is SkillImporter.Result.Err -> return context.getString(R.string.skill_update_validate_failed, validated.reason)
        }
        val updated = parsed.copy(
            createdAt = existing.createdAt,
            updatedAt = System.currentTimeMillis(),
            enabled = true,
        )
        resultOf { repo.update(updated) }
            .onError { msg, _ -> return context.getString(R.string.skill_update_db_failed, msg) }
        return context.getString(R.string.skill_updated, updated.name, updated.id)
    }

    /**
     * 执行提示词技能:把指令文本交回模型,不执行任何 Kotlin 实现。
     *
     * 文本带 `[技能指令]` 标注,明确区分于系统指令;调用方传入的参数一律忽略
     * (提示词技能不接收参数,见 [SkillImporter.PROMPT_PREFIX])。
     */
    fun execPromptSkill(skill: SkillEntity): String {
        val text = SkillImporter.decodePromptText(skill.implementationKotlin)?.trim()
        if (text.isNullOrEmpty()) return context.getString(R.string.skill_prompt_empty)
        return context.getString(R.string.skill_prompt_instruction_result, skill.name, skill.id, text)
    }

    private companion object {
        /** 插件技能的 implementationKotlin 前缀(与 PluginManager 的 "plugin:<pluginId>:<fn>" 约定一致)。 */
        const val PLUGIN_IMPLEMENTATION_PREFIX = "plugin:"
    }

    private fun precheckSkillJson(jsonText: String): String? {
        val requiredFields = listOf("name", "description", "category", "implementationKotlin", "parametersJson")
        var jsonErrorMsg: String? = null
        val raw: JsonObject? = resultOf {
            AppJson.decodeFromString(JsonObject.serializer(), jsonText)
        }.onError { msg, throwable ->
            val errorKind = if (throwable is SerializationException) "格式错误" else "非预期异常"
            jsonErrorMsg = msg
            Logger.w("SkillManagement", "install_skill JSON 解析失败($errorKind, ${throwable?.javaClass?.simpleName}): $msg")
        }.getOrNull()
        if (raw == null) {
            return "JSON 格式错误: ${jsonErrorMsg ?: "无法解析"}。请检查字段引号、冒号、括号是否匹配。"
        }
        for (field in requiredFields) {
            if (field !in raw) {
                return "缺少必需字段: $field。必需字段: name, description, category, implementationKotlin, parametersJson"
            }
        }
        for (field in requiredFields) {
            val element = raw[field]
            if (element !is JsonPrimitive || !element.isString) {
                val actualType = when (element) {
                    is JsonPrimitive -> if (element.isString) "字符串" else "数字/布尔"
                    is JsonObject -> "对象"
                    is JsonArray -> "数组"
                    is JsonNull -> "null"
                    else -> "未知"
                }
                return "字段 $field 类型错误: 期望字符串,实际为 $actualType"
            }
        }
        return null
    }
}
