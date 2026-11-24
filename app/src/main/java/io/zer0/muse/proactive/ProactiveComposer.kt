package io.zer0.muse.proactive

import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.proactive.PendingThoughtEntity
import io.zer0.muse.data.proactive.ProactiveIntensity
import io.zer0.muse.data.proactive.ThoughtKind
import io.zer0.muse.data.proactive.ThoughtUrgency
import io.zer0.muse.transformer.stripThinkTags

/**
 * 主动消息的内容生成。
 *
 * 一次调用，且允许放弃。
 *
 * 素材是**最近 20 轮原始对话**，不是「攒下来的牵挂清单」。这个区别是关键：
 * 从清单里挑，挑来挑去总是权重最高的那条，于是同一件事会被反复提起；
 * 从对话里接，接的永远是刚刚聊过的、还热着的话头。
 *
 * 牵挂仍然会作为补充线索给进去（生日、答应过的事这类有具体时间点的），
 * 但它不再是开口的前提 —— 没有牵挂照样可以接话。
 *
 * `skip: true` 是**合法且被鼓励**的输出。这一点必须在提示里用强措辞写死，
 * 否则模型会觉得「系统都让我主动了，不发声显得失职」，于是硬凑一句
 * 「今天过得怎么样」—— 那正是这套设计要消灭的东西。
 */
