package io.zer0.muse.tools

import io.zer0.ai.core.ToolDefinition

/**
 * Lightweight boundary for resumed/background tool calls.
 * A turn may outlive dynamic MCP/skill registration, so callers must validate
 * the current registry before invoking ToolRegistry directly.
 */
class ToolRouteExecutionGuard(private val registry: ToolRegistry) {
    fun validateLocal(name: String, snapshot: ToolRouteSnapshot? = null): String? {
        val current = registry.listTools().firstOrNull { it.name == name }
            ?: return "工具不存在或已注销: $name"
        if (snapshot != null) {
            when (val route = snapshot.routeFor(name)) {
                null -> return "工具未在当前路由中暴露: $name"
                is ToolRouteSnapshot.Route.Skill -> return "工具路由已变更为 skill: $name"
                ToolRouteSnapshot.Route.Local -> {
                    val expected = snapshot.definitions.firstOrNull { it.name == name }
                    if (expected != null && !definitionMatches(expected, current)) {
                        return "工具定义已变更，拒绝执行: $name"
                    }
                }
            }
        }
        return null
    }

    suspend fun executeFromJson(
        name: String,
        argumentsJson: String,
        snapshot: ToolRouteSnapshot? = null,
        executionContext: ToolExecutionContext? = null,
    ): String {
        validateLocal(name, snapshot)?.let { return "Error: $it" }
        return if (executionContext == null) {
            registry.executeFromJson(name, argumentsJson)
        } else {
            registry.executeFromJson(name, argumentsJson, executionContext)
        }
    }

    private fun definitionMatches(expected: ToolDefinition, current: ToolRegistry.ToolDef): Boolean =
        expected.name == current.name &&
            expected.description == current.description &&
            expected.parametersJsonSchema == current.rawParametersJsonSchema
}
