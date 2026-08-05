package io.zer0.muse.tools

import android.content.Context
import io.zer0.muse.tools.resource.ResourceLibraryStore

/**
 * P1-3b 拆域：资源库工具注册器。
 *
 * 注册 resource_add / resource_list / resource_search / resource_get / resource_delete。
 * 实现位于 [ResourceToolsImpl.kt]。
 */
class ResourceToolsRegistrar(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
    resourceLibrary: ResourceLibraryStore = ResourceLibraryStore(context),
) {
    private val impl = ResourceToolsImpl(context, resourceLibrary)

    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "resource_add",
                description = "向资源库添加一条资源(笔记/提示词/参考内容等)。",
                parameters = mapOf(
                    "title" to "必填,资源标题",
                    "content" to "必填,资源正文",
                    "tags" to "可选,标签,多个用逗号分隔",
                ),
                required = setOf("title", "content"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.add(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "resource_list",
                description = "列出资源库中的资源,可按关键字过滤。",
                parameters = mapOf(
                    "keyword" to "可选,标题/正文/标签过滤关键字",
                    "limit" to "可选,最多返回数量,默认 20",
                ),
                required = emptySet(),
                category = "built-in",
                parameterTypes = mapOf("limit" to "integer"),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.list(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "resource_search",
                description = "搜索资源库(与 resource_list 关键字过滤行为一致)。",
                parameters = mapOf(
                    "keyword" to "可选,搜索关键字",
                    "limit" to "可选,最多返回数量,默认 20",
                ),
                required = emptySet(),
                category = "built-in",
                parameterTypes = mapOf("limit" to "integer"),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.list(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "resource_get",
                description = "根据 id 获取资源库中的单条资源。",
                parameters = mapOf("id" to "必填,资源 id"),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.get(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "resource_delete",
                description = "根据 id 删除资源库中的资源。",
                parameters = mapOf("id" to "必填,资源 id"),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.delete(args) }
    }
}
