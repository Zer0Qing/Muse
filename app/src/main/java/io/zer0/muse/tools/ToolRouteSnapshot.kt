package io.zer0.muse.tools

import io.zer0.ai.core.ToolDefinition
import io.zer0.muse.data.skill.SkillEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/** Immutable routing decision captured for one model turn. Definitions and execution share this snapshot. */
data class ToolRouteSnapshot(
    val definitions: List<ToolDefinition>,
    val routes: Map<String, Route>,
) {
    sealed interface Route {
        data object Local : Route

        data class Skill(val skill: SkillEntity) : Route
    }

    fun routeFor(name: String): Route? = routes[name]

    fun isExposed(name: String): Boolean = name in routes
}

/** Builds a deterministic per-turn table. Local tools always win over same-named skills. */
object RouteTable {
    /**
     * v2.x: 提示词技能统一工具 schema — 追加可选 input 参数。
     * 无参 schema 会让模型无法向提示词技能传入具体请求("装了但用不上"的体验根源之一)。
     */
    private const val PROMPT_SKILL_INPUT_SCHEMA =
        "{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"," +
            "\"description\":\"传给该技能的输入或请求文本;会代入指令中的 {{input}}/{{args}} 占位符,无占位符时追加到指令末尾\"}},\"required\":[]}"

    fun snapshot(localDefinitions: List<ToolDefinition>, skills: Collection<SkillEntity>): ToolRouteSnapshot {
        val localByName = localDefinitions.distinctBy { it.name }.associateBy { it.name }
        val skillByName =
            skills
                .distinctBy { it.id }
                .filterNot { it.id in localByName }
                .associateBy { it.id }
        val definitions =
            localByName.values +
                skillByName.values.map { skill ->
                    ToolDefinition(
                        name = skill.id,
                        description = skill.description,
                        // v2.x: 提示词技能追加可选 input 参数(指令无自带参数时的输入口)
                        parametersJsonSchema =
                        if (SkillImporter.isPromptSkill(skill.implementationKotlin)) {
                            PROMPT_SKILL_INPUT_SCHEMA
                        } else {
                            normalizeObjectSchema(skill.parametersJson)
                        },
                    )
                }
        val routes =
            localByName.keys.associateWith { ToolRouteSnapshot.Route.Local } +
                skillByName.mapValues { (_, skill) -> ToolRouteSnapshot.Route.Skill(skill) }
        return ToolRouteSnapshot(definitions = definitions, routes = routes)
    }

    private fun normalizeObjectSchema(raw: String): String {
        if (raw.isBlank()) return """{"type":"object","properties":{}}"""
        return runCatching {
            val element = Json.parseToJsonElement(raw).jsonObject
            if (element.containsKey("type")) {
                raw
            } else {
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    element.forEach { (k, v) -> put(k, v) }
                }.toString()
            }
        }.getOrElse { """{"type":"object","properties":{}}""" }
    }
}