class ProactiveComposer(
    private val chatService: ChatService,
    /**
     * 与正常聊天共用的系统提示组装器。
     *
     * 主动消息最需要这套上下文：它是在用户没开口的时候说的，
     * 没有任何来自用户的消息帮忙把人格「拉」到位，全靠系统提示撑着。
     */
    private val promptAssembler: io.zer0.muse.transformer.SystemPromptAssembler,
    /**
     * 用户自己立的规矩（「希望 / 不希望它做的事」）。
     *
     * 必需注入而不是内部 `of(context)`：本类不持 `Context`，
     * 且测试要能塞一份内存实现进来验优先级与预算。
     */
    private val directiveStoreProvider: () -> io.zer0.muse.data.assistant.AgentDirectiveStore,
) {

    companion object {
        private const val TAG = "ProactiveComposer"

        /** 正文 token 上限。够写一段话，不足以展开长篇。 */
        private const val MAX_TOKENS = 400

        /** 补充线索条数。多给几条没什么意义，账本只是配菜。 */
        private const val MAX_CANDIDATES = 3

        /** 防重复提示里列出几条历史主动消息。 */
        private const val MAX_RECENT_PROACTIVE = 4

        /**
         * 单条历史消息截断长度。
         *
         * 原来截 300 字，一段稍长的倾诉会被切成半句，接起来的话就容易答非所问。
         * 600 字能容下一次完整的情绪表达，又不会让 40 条消息把窗口撑爆。
         */
        private const val MESSAGE_CLIP = 600

        /**
         * 「距上次聊天 / 距上次主动开口」事实行（v2.6.23 P3）。
         *
         * 两个都没有 → 返回 null，提示词与改动前逐字一致（P3 只加事实，不改无信息时的措辞）。
         * 小时/天取整从粗：精确到分钟只会把模型带成做算术，而不是理解「隔挺久了」。
         */
        internal fun timeFactsLine(input: Input): String? {
            val isZh = input.locale.startsWith("zh", ignoreCase = true)
            val parts = if (isZh) {
                listOfNotNull(
                    input.minutesSinceLastChat?.let { "你们上次聊天距今${humanElapsed(it, isZh = true)}" },
                    input.minutesSinceLastProactive?.let { "你上次主动联系对方距今${humanElapsed(it, isZh = true)}" },
                )
            } else {
                listOfNotNull(
                    input.minutesSinceLastChat?.let { "Your last conversation was ${humanElapsed(it, isZh = false)} ago" },
                    input.minutesSinceLastProactive?.let { "Your last proactive message was ${humanElapsed(it, isZh = false)} ago" },
                )
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(
                separator = if (isZh) "，" else "; ",
                postfix = if (isZh) "。" else ".",
            )
        }

        private fun humanElapsed(minutes: Long, isZh: Boolean): String = if (isZh) {
            when {
                minutes < 60 -> "${minutes.coerceAtLeast(1)} 分钟"
                minutes < 48 * 60 -> "${minutes / 60} 小时"
                else -> "${minutes / (24 * 60)} 天"
            }
        } else {
            when {
                minutes < 60 -> "${minutes.coerceAtLeast(1)} min"
                minutes < 48 * 60 -> "${minutes / 60} hr"
                else -> "${minutes / (24 * 60)} days"
            }
        }

        /**
         * 从完整系统提示中剥离与工具/UI/内部格式相关的段落，只保留轻量人格、记忆、关系与时间上下文。
         * 主动消息是 400 token 的无工具 JSON 调用，注入 `<mood>` / `<artifact>` / `show_card` / 工具清单会严重干扰格式遵循。
         */
        internal fun stripNonProactiveSections(fullPrompt: String): String {
            if (fullPrompt.isBlank()) return ""
            val kept = fullPrompt
                .split("\n\n---\n\n")
                .map { it.trim() }
                .filter { section -> section.isNotEmpty() && !isNonProactiveSection(section) }
            return kept.joinToString("\n\n---\n\n")
        }

        private fun isNonProactiveSection(section: String): Boolean {
            val firstLine = section.lineSequence().firstOrNull()?.trim().orEmpty()
            return NON_PROACTIVE_PREFIXES.any { prefix -> firstLine.startsWith(prefix, ignoreCase = true) } ||
                NON_PROACTIVE_MARKERS.any { marker -> section.contains(marker, ignoreCase = true) }
        }

        private val NON_PROACTIVE_PREFIXES = listOf(
            "平台声明",
            "Platform declaration",
            "工具能力索引",
            "Tool capability index",
            "多 Agent 协作能力",
            "## 收件箱",
            "文件工具沙盒根目录",
            "执行准则",
            "Execution rules",
            "决策规则",
            "Decision rules",
            "工具使用纪律",
            "Tool usage discipline",
            "行动纪律",
            "Action discipline",
            "交付契约",
            "Delivery contract",
            "操作安全原则",
            "Operation safety principles",
            "MOOD 格式",
            "MOOD format",
            "自我反思要求",
            "Self-reflection requirement",
            "产物卡片格式",
            "Artifact format",
            "生成式界面",
            "Generative UI",
        )

        private val NON_PROACTIVE_MARKERS = listOf(
            "<mood>",
            "<reflection>",
            "<artifact",
            "show_card",
            "[[sticker:",
            "delegate_agent",
            "workspace_mounts",
            "find_tools",
        )
    }

    /** 一次生成所需的全部输入。 */
    data class Input(
        val assistant: AssistantEntity,
        /** 当前会话 id，供系统提示里的跨会话衔接排除自身。 */
        val sessionId: String? = null,
        /** 补充线索。可以为空 —— 没有牵挂也能接话。 */
        val candidates: List<PendingThoughtEntity> = emptyList(),
        val recentMessages: List<UIMessage>,
        /** 最近几条主动消息的正文，用来提醒「这些已经说过了」。 */
        val recentProactiveMessages: List<String> = emptyList(),
        val now: Long,
        /**
         * v2.6.23(P3): 距上次聊天的分钟数；null = 没有近期消息。
         *
         * 作为**事实**写进提示词，让模型自己掂量「隔了这么久突然提旧事是不是尬聊」，
         * 而不是只靠事后那句「不要翻旧账」。两者都为 null 时提示词与改动前逐字一致。
         */
        val minutesSinceLastChat: Long? = null,
        /** v2.6.23(P3): 距上一条主动消息的分钟数；null = 从没主动过。 */
        val minutesSinceLastProactive: Long? = null,
        /**
         * 用户选的主动等级。
         *
         * 它决定这次开口的口径：「安静」只说具体的事，「偶尔被想到」可以接话但不主动分享，
         * 标准及以上不额外限制。候选线索的过滤在 [ProactiveCycleRunner] 里做，
         * 这里只负责把口径写进提示词 —— 两边必须用同一个 enum，不能各写一套判断。
         */
        val intensity: ProactiveIntensity = ProactiveIntensity.DEFAULT,
        /** 提示词语言（默认 zh-CN，英文传 en/en-US）。 */
        val locale: String = "zh-CN",
    )

    suspend fun compose(
        input: Input,
        model: io.zer0.ai.core.Model?,
        providerConfig: io.zer0.ai.core.ProviderConfig?,
        temperature: Float,
    ): ProactiveReplyParser.Result {
        // 没有候选也可以说 —— 素材是对话本身，不是账本。
        return callModel(input, model, providerConfig, temperature).fold(
            onSuccess = { ProactiveReplyParser.parse(stripThinkTags(it)) },
            onFailure = { e ->
                Logger.w(TAG, "主动消息生成调用失败: ${e.message}")
                ProactiveReplyParser.Result.Failed(e.message ?: "调用失败")
            },
        )
    }

    /** 真正发请求。返回值包成 Result，让调用方决定失败怎么算。 */
    private suspend fun callModel(
        input: Input,
        model: io.zer0.ai.core.Model?,
        providerConfig: io.zer0.ai.core.ProviderConfig?,
        temperature: Float,
    ): Result<String> = runCatching {
        chatService.completeText(
            messages = buildPrompt(input),
            model = model,
            providerConfig = providerConfig,
            temperature = temperature.coerceIn(0f, 2f),
            maxTokens = MAX_TOKENS,
            reasoningLevel = ReasoningLevel.OFF,
            mode = ChatRequestMode.UTILITY,
        ).text
    }

    // ══════════════════════════════════════════════════════════════
    // 提示构造
    // ══════════════════════════════════════════════════════════════

    internal suspend fun buildPrompt(input: Input): List<UIMessage> {
        val system = UIMessage(role = MessageRole.SYSTEM, content = buildSystemPrompt(input))
        val user = UIMessage(role = MessageRole.USER, content = buildContext(input))
        return listOf(system, user)
    }

    private suspend fun buildSystemPrompt(input: Input): String {
        val full = runCatching {
            promptAssembler.build(
                assistant = input.assistant,
                memoryEnabled = input.assistant.memoryEnabled,
                timeReminderEnabled = input.assistant.enableTimeReminder,
                sessionId = input.sessionId,
            ).firstOrNull()?.content.orEmpty()
        }.getOrDefault("")
        // 剥离工具/UI/MOOD 等与无工具 JSON 主动消息无关的段落，保留轻量人格、记忆、关系与时间；
        // 组装失败或裁剪后为空时退回最小人格。
        val persona = stripNonProactiveSections(full).ifBlank { fallbackPersona(input.assistant, input.locale) }
        // 用户自己立的规矩（「希望/不希望它做的事」）。读不到就整段不加：
        // 空清单必须与改动前逐字一致，否则这个功能上线就等于悄悄改了所有人的提示词。
        val directives = runCatching {
            AgentDirectivesPrompt.build(
                directives = directiveStoreProvider().directivesFor(input.assistant.id),
                totalBudgetChars = io.zer0.muse.data.assistant.AgentDirectiveStore.MAX_TOTAL_CHARS,
                locale = input.locale,
            )
        }.getOrNull()
        return buildString {
            appendLine(persona)
            appendLine()
            appendLine("---")
            appendLine()
            append(proactiveInstruction(input))
            directives?.block?.takeIf { it.isNotBlank() }?.let { block ->
                appendLine()
                appendLine()
                append(block)
            }
        }
    }

    /** 组装器拿不到内容时的兜底人格。 */
    private fun fallbackPersona(assistant: AssistantEntity, locale: String): String = buildString {
        val isZh = locale.startsWith("zh", ignoreCase = true)
        val name = assistant.name.ifBlank { "Muse" }
        if (isZh) {
            appendLine("你是「$name」。")
            if (assistant.personaDefinition.isNotBlank()) {
                appendLine("【人格定义】")
                appendLine(assistant.personaDefinition)
            }
            if (assistant.systemPrompt.isNotBlank()) {
                appendLine("【人设与行为准则】")
                appendLine(assistant.systemPrompt)
            }
        } else {
            appendLine("You are \"$name\".")
            if (assistant.personaDefinition.isNotBlank()) {
                appendLine("[Persona]")
                appendLine(assistant.personaDefinition)
            }
            if (assistant.systemPrompt.isNotBlank()) {
                appendLine("[System Prompt]")
                appendLine(assistant.systemPrompt)
            }
        }
    }

    /**
     * 按主动等级给出不同的「这次能说什么」。
     *
     * 三个档位不是频次高低，是三种不同的开口理由。
     * 「安静」档若仍把「从最近对话接话」的指导给它，用户选了安静也照样会收到寒暄 ——
     * 提示词里的规则比字段上的枚举更有决定性，所以这一段必须按档位换掉，而不是补充。
     */
    private fun intensityGuidance(intensity: ProactiveIntensity, isZh: Boolean): String = if (isZh) {
        when (intensity) {
            ProactiveIntensity.QUIET -> buildString {
                appendLine("【这次只说什么】")
                appendLine("你现在被设成「安静」：只有确有具体事项才开口。")
                appendLine("「你另外还惦记着的事」里有一条，就说那件事；一条都没有，就 skip。")
                appendLine("不要寒暄，不要为了接话而开口，不要分享你自己的见闻，不要提你们之间的节点。")
                appendLine()
            }

            ProactiveIntensity.RESERVED -> buildString {
                append(findTopicGuidance(isZh = true))
                appendLine("【这次只说什么】")
                appendLine("你现在被设成「偶尔被想到」：可以接着聊过的话说，也可以关心对方的状态。")
                appendLine("不要主动分享你自己的事，不要报你们之间的节点。")
                appendLine()
            }

            ProactiveIntensity.STANDARD,
            ProactiveIntensity.ACTIVE,
            ProactiveIntensity.CLINGY,
            -> findTopicGuidance(isZh = true)
        }
    } else {
        when (intensity) {
            ProactiveIntensity.QUIET -> buildString {
                appendLine("[Scope]")
                appendLine("You are in QUIET mode: speak ONLY when there is a concrete pending item to mention.")
                appendLine("If \"Pending items\" has an actionable item, mention it; otherwise skip.")
                appendLine("Do not make small talk, do not follow up casually, do not share random updates, and do not mention milestones.")
                appendLine()
            }

            ProactiveIntensity.RESERVED -> buildString {
                append(findTopicGuidance(isZh = false))
                appendLine("[Scope]")
                appendLine("You are in RESERVED mode: you may follow up on recent conversation or check in on the user.")
                appendLine("Do not share unrelated updates about yourself or announce milestones.")
                appendLine()
            }

            ProactiveIntensity.STANDARD,
            ProactiveIntensity.ACTIVE,
            ProactiveIntensity.CLINGY,
            -> findTopicGuidance(isZh = false)
        }
    }

    /** 从最近对话里找话头的指导。只有允许「接话」的档位才用它。 */
    private fun findTopicGuidance(isZh: Boolean): String = if (isZh) {
        buildString {
            appendLine("【怎么找话说】")
            appendLine("下面的「最近聊的」是你们真实的对话，按时间顺序排。你想接着上次的话自然往下说一句：")
            appendLine()
            appendLine("1. 先看最后几句。对方最后说的是什么？")
            appendLine("2. 如果对方提了一件事但没说结果（「我去吃饭了」「等下要开会」），就问那件事怎样了。")
            appendLine("   比如对方说去吃饭，那就是「吃完没」「吃的啥」——这是最自然的接法。")
            appendLine("3. 如果最后是你说的、对方没接，就看你那句话还有没有下文；没有就别硬续。")
            appendLine("4. 如果最近这一段已经聊完了、聊干净了，那就不说。这很正常。")
            appendLine()
        }
    } else {
        buildString {
            appendLine("[How to find something to say]")
            appendLine("Below is your real recent conversation in chronological order. Pick up naturally from where you left off:")
            appendLine()
            appendLine("1. Check the last few turns. What did the user say last?")
            appendLine(
                "2. If the user mentioned an unfinished activity (\"going for lunch\", \"heading into a meeting\"), " +
                    "ask how it went.",
            )
            appendLine(
                "3. If you spoke last and the user did not reply, only follow up if there is a genuine next beat; " +
                    "otherwise don't force it.",
            )
            appendLine("4. If the topic already concluded cleanly, skip. That is completely fine.")
            appendLine()
        }
    }

    /**
     * 主动消息这一轮的附加约束。
     *
     * 上面那一大段是聊天时也在用的通用人格，这里只补「主动开口」这个场景特有的东西。
     */
    private fun proactiveInstruction(input: Input): String {
        val isZh = input.locale.startsWith("zh", ignoreCase = true)
        if (!isZh) {
            return buildString {
                appendLine(
                    if (input.intensity == ProactiveIntensity.QUIET) {
                        "Current situation: it has been a while since you last spoke. " +
                            "Only reach out if there is a concrete pending item."
                    } else {
                        "Current situation: it has been a while since you last spoke, " +
                            "and you are deciding whether to send a brief follow-up."
                    },
                )
                timeFactsLine(input)?.let { appendLine(it) }
                appendLine("The user did not ask a question and is not waiting for a reply.")
                appendLine()
                append(intensityGuidance(input.intensity, isZh = false))
                appendLine("[Most important rule]")
                appendLine("**Do not dredge up old topics.** Real friends do not randomly bring up something from days ago out of nowhere.")
                appendLine(
                    "Stay connected to the recent chat. " +
                        "If nothing was discussed recently and no pending item applies, prefer skipping.",
                )
                appendLine()
                appendLine("[Avoid]")
                appendLine("- Generic greetings (\"How's your day?\", \"Are you busy?\")")
                appendLine("- Repeating or paraphrasing your recent proactive messages")
                appendLine("- Reciting remembered facts just to prove you remember them")
                appendLine("- Customer-service tone (\"How can I help you today?\") or calling yourself an assistant")
                appendLine("- Fabricating events that never happened between you")
                appendLine()
                appendLine("[Skipping is completely normal]")
                appendLine("A real friend does not message every day. Forcing a line is worse than staying quiet. If nothing fits, skip.")
                appendLine()
                appendLine("[Output Format] Return strict JSON only, no markdown fences, no extra explanation:")
                appendLine("""{"skip": true, "reason": "why there is nothing natural to say"}""")
                appendLine("or:")
                appendLine("""{"skip": false, "content": "your message", "tone": "care|share|casual", "thought_id": ""}""")
                appendLine()
                appendLine(
                    "For thought_id: copy the exact id ONLY if your message directly addresses an item " +
                        "in Pending items; otherwise use an empty string \"\".",
                )
                appendLine("Keep content natural like a chat message: 1-3 short sentences, single paragraph, no lists or markdown.")
            }
        }

        return buildString {
            appendLine(
                if (input.intensity == ProactiveIntensity.QUIET) {
                    "现在的情况：你们有一阵没说话了。当前处于「安静」模式，只在有明确事项时才开口。"
                } else {
                    "现在的情况：你们有一阵没说话了，你在决定要不要顺着近况主动发一句消息。"
                },
            )
            // v2.6.23(P3): 时间跨度事实行;拿不到跨度时整行不出现(与旧行为逐字一致)。
            timeFactsLine(input)?.let { appendLine(it) }
            appendLine("对方不是在问你问题，也没在等你回复——只有在确实自然、有话可说时再开口。")
            appendLine()
            append(intensityGuidance(input.intensity, isZh = true))
            appendLine("【最要紧的一条】")
            appendLine("**不要翻旧账。** 真人不会记着一件事，然后隔了两三天突然发消息提起。")
            appendLine("你说的话要和「最近聊的」直接连着，中间不要跨越好几天的话题。")
            appendLine("如果最近这几天根本没聊过什么，那就宁可不发。")
            appendLine()
            appendLine("【不要】")
            appendLine("- 空泛的问候（「今天过得怎么样」「在忙吗」）")
            appendLine("- 重复你最近已经说过的话，换个说法也不行")
            appendLine("- 复述你记得的信息来证明自己记性好")
            appendLine("- 假装自己是来提供服务的（「有什么可以帮您」）")
            appendLine("- 编造没有发生在你们之间的事")
            appendLine()
            appendLine("【放弃是完全正常的】")
            appendLine("一个真朋友也不是每天都有话要说。硬凑一句比不说话糟糕得多。")
            appendLine("想不到能接什么，就 skip。")
            appendLine()
            appendLine("【输出格式】只返回 JSON，不要 markdown 代码块，不要任何解释：")
            appendLine("""{"skip": true, "reason": "为什么没什么可接的"}""")
            appendLine("或者：")
            appendLine(
                """{"skip": false, "content": "你要说的话", "tone": "关心|分享|随口一提", "thought_id": ""}""",
            )
            appendLine()
            appendLine("thought_id 怎么填：")
            appendLine("只有当你这句话正是在说「你另外还惦记着的事」里的某一条时，才把那条的 id 原样抄进去。")
            appendLine("其余情况留空字符串，不要自己编一个 id。")
            appendLine()
            appendLine("content 要求：像发微信一样自然，一到三句话为主，不要分段，不要用列表。")
            appendLine("不要出现「提醒」「温馨」「亲」这类客服腔。不要自称「助手」。")
        }
    }

    private fun buildContext(input: Input): String {
        val isZh = input.locale.startsWith("zh", ignoreCase = true)
        if (!isZh) {
            return buildString {
                appendLine("## Recent chat")
                if (input.recentMessages.isEmpty()) {
                    appendLine("(No recent messages. Usually you should skip.)")
                } else {
                    input.recentMessages.forEach { msg ->
                        val who = if (msg.role == MessageRole.USER) "User" else "You"
                        appendLine("[$who] ${msg.content.take(MESSAGE_CLIP)}")
                    }
                }
                appendLine()
                if (input.recentProactiveMessages.isNotEmpty()) {
                    appendLine("## Your recent proactive messages (do not repeat or paraphrase)")
                    input.recentProactiveMessages.take(MAX_RECENT_PROACTIVE).forEach { text ->
                        appendLine("- ${text.take(MESSAGE_CLIP)}")
                    }
                    appendLine()
                }
                if (input.candidates.isNotEmpty()) {
                    appendLine("## Pending items (use only if natural, never force)")
                    input.candidates.take(MAX_CANDIDATES).forEach { thought ->
                        val kindLabel = describeKind(thought, isZh = false)
                        val ageLabel = describeAge(thought, input.now, isZh = false)
                        appendLine("- $kindLabel | $ageLabel | id=${thought.id}")
                        appendLine("  ${thought.seed}")
                    }
                    appendLine()
                }
                appendLine(
                    if (input.intensity.onlyWhenNeeded) {
                        "Mention a concrete pending item if one applies; otherwise skip."
                    } else {
                        "Follow up naturally from the recent chat, or skip."
                    },
                )
            }
        }

        return buildString {
            appendLine("## 最近聊的")
            if (input.recentMessages.isEmpty()) {
                appendLine("（你们还没聊过什么。这种情况下通常不该主动开口。）")
            } else {
                input.recentMessages.forEach { msg ->
                    val who = if (msg.role == MessageRole.USER) "用户" else "你"
                    appendLine("[$who] ${msg.content.take(MESSAGE_CLIP)}")
                }
            }
            appendLine()
            if (input.recentProactiveMessages.isNotEmpty()) {
                appendLine("## 你最近主动说过的话（不要再说一遍，换说法也不行）")
                input.recentProactiveMessages.take(MAX_RECENT_PROACTIVE).forEach { text ->
                    appendLine("- ${text.take(MESSAGE_CLIP)}")
                }
                appendLine()
            }
            if (input.candidates.isNotEmpty()) {
                appendLine("## 你另外还惦记着的事（能自然接上就用，接不上就别硬提）")
                input.candidates.take(MAX_CANDIDATES).forEach { thought ->
                    // id 必须露给模型：这条消息说完之后要靠它回填 thought_id，
                    // 账本才能记下「这件事已经说过了」。不露 id 的话，回填永远是空的，
                    // surfaceCount 恒为 0，「反复念叨同一件事就自动放弃」就永不触发。
                    appendLine("- ${describeKind(thought, isZh = true)}｜${describeAge(thought, input.now, isZh = true)}｜id=${thought.id}")
                    appendLine("  ${thought.seed}")
                }
                appendLine()
            }
            appendLine(
                if (input.intensity.onlyWhenNeeded) {
                    "有具体的事就说那件事，没有就 skip。"
                } else {
                    "接着「最近聊的」往下说一句，或者 skip。"
                },
            )
        }
    }

    /** 牵挂性质的描述，用于让模型理解「这是什么类型的话」。 */
    private fun describeKind(thought: PendingThoughtEntity, isZh: Boolean): String = if (isZh) {
        when (thought.kindEnum()) {
            ThoughtKind.REMINDER -> "要说的事（有具体时间点）"
            ThoughtKind.FOLLOW_UP -> "没聊完的话题"
            ThoughtKind.CARE -> "关于对方的状态"
            ThoughtKind.SHARE -> "你想分享的事"
            ThoughtKind.MILESTONE -> "你们之间的节点"
        }
    } else {
        when (thought.kindEnum()) {
            ThoughtKind.REMINDER -> "Time-specific reminder"
            ThoughtKind.FOLLOW_UP -> "Unfinished topic"
            ThoughtKind.CARE -> "Check-in about the user"
            ThoughtKind.SHARE -> "Something to share"
            ThoughtKind.MILESTONE -> "Milestone"
        }
    }

    /** 事由的时间感。模型需要知道「刚发生」还是「好几周了」。 */
    private fun describeAge(thought: PendingThoughtEntity, now: Long, isZh: Boolean): String {
        val hours = (now - thought.createdAt).coerceAtLeast(0L) / 3_600_000L
        return if (isZh) {
            val age = when {
                hours < 1 -> "刚刚想起"
                hours < 24 -> "${hours}小时前想起"
                else -> "${hours / 24}天前想起"
            }
            val urgency = when (thought.urgencyEnum()) {
                ThoughtUrgency.IMMEDIATE -> "时效很紧"
                ThoughtUrgency.WINDOW -> "这几天说都行"
                ThoughtUrgency.EVERGREEN -> "什么时候说都行"
            }
            "$age，$urgency"
        } else {
            val age = when {
                hours < 1 -> "just noted"
                hours < 24 -> "${hours}h ago"
                else -> "${hours / 24}d ago"
            }
            val urgency = when (thought.urgencyEnum()) {
                ThoughtUrgency.IMMEDIATE -> "time-sensitive"
                ThoughtUrgency.WINDOW -> "next few days"
                ThoughtUrgency.EVERGREEN -> "anytime"
            }
            "$age, $urgency"
        }
    }
}
