package io.zer0.muse.tools

import android.content.Context

/**
 * P1-3b 拆域：快速记录工具注册器。
 *
 * 注册 quick_note_add / list / search / get / update / delete / pin。
 * 实现位于 [QuickNoteToolsImpl.kt]。
 */
class QuickNoteToolsRegistrar(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
) {
    private val impl = QuickNoteToolsImpl(context)

    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "quick_note_add",
                description = "添加一条快速记录(轻量笔记)。模型可在对话中帮用户记下待办/灵感/参考内容。",
                parameters = mapOf(
                    "title" to "必填,记录标题",
                    "content" to "可选,记录正文",
                    "tags" to "可选,标签,多个用逗号分隔",
                ),
                required = setOf("title"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.add(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "quick_note_list",
                description = "列出快速记录,支持按关键字/标签过滤。",
                parameters = mapOf(
                    "keyword" to "可选,标题/正文/标签过滤关键字",
                    "tag" to "可选,按标签精确过滤",
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
                name = "quick_note_search",
                description = "搜索快速记录(与 quick_note_list 关键字过滤一致)。",
                parameters = mapOf(
                    "keyword" to "可选,搜索关键字",
                    "limit" to "可选,最多返回数量,默认 20",
                ),
                required = emptySet(),
                category = "built-in",
                parameterTypes = mapOf("limit" to "integer"),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.search(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "quick_note_get",
                description = "根据 id 获取单条快速记录。",
                parameters = mapOf("id" to "必填,记录 id"),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.get(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "quick_note_update",
                description = "更新指定快速记录。",
                parameters = mapOf(
                    "id" to "必填,记录 id",
                    "title" to "可选,新标题",
                    "content" to "可选,新正文",
                    "tags" to "可选,新标签,多个用逗号分隔",
                ),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.update(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "quick_note_delete",
                description = "删除指定快速记录(移入回收站,可在回收站恢复)。",
                parameters = mapOf("id" to "必填,记录 id"),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.delete(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "quick_note_pin",
                description = "置顶/取消置顶某条快速记录。",
                parameters = mapOf(
                    "id" to "必填,记录 id",
                    "pinned" to "必填,true/false",
                ),
                required = setOf("id", "pinned"),
                category = "built-in",
                parameterTypes = mapOf("pinned" to "boolean"),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.pin(args) }
    }
}
