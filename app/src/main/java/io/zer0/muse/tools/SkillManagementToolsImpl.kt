package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.skill.SkillRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * P1-3b 拆域：Skill 管理工具实现（install/list/uninstall/disable）。
 * 由 SkillExecutor 委托调用。
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

    suspend fun uninstallSkill(args: Map<String, String>): String {
        val repo = skillRepository ?: return context.getString(R.string.skill_uninstall_not_configured)
        val id = args["id"]?.takeIf { it.isNotBlank() }
        val name = args["name"]?.takeIf { it.isNotBlank() }
        if (id == null && name == null) return context.getString(R.string.skill_missing_param_id_or_name)
        val targetId = id ?: run {
            val matched = repo.observeAll.first().find { it.name == name }
            matched?.id ?: return context.getString(R.string.skill_skill_not_found_by_name, name)
        }
        val existing = repo.getById(targetId) ?: return context.getString(R.string.skill_skill_not_found, targetId)
        repo.delete(targetId)
        return context.getString(R.string.skill_uninstalled, existing.name, targetId)
    }

    suspend fun disableSkill(args: Map<String, String>): String {
        val repo = skillRepository ?: return context.getString(R.string.skill_disable_not_configured)
        val id = args["id"]?.takeIf { it.isNotBlank() } ?: return context.getString(R.string.skill_missing_param_id)
        val existing = repo.getById(id) ?: return context.getString(R.string.skill_skill_not_found, id)
        repo.setEnabled(id, enabled = false)
        return context.getString(R.string.skill_disabled_result, existing.name, id)
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
