package io.zer0.muse.proactive

import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.proactive.PendingThoughtEntity
import io.zer0.muse.data.proactive.ProactiveIntensity
import io.zer0.muse.data.proactive.ThoughtKind
import io.zer0.muse.data.proactive.ThoughtUrgency

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
         * 用户选的主动等级。
         *
         * 它决定这次开口的口径：「安静」只说具体的事，「偶尔被想到」可以接话但不主动分享，
         * 标准及以上不额外限制。候选线索的过滤在 [ProactiveCycleRunner] 里做，
         * 这里只负责把口径写进提示词 —— 两边必须用同一个 enum，不能各写一套判断。
         */
        val intensity: ProactiveIntensity = ProactiveIntensity.DEFAULT,
    )

    suspend fun compose(
        input: Input,
        model: io.zer0.ai.core.Model?,
        providerConfig: io.zer0.ai.core.ProviderConfig?,
        temperature: Float,
    ): ProactiveReplyParser.Result {
        // 没有候选也可以说 —— 素材是对话本身，不是账本。
        return callModel(input, model, providerConfig, temperature).fold(
            onSuccess = { ProactiveReplyParser.parse(it) },
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
        // 组装失败时退回最小人格，不能因为系统提示出问题就让主动消息说不出人话。
        val persona = full.ifBlank { fallbackPersona(input.assistant) }
        return buildString {
            appendLine(persona)
            appendLine()
            appendLine("---")
            appendLine()
            append(proactiveInstruction(input.intensity))
        }
    }

    /** 组装器拿不到内容时的兜底人格。 */
    private fun fallbackPersona(assistant: AssistantEntity): String = buildString {
        appendLine("你是「${assistant.name.ifBlank { "muse" }}」。")
        if (assistant.personaDefinition.isNotBlank()) {
            appendLine("【人格定义】")
            appendLine(assistant.personaDefinition)
        }
        if (assistant.systemPrompt.isNotBlank()) {
            appendLine("【人设与行为准则】")
            appendLine(assistant.systemPrompt)
        }
    }

    /**
     * 按主动等级给出不同的「这次能说什么」。
     *
     * 三个档位不是频次高低，是三种不同的开口理由。
     * 「安静」档若仍把「从最近对话接话」的指导给它，用户选了安静也照样会收到寒暄 ——
     * 提示词里的规则比字段上的枚举更有决定性，所以这一段必须按档位换掉，而不是补充。
     */
    private fun intensityGuidance(intensity: ProactiveIntensity): String = when (intensity) {
        ProactiveIntensity.QUIET -> buildString {
            appendLine("【这次只说什么】")
            appendLine("你现在被设成「安静」：只有具体的事才开口。")
            appendLine("「你另外还惦记着的事」里有一条，就说那件事；一条都没有，就 skip。")
            appendLine("不要寒暄，不要分享你自己的见闻，不要提你们之间的节点。")
            appendLine()
        }

        ProactiveIntensity.RESERVED -> buildString {
            append(findTopicGuidance())
            appendLine("【这次只说什么】")
            appendLine("你现在被设成「偶尔被想到」：可以接着聊过的话说，也可以关心他的状态。")
            appendLine("不要主动分享你自己的事，不要报你们之间的节点。")
            appendLine()
        }

        ProactiveIntensity.STANDARD,
        ProactiveIntensity.ACTIVE,
        ProactiveIntensity.CLINGY,
        -> findTopicGuidance()
    }

    /** 从最近对话里找话头的指导。只有允许「接话」的档位才用它。 */
    private fun findTopicGuidance(): String = buildString {
        appendLine("【怎么找话说】")
        appendLine("下面的「最近聊的」是你们真实的对话，按时间顺序排。你要从这里找一句能接的话：")
        appendLine()
        appendLine("1. 先看最后几句。他最后说的是什么？")
        appendLine("2. 如果他提了一件事但没说结果（「我去吃饭了」「等下要开会」），就问那件事怎样了。")
        appendLine("   比如他说去吃饭，那就是「吃完没」「吃的啥」——这是最自然的接法。")
        appendLine("3. 如果最后是你说的、他没接，就看你那句话还有没有下文；没有就别硬续。")
        appendLine("4. 如果最近这一段已经聊完了、聊干净了，那就不说。这很正常。")
        appendLine()
    }

    /**
     * 主动消息这一轮的附加约束。
     *
     * 上面那一大段是聊天时也在用的通用人格，这里只补「主动开口」这个场景特有的东西。
     */
    private fun proactiveInstruction(intensity: ProactiveIntensity): String = buildString {
        appendLine("现在的情况：你们有一阵没说话了。你想接着上次的话往下说一句。")
        appendLine("他不是在问你问题，也没在等你回复——但你有点话想说，或者只是想知道他那件事怎么样了。")
        appendLine()
        append(intensityGuidance(intensity))
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

    private fun buildContext(input: Input): String = buildString {
        appendLine("## 最近聊的")
        if (input.recentMessages.isEmpty()) {
            appendLine("（你们还没聊过什么。这种情况下通常不该主动开口。）")
        } else {
            input.recentMessages.forEach { msg ->
                val who = if (msg.role == MessageRole.USER) "他" else "你"
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
                appendLine("- ${describeKind(thought)}｜${describeAge(thought, input.now)}｜id=${thought.id}")
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

    /** 牵挂性质的中文描述，用于让模型理解「这是什么类型的话」。 */
    private fun describeKind(thought: PendingThoughtEntity): String = when (thought.kindEnum()) {
        ThoughtKind.REMINDER -> "要说的事（有具体时间点）"
        ThoughtKind.FOLLOW_UP -> "没聊完的话题"
        ThoughtKind.CARE -> "关于他的状态"
        ThoughtKind.SHARE -> "你想分享的事"
        ThoughtKind.MILESTONE -> "你们之间的节点"
    }

    /** 事由的时间感。模型需要知道「刚发生」还是「好几周了」。 */
    private fun describeAge(thought: PendingThoughtEntity, now: Long): String {
        val hours = (now - thought.createdAt).coerceAtLeast(0L) / 3_600_000L
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
        return "$age，$urgency"
    }
}
