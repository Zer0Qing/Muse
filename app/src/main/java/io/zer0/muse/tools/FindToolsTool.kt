package io.zer0.muse.tools

/**
 * v2.x 工具瘦身阶段3:find_tools 元工具 — 工具库检索 + 能力总览。
 *
 * 默认分层收窄后,模型每轮只看到 CORE/STANDARD + 命中族 + 会话粘性工具;
 * 需要其他能力时,用本工具按关键词检索**全部已注册工具**(名称+描述),
 * 命中结果写入 [SessionToolLoadRegistry],后续轮次即可直接调用。
 *
 * v2.2.1: 新增"能力总览"模式 —— 空关键词返回全部工具的分组清单(不装载),
 * 让模型能事无巨细地回答"你/这个平台能做什么"。
 *
 * 只读检索:不执行任何工具、不改权限;装载只是让工具"可见",审批照旧。
 */
object FindToolsTool {
    /** 工具名常量(供白名单特例等引用)。 */
    const val TOOL_NAME = "find_tools"

    private const val MAX_RESULTS = 8
    private const val DESCRIPTION_SNIPPET = 140
    private const val LIST_DESCRIPTION_SNIPPET = 80

    fun toolDef() = ToolRegistry.ToolDef(
        name = TOOL_NAME,
        description =
        "Search the full tool library, or get a complete capability list. " +
            "With keywords: matching tools become available from your next call in this session. " +
            "With an empty query: returns the full tool list with a channel-status summary. " +
            "Entries marked * need extra system authorization (Shizuku/Root, Accessibility or Termux); " +
            "when a required channel is not ready, calling the tool fails fast with a clear error. " +
            "Use this when you need a tool that is not in your current list, or when asked what you can do.",
        parameters =
        mapOf(
            "query" to "Optional. Keywords describing the capability you need. " +
                "Leave empty to get the full tool list (capability overview).",
        ),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    /**
     * 纯函数检索:按名称/描述关键词匹配,名称命中权重更高;名称命中优先排序。
     *
     * 关键词按空白/中英文逗号/分号拆分,长度 ≥2 的词参与匹配;整串均为单字符时用整串兜底。
     */
    fun search(query: String, tools: List<ToolRegistry.ToolDef>, excludeName: String = TOOL_NAME): List<ToolRegistry.ToolDef> {
        val raw = query.trim().lowercase()
        if (raw.isEmpty()) return emptyList()
        val terms =
            raw.split(Regex("[\\s,，、;；]+"))
                .filter { it.length >= 2 }
                .ifEmpty { listOf(raw) }
        return tools
            .asSequence()
            .filter { it.name != excludeName }
            .map { def ->
                val name = def.name.lowercase()
                val desc = def.description.lowercase()
                var score = 0
                terms.forEach { term ->
                    if (name.contains(term)) score += 3
                    if (desc.contains(term)) score += 1
                }
                def to score
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<ToolRegistry.ToolDef, Int>> { it.second }.thenBy { it.first.name })
            .map { it.first }
            .take(MAX_RESULTS)
            .toList()
    }

    /**
     * v2.2.1: 纯函数 —— 渲染全部工具的分组清单(能力总览)。
     *
     * 清单只是"目录",不装载任何工具;模型看到目标后用关键词再查一次才装载,
     * 避免一次总览把所有工具面重新撑回全量。
     *
     * v2.x: 需额外授权的工具名带 * 标记;传入 [status] 时附运行通道状态行。
     */
    fun fullList(tools: List<ToolRegistry.ToolDef>, excludeName: String = TOOL_NAME, status: ToolPermissionStatus? = null): String {
        val visible = tools.filter { it.name != excludeName }
        if (visible.isEmpty()) return "No tools registered."
        val grouped = visible.groupBy { ToolCategories.categoryOf(it.name) }
        val sb = StringBuilder()
        sb.appendLine("Full tool capability list (${visible.size} tools total):")
        if (visible.any { ToolCategories.permissionOf(it.name).isNotEmpty() }) {
            sb.appendLine(
                "* = needs extra system authorization (Shizuku/Root, Accessibility or Termux). " +
                    "Unready channels fail fast with a clear error instead of a misleading success.",
            )
        }
        val sections =
            listOf(
                ToolCategory.CORE to "core 核心",
                ToolCategory.STANDARD to "standard 常用",
                ToolCategory.OPTIONAL to "optional 可选",
                ToolCategory.GLOBAL to "global 高级(需授权/审批)",
                null to "extension 扩展(插件/MCP/技能)",
            )
        sections.forEach { (category, label) ->
            val items = grouped[category].orEmpty().sortedBy { it.name }
            if (items.isEmpty()) return@forEach
            sb.appendLine()
            sb.appendLine("【$label】${items.size}")
            items.forEach { def ->
                val snippet = def.description.replace('\n', ' ').take(LIST_DESCRIPTION_SNIPPET)
                sb.appendLine("- ${markName(def.name)}: $snippet")
            }
        }
        if (status != null) {
            sb.appendLine()
            sb.appendLine(
                "Channel status now — Accessibility=${readyLabel(status.accessibility)}, " +
                    "Shizuku/Root=${readyLabel(status.shellTier)}, Termux=${readyLabel(status.termux)}",
            )
        }
        sb.appendLine()
        sb.appendLine(
            "以上为完整目录。要使用其中某个工具:用它的名称或关键词再次调用 find_tools," +
                "命中的工具从下一轮起可直接调用。应用层面的功能总览可用 knowledge_search(include_internal=true) 查询。",
        )
        return sb.toString().trimEnd()
    }

    /** 需额外授权的工具名加 * 标记(与 fullList 图例对应)。 */
    private fun markName(name: String): String = if (ToolCategories.permissionOf(name).isEmpty()) name else "$name*"

    private fun readyLabel(ready: Boolean): String = if (ready) "READY" else "NOT READY"

    /** 检索结果里的权限注记(含当前就绪状态,便于提前判断)。 */
    private fun permissionHint(name: String, status: ToolPermissionStatus?): String {
        val required = ToolCategories.permissionOf(name)
        if (required.isEmpty()) return ""
        val needs = required.joinToString(" or ") { it.enLabel() }
        return when (status?.satisfiedAny(required)) {
            true -> " [needs $needs; ready]"
            false -> " [needs $needs; NOT READY]"
            null -> " [needs $needs]"
        }
    }

    /**
     * 执行:空 query = 能力总览(只读,不装载);有关键词 = 检索并装载(命中后下一轮起可用)。
     */
    suspend fun execute(args: Map<String, String>, toolRegistry: ToolRegistry, executionContext: ToolExecutionContext): String {
        val query = args["query"]?.trim().orEmpty()
        // 可检索目录 = 本地注册工具 + 当前会话技能(技能默认被收窄,必须可检索才能找回)。
        val allTools = toolRegistry.listTools() + toolRegistry.searchableSkillProvider?.invoke().orEmpty()
        val status = toolRegistry.permissionStatusProvider?.invoke()
        if (query.isEmpty()) {
            return fullList(allTools, status = status)
        }
        val matches = search(query, allTools)
        if (matches.isEmpty()) {
            return "No tools matched \"$query\". Try different or broader keywords."
        }
        executionContext.sessionId?.let { sessionId ->
            SessionToolLoadRegistry.markLoaded(sessionId, matches.map { it.name })
        }
        return buildString {
            appendLine("Matched ${matches.size} tool(s), now available from your next call in this session:")
            matches.forEach { def ->
                val snippet = def.description.replace('\n', ' ').take(DESCRIPTION_SNIPPET)
                appendLine("- ${def.name}${permissionHint(def.name, status)}: $snippet")
            }
        }.trimEnd()
    }
}
