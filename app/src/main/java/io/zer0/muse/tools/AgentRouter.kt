package io.zer0.muse.tools

import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v1.200: Agent 自动路由。
 *
 * 当用户请求需要多 Agent 协作但未显式指定子助手时,
 * AgentRouter 根据任务文本 + 可用助手能力标签 + 团队配置,
 * 推荐最合适的助手或团队。
 *
 * 默认走规则路由:按关键词命中能力标签,匹配度最高的助手胜出。
 * v2.x: 新增 [routeWithLlm] —— 可选的 LLM 语义路由,在 [chatService] 非空时可用,
 * 由 [MultiAgentConfig.llmRoutingEnabled] 开关控制。LLM 路由失败时自动降级到规则路由。
 */
class AgentRouter(
    private val assistantRepository: AssistantRepository,
    private val settingsRepository: SettingsRepository,
    /** v2.x: 可选的 ChatService,非空时支持 [routeWithLlm] LLM 语义路由。 */
    private val chatService: ChatService? = null,
) {

    /**
     * 路由结果。
     *
     * @param targetType 目标类型("assistant" | "team" | null)
     * @param targetId 目标 assistantId 或 teamId
     * @param confidence 0..1 匹配置信度
     * @param reason 路由原因(供 LLM/用户理解)
     * @param source 路由来源:"rule"(规则路由) | "llm"(LLM 语义路由)
     */
    data class RouteResult(
        val targetType: String?,
        val targetId: String?,
        val targetName: String?,
        val confidence: Float,
        val reason: String,
        val source: String = "rule",
    ) {
        val isAvailable: Boolean get() = targetType != null && targetId != null
    }

    /**
     * 根据任务文本自动选择最佳 Agent/团队。
     *
     * @param task 用户任务描述
     * @param excludeAssistantId 排除的助手 id(通常是当前主助手,避免自委托)
     */
    suspend fun route(
        task: String,
        excludeAssistantId: String? = null,
    ): RouteResult {
        val assistants = assistantRepository.getAll()
            .filter { it.id != excludeAssistantId }
        val config = settingsRepository.multiAgentConfigCache

        if (assistants.isEmpty() && config.teams.isEmpty()) {
            return RouteResult(
                targetType = null,
                targetId = null,
                targetName = null,
                confidence = 0f,
                reason = "当前没有其他助手或团队可委托",
            )
        }

        val normalizedTask = task.lowercase()
        val taskCapabilities = extractCapabilities(normalizedTask)

        // 1. 先匹配团队:团队名/描述命中或团队整体能力覆盖任务
        val teamScores = config.teams.map { team ->
            val members = assistants.filter { it.id in team.memberIds }
            val teamCaps = members.flatMap { AgentCapability.fromEntity(it) }.distinct()
            val nameScore = if (team.name.isNotBlank() && normalizedTask.contains(team.name.lowercase())) 0.6f else 0f
            val descScore = if (team.description.isNotBlank() &&
                team.description.lowercase().splitAny(taskCapabilities).isNotEmpty()
            ) 0.3f else 0f
            val capScore = computeCoverageScore(teamCaps, taskCapabilities)
            team to (nameScore + descScore + capScore)
        }
        val bestTeam = teamScores.maxByOrNull { it.second }

        // 2. 再匹配单个助手
        val assistantScores = assistants.map { assistant ->
            val caps = AgentCapability.fromEntity(assistant)
            val nameScore = if (assistant.name.isNotBlank() &&
                normalizedTask.contains(assistant.name.lowercase())
            ) 0.8f else 0f
            val capScore = computeCoverageScore(caps, taskCapabilities)
            val systemPromptScore = if (assistant.systemPrompt.isNotBlank() &&
                assistant.systemPrompt.lowercase().splitAny(taskCapabilities).isNotEmpty()
            ) 0.2f else 0f
            assistant to (nameScore + capScore + systemPromptScore)
        }
        val bestAssistant = assistantScores.maxByOrNull { it.second }

        // 3. 取最高分的团队或助手,需超过阈值才推荐
        val teamResult = bestTeam?.let { (team, score) ->
            if (score >= CONFIDENCE_THRESHOLD) {
                RouteResult(
                    targetType = "team",
                    targetId = team.id,
                    targetName = team.name,
                    confidence = score.coerceIn(0f, 1f),
                    reason = "团队「${team.name}」的能力覆盖该任务",
                )
            } else null
        }

        val assistantResult = bestAssistant?.let { (assistant, score) ->
            if (score >= CONFIDENCE_THRESHOLD) {
                RouteResult(
                    targetType = "assistant",
                    targetId = assistant.id,
                    targetName = assistant.name,
                    confidence = score.coerceIn(0f, 1f),
                    reason = "助手「${assistant.name}」的能力标签匹配该任务",
                )
            } else null
        }

        return when {
            teamResult != null && assistantResult != null ->
                if (teamResult.confidence >= assistantResult.confidence) teamResult else assistantResult
            teamResult != null -> teamResult
            assistantResult != null -> assistantResult
            else -> {
                // 未命中阈值:记录日志,便于排查为何回退默认 Agent
                val teamScore = bestTeam?.second ?: 0f
                val assistantScore = bestAssistant?.second ?: 0f
                val bestScore = maxOf(teamScore, assistantScore)
                Logger.d("AgentRouter", "路由置信度 $bestScore 低于阈值 $CONFIDENCE_THRESHOLD,回退默认 Agent")
                // 未命中阈值,返回最高分候补供上层决策
                val fallback = assistantResult ?: assistantScores.maxByOrNull { it.second }
                    ?.let { (assistant, score) ->
                        RouteResult(
                            targetType = "assistant",
                            targetId = assistant.id,
                            targetName = assistant.name,
                            confidence = score.coerceIn(0f, 1f),
                            reason = "未强匹配,候选助手「${assistant.name}」",
                        )
                    }
                fallback ?: RouteResult(
                    targetType = null,
                    targetId = null,
                    targetName = null,
                    confidence = 0f,
                    reason = "未找到合适的助手或团队",
                )
            }
        }
    }

    /**
     * v2.x: LLM 语义路由(可选)。
     *
     * 当 [chatService] 非空时,把任务文本 + 可用助手/团队清单交给 LLM 判断,
     * LLM 只回复最合适的目标 ID。比规则路由更准,但多一次 LLM 调用开销。
     *
     * 降级策略(任何环节失败均回退 [route]):
     *  - [chatService] 为 null:直接走规则路由
     *  - LLM 调用异常:走规则路由
     *  - LLM 回复的 ID 无法匹配任何助手/团队:走规则路由
     *
     * @param task 用户任务描述
     * @param excludeAssistantId 排除的助手 id(通常是当前主助手,避免自委托)
     * @return 路由结果,[RouteResult.source] = "llm" 表示 LLM 路由命中,"rule" 表示降级回规则路由
     */
    suspend fun routeWithLlm(
        task: String,
        excludeAssistantId: String? = null,
    ): RouteResult {
        // chatService 未注入:无法走 LLM 路由,直接降级
        if (chatService == null) return route(task, excludeAssistantId)

        val assistants = assistantRepository.getAll()
            .filter { it.id != excludeAssistantId }
        val config = settingsRepository.multiAgentConfigCache

        // 没有候选目标时直接走规则路由(route 内部会返回"无可委托目标")
        if (assistants.isEmpty() && config.teams.isEmpty()) {
            return route(task, excludeAssistantId)
        }

        // 构造助手/团队花名册,让 LLM 知道每个候选的 id 和能力描述
        val roster = buildString {
            append("可用助手:\n")
            assistants.forEach { a ->
                // 优先用 summary,为空时降级到 systemPrompt 前 80 字
                val desc = a.summary.ifBlank { a.systemPrompt.take(80) }
                append("- ${a.id} | ${a.name}${if (desc.isBlank()) "" else ": $desc"}\n")
            }
            if (config.teams.isNotEmpty()) {
                append("\n可用团队:\n")
                config.teams.forEach { t ->
                    append("- ${t.id} | ${t.name}${if (t.description.isBlank()) "" else ": ${t.description}"}\n")
                }
            }
        }

        val prompt = """
你是一个轻量路由器。只有当候选助手/团队明显比当前助手更合适时才选择它。
只输出一个候选 ID;如果没有明显匹配,输出 NONE。不要解释。

用户任务:
$task

$roster

候选 ID:
""".trimIndent()

        return try {
            val completion = withTimeoutOrNull(4_000L) {
                chatService.completeText(
                    messages = listOf(
                        UIMessage(role = MessageRole.SYSTEM, content = "只做轻量分类,只输出候选 ID 或 NONE,不要思考长文。"),
                        UIMessage(role = MessageRole.USER, content = prompt),
                    ),
                    model = null,  // 用默认模型
                    temperature = 0f,
                    maxTokens = 16,
                    reasoningLevel = io.zer0.ai.core.ReasoningLevel.OFF,
                    mode = ChatRequestMode.UTILITY,
                )
            } ?: return route(task, excludeAssistantId)
            val id = completion.text
                .trim()
                .lineSequence()
                .firstOrNull()
                ?.trim()
                ?.removePrefix("`")
                ?.removeSuffix("`")
                .orEmpty()
            if (id.equals("NONE", ignoreCase = true)) return route(task, excludeAssistantId)
            // 匹配助手
            val matchedAssistant = assistants.find { it.id == id }
            if (matchedAssistant != null) {
                RouteResult(
                    targetType = "assistant",
                    targetId = matchedAssistant.id,
                    targetName = matchedAssistant.name,
                    confidence = 0.8f,
                    reason = "LLM 路由命中助手「${matchedAssistant.name}」",
                    source = "llm",
                )
            } else {
                // 匹配团队
                val matchedTeam = config.teams.find { it.id == id }
                if (matchedTeam != null) {
                    RouteResult(
                        targetType = "team",
                        targetId = matchedTeam.id,
                        targetName = matchedTeam.name,
                        confidence = 0.8f,
                        reason = "LLM 路由命中团队「${matchedTeam.name}」",
                        source = "llm",
                    )
                } else {
                    // LLM 回复的 ID 无法匹配,降级规则路由
                    Logger.d("AgentRouter", "LLM 路由回复『$id』未匹配任何助手/团队,降级规则路由")
                    route(task, excludeAssistantId)
                }
            }
        } catch (e: Exception) {
            Logger.w("AgentRouter", "LLM 路由异常,降级规则路由", e)
            route(task, excludeAssistantId)
        }
    }

    /**
     * 提取任务文本中隐含的能力需求。
     */
    private fun extractCapabilities(task: String): List<String> {
        return AgentCapability.ALL_CAPABILITIES.filter { capability ->
            KEYWORD_MAP[capability]?.any { task.contains(it) } == true
        }
    }

    /**
     * 计算能力覆盖分数:
     * - 每命中一个任务所需能力 +0.25
     * - 助手额外能力不扣分,但超过 3 个额外能力 penalize 0.05 避免过泛
     */
    private fun computeCoverageScore(
        agentCaps: List<String>,
        taskCaps: List<String>,
    ): Float {
        if (taskCaps.isEmpty()) return 0.1f
        val matched = taskCaps.count { it in agentCaps }
        val coverage = matched.toFloat() / taskCaps.size
        val extra = (agentCaps - taskCaps.toSet()).size
        val penalty = (extra.coerceAtMost(5) * 0.03f)
        return (coverage * 0.7f + matched * 0.25f - penalty).coerceIn(0f, 1f)
    }

    /**
     * 把字符串按给定 token 集合拆分,返回命中的 token。
     */
    private fun String.splitAny(tokens: List<String>): List<String> {
        return tokens.filter { this.contains(it) }
    }

    companion object {
        /** 推荐阈值,低于此值视为未命中,返回候补。 */
        private const val CONFIDENCE_THRESHOLD = 0.35f

        /** 能力 → 关键词映射。 */
        private val KEYWORD_MAP = mapOf(
            AgentCapability.CODE to listOf("代码", "编程", "program", "code", "debug", "bug", "kotlin", "java", "python", "js", "javascript", "ts", "typescript", "compose", "android"),
            AgentCapability.MATH to listOf("数学", "计算", "math", "calculate", "公式", "statistics", "概率", "代数", "微积分"),
            AgentCapability.RESEARCH to listOf("调研", "研究", "research", "搜索", "search", "调查", "分析", "竞品", "市场"),
            AgentCapability.WRITING to listOf("写作", "写文章", "write", "文案", "essay", "draft", "润色", "改写", "邮件", "report", "故事"),
            AgentCapability.CREATIVE to listOf("创意", "brainstorm", "idea", "创意", "灵感", "设计", "plot", "角色"),
            AgentCapability.TRANSLATION to listOf("翻译", "translate", "translation", "英文", "中文", "日文", "语言"),
            AgentCapability.REVIEW to listOf("审阅", "review", "检查", "校对", " critique", "评估", "评分"),
            AgentCapability.DATA to listOf("数据", "data", "csv", "json", "表格", "统计", "可视化", "chart", "分析"),
            AgentCapability.LEGAL to listOf("法律", "legal", "合同", "law", "条款", "合规"),
            AgentCapability.MEDICAL to listOf("医疗", "医学", "medical", "健康", "症状", "药", "诊断"),
            AgentCapability.FINANCE to listOf("金融", "财务", "finance", "投资", "股票", "基金", "理财", "预算"),
            AgentCapability.EDUCATION to listOf("教育", "学习", "teach", "tutorial", "解释", "课程", "知识点"),
            AgentCapability.IMAGE to listOf("图片", "图像", "image", "画图", "生成图", "插画", "photo", "dall"),
            AgentCapability.VIDEO to listOf("视频", "video", "生成视频", "剪辑", "动画"),
            AgentCapability.WEB_SEARCH to listOf("联网", "搜索", "最新", "新闻", "web", "search", "资讯"),
            AgentCapability.MEMORY to listOf("记忆", "整理", "memory", "归档", "回顾", "总结"),
            AgentCapability.SCHEDULE to listOf("日程", "定时", "schedule", "提醒", "任务", "todo", "计划", "闹钟"),
            AgentCapability.KNOWLEDGE to listOf("知识库", "文档", "knowledge", "pdf", "笔记", "资料"),
            AgentCapability.CHAT to listOf("聊天", "闲聊", "chat", "对话", "谈心"),
            AgentCapability.REASONING to listOf("推理", "逻辑", "reasoning", "逻辑题", "证明", "推导"),
        )
    }
}
