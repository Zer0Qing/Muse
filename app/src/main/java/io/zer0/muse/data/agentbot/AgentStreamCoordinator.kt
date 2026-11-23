package io.zer0.muse.data.agentbot

import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 原生流式快慢双轨前台大脑。
 *
 * 与旧版强制 `bot_say` + 非流式 600 token 协议不同：
 * - 普通对话与轻问答直接走原生 SSE 流式输出，毫秒级首字响应且兼容全部供应商；
 * - 控制工具（贴表情反应、派发异步任务、运行中插话、给可点选项、存惯例）采用 `auto` 声明，
 *   不强迫模型每次必调工具；
 * - 即使在流式生成过程中，收到完整或结束后的工具调用也会统一结构化结算，并与
 *   [PublicationService] 保持同样的幂等与 revision 防串话护栏。
 */
class AgentStreamCoordinator(
    private val streamingClient: StreamingClient,
    private val publication: PublicationService,
    private val governor: BotResourceGovernor,
) {

    fun interface StreamingClient {
        suspend fun stream(
            messages: List<UIMessage>,
            tools: List<ToolDefinition>,
            providerConfig: ProviderConfig?,
            model: Model?,
            sessionId: String,
        ): Flow<ChatStreamEvent>
    }

    /** 流式期间增量吐给 UI 的实时草稿。 */
    data class StreamingDraft(
        val text: String = "",
        val reasoning: String = "",
        val citationUrls: List<String> = emptyList(),
        val reactions: List<ReactionAction> = emptyList(),
    )

    data class ReactionAction(
        val emoji: String,
        val targetMessageId: String = "",
    )

    data class MissionDispatch(
        val goal: String,
        val acceptance: String = "",
        val resourceScope: String = "",
        val assigneeBotId: String = "",
    )

    data class SteerAction(
        val taskId: String,
        val instruction: String,
    )

    @Serializable
    data class ChoiceOption(
        val id: String,
        val label: String,
        val prompt: String = "",
    )

    data class ChoiceOffer(
        val prompt: String = "",
        val options: List<ChoiceOption> = emptyList(),
    )

    data class RoutineCreation(
        val title: String,
        val goalPrompt: String,
        val triggerType: String = RoutineTriggerType.MANUAL,
        val triggerExpr: String = "",
        val resourceScope: String = "",
    )

    data class TurnOutcome(
        val text: String = "",
        val reasoning: String = "",
        val citationUrls: List<String> = emptyList(),
        val reactions: List<ReactionAction> = emptyList(),
        val dispatches: List<MissionDispatch> = emptyList(),
        val steerings: List<SteerAction> = emptyList(),
        val choiceOffer: ChoiceOffer? = null,
        val routineCreations: List<RoutineCreation> = emptyList(),
        val publishedMessageId: String = "",
        val requestSuperseded: Boolean = false,
        val quotaRejected: Boolean = false,
        val streamError: String? = null,
    ) {
        val hasAnyAction: Boolean
            get() = text.isNotBlank() ||
                reactions.isNotEmpty() ||
                dispatches.isNotEmpty() ||
                steerings.isNotEmpty() ||
                choiceOffer != null ||
                routineCreations.isNotEmpty()
    }

    private data class ToolCallAccumulator(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )

    suspend fun runStreamingTurn(
        identity: AgentTurnIdentity,
        systemPrompt: String,
        history: List<UIMessage>,
        userText: String,
        inputImageBase64List: List<String> = emptyList(),
        providerConfig: ProviderConfig? = null,
        model: Model? = null,
        currentRequestRevision: suspend () -> Long,
        onDraft: (StreamingDraft) -> Unit = {},
    ): TurnOutcome {
        val identityError = identity.validate()
        if (identityError != null) {
            Logger.e(TAG, "流式回合身份无效: $identityError")
            return TurnOutcome(streamError = identityError)
        }

        val messages = buildList {
            if (systemPrompt.isNotBlank()) {
                add(UIMessage(role = MessageRole.SYSTEM, content = systemPrompt))
            }
            addAll(history)
            add(
                UIMessage(
                    role = MessageRole.USER,
                    content = userText,
                    imageBase64List = inputImageBase64List,
                ),
            )
        }

        val textBuilder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        val citationSet = linkedSetOf<String>()
        val toolAccumulators = linkedMapOf<Int, ToolCallAccumulator>()
        var streamError: String? = null

        try {
            val flow = streamingClient.stream(
                messages = messages,
                tools = AGENT_TOOLS,
                providerConfig = providerConfig,
                model = model,
                sessionId = identity.sessionId,
            )
            flow.collect { event ->
                when (event) {
                    is ChatStreamEvent.ContentDelta -> {
                        if (event.delta.isNotEmpty()) {
                            textBuilder.append(event.delta)
                            onDraft(
                                StreamingDraft(
                                    text = textBuilder.toString(),
                                    reasoning = reasoningBuilder.toString(),
                                    citationUrls = citationSet.toList(),
                                ),
                            )
                        }
                    }
                    is ChatStreamEvent.ReasoningDelta -> {
                        if (event.delta.isNotEmpty()) {
                            reasoningBuilder.append(event.delta)
                            onDraft(
                                StreamingDraft(
                                    text = textBuilder.toString(),
                                    reasoning = reasoningBuilder.toString(),
                                    citationUrls = citationSet.toList(),
                                ),
                            )
                        }
                    }
                    is ChatStreamEvent.CitationDelta -> {
                        if (event.urls.isNotEmpty()) {
                            citationSet.addAll(event.urls)
                            onDraft(
                                StreamingDraft(
                                    text = textBuilder.toString(),
                                    reasoning = reasoningBuilder.toString(),
                                    citationUrls = citationSet.toList(),
                                ),
                            )
                        }
                    }
                    is ChatStreamEvent.ToolCallDelta -> {
                        val acc = toolAccumulators.getOrPut(event.index) { ToolCallAccumulator() }
                        event.id?.takeIf { it.isNotBlank() }?.let { acc.id = it }
                        event.name?.takeIf { it.isNotBlank() }?.let { acc.name = it }
                        event.argumentsDelta?.takeIf { it.isNotEmpty() }?.let { delta ->
                            if (event.isSnapshot) {
                                acc.arguments.clear()
                            }
                            acc.arguments.append(delta)
                        }
                    }
                    is ChatStreamEvent.Error -> {
                        streamError = event.message
                    }
                    is ChatStreamEvent.StreamInterrupted -> {
                        if (textBuilder.isEmpty()) {
                            streamError = event.message
                        }
                    }
                    is ChatStreamEvent.Done,
                    is ChatStreamEvent.FallbackNotice,
                    is ChatStreamEvent.ImageDelta,
                    is ChatStreamEvent.UsageDelta,
                    -> Unit
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "流式前台回合异常: ${e.message}", e)
            streamError = e.message ?: "stream_failed"
        }

        if (currentRequestRevision() > identity.requestRevision) {
            Logger.i(TAG, "输入版本已更新，丢弃过期流式回合: ${identity.requestId}")
            return TurnOutcome(requestSuperseded = true)
        }

        val toolCalls = toolAccumulators.values
            .filter { it.name.isNotBlank() }
            .map { acc ->
                ToolCall(
                    id = acc.id.ifBlank { "tc-${acc.name}-${System.nanoTime()}" },
                    name = acc.name,
                    arguments = acc.arguments.toString(),
                )
            }

        val parsed = parseToolCalls(toolCalls, fallbackText = textBuilder.toString())
        val finalReactions = parsed.reactions.toMutableList()
        if (parsed.dispatches.isNotEmpty() && finalReactions.isEmpty()) {
            // 派发后台任务但模型未主动贴角标时，自动给用户原消息贴一枚轻量确认角标。
            finalReactions += ReactionAction(emoji = DEFAULT_DISPATCH_EMOJI, targetMessageId = identity.requestId)
        }
        val finalDispatches = mutableListOf<MissionDispatch>()
        var quotaRejected = false
        for (dispatch in parsed.dispatches) {
            when (val verdict = governor.admit(runningCount = 0)) {
                is BotResourceGovernor.Verdict.Go,
                is BotResourceGovernor.Verdict.Queue,
                -> finalDispatches += dispatch
                is BotResourceGovernor.Verdict.Reject -> {
                    Logger.w(TAG, "后台配额拒绝派工: ${verdict.reason}")
                    quotaRejected = true
                }
            }
        }

        val replyText = parsed.sayText.trim()
        val effectiveText = when {
            replyText.isNotBlank() -> replyText
            finalDispatches.isNotEmpty() -> DEFAULT_ACK_TEXT
            parsed.steerings.isNotEmpty() -> DEFAULT_STEER_ACK_TEXT
            parsed.routineCreations.isNotEmpty() -> DEFAULT_ROUTINE_ACK_TEXT
            else -> ""
        }

        var publishedMessageId = ""
        if (effectiveText.isNotBlank()) {
            val publishResult = runCatching {
                publication.publish(
                    botId = identity.assistantId,
                    originSessionId = identity.sessionId,
                    causeEventIds = identity.requestId,
                    requestRevision = identity.requestRevision,
                    taskRevision = 1,
                    items = listOf(PublicationService.Item(PublicationKind.TEXT, effectiveText)),
                    currentRequestRevision = currentRequestRevision(),
                )
            }.onFailure {
                Logger.w(TAG, "流式回复落盘发布失败: ${it.message}", it)
            }.getOrNull()
            publishedMessageId = publishResult?.messageIds?.firstOrNull().orEmpty()
        }

        return TurnOutcome(
            text = effectiveText,
            reasoning = reasoningBuilder.toString().trim(),
            citationUrls = citationSet.toList(),
            reactions = finalReactions,
            dispatches = finalDispatches,
            steerings = parsed.steerings,
            choiceOffer = parsed.choiceOffer,
            routineCreations = parsed.routineCreations,
            publishedMessageId = publishedMessageId,
            quotaRejected = quotaRejected,
            streamError = if (effectiveText.isBlank() && finalDispatches.isEmpty() && finalReactions.isEmpty()) {
                streamError
            } else {
                null
            },
        )
    }

    private data class ParsedActions(
        val sayText: String,
        val reactions: List<ReactionAction>,
        val dispatches: List<MissionDispatch>,
        val steerings: List<SteerAction>,
        val choiceOffer: ChoiceOffer?,
        val routineCreations: List<RoutineCreation>,
    )

    private fun parseToolCalls(toolCalls: List<ToolCall>, fallbackText: String): ParsedActions {
        var explicitSay = ""
        val reactions = mutableListOf<ReactionAction>()
        val dispatches = mutableListOf<MissionDispatch>()
        val steerings = mutableListOf<SteerAction>()
        var choiceOffer: ChoiceOffer? = null
        val routines = mutableListOf<RoutineCreation>()

        toolCalls.forEach { call ->
            val args = runCatching {
                json.parseToJsonElement(call.arguments) as? JsonObject
            }.onFailure {
                Logger.w(TAG, "流式工具参数解析失败(${call.name}): ${it.message}")
            }.getOrNull()

            when (call.name) {
                TOOL_REACT -> {
                    val emoji = args?.str("emoji").orEmpty().trim()
                    if (emoji.isNotBlank()) {
                        reactions += ReactionAction(
                            emoji = emoji.take(MAX_EMOJI_CHARS),
                            targetMessageId = args?.str("target_message_id").orEmpty().trim(),
                        )
                    }
                }
                TOOL_LAUNCH_MISSION,
                ForegroundCoordinator.TOOL_DISPATCH,
                -> {
                    val goal = args?.str("goal").orEmpty().trim()
                    if (goal.isNotBlank()) {
                        dispatches += MissionDispatch(
                            goal = goal,
                            acceptance = args?.str("acceptance").orEmpty().trim(),
                            resourceScope = args?.str("resource_scope").orEmpty().trim(),
                            assigneeBotId = args?.str("assignee_bot_id").orEmpty().trim(),
                        )
                    }
                }
                TOOL_STEER_MISSION -> {
                    val taskId = args?.str("task_id").orEmpty().trim()
                    val instruction = args?.str("instruction").orEmpty().trim()
                    if (taskId.isNotBlank() && instruction.isNotBlank()) {
                        steerings += SteerAction(taskId = taskId, instruction = instruction)
                    }
                }
                TOOL_OFFER_CHOICES -> {
                    val prompt = args?.str("prompt").orEmpty().trim()
                    val rawOptions = (args?.get("options") as? JsonArray).orEmpty()
                    val parsedOptions = rawOptions.mapIndexedNotNull { idx, elem ->
                        when (elem) {
                            is JsonPrimitive -> {
                                val text = elem.content.trim()
                                if (text.isBlank()) null else ChoiceOption("opt_${idx + 1}", text, text)
                            }
                            is JsonObject -> {
                                val label = elem.str("label").ifBlank { elem.str("title") }.trim()
                                if (label.isBlank()) {
                                    null
                                } else {
                                    ChoiceOption(
                                        id = elem.str("id").ifBlank { "opt_${idx + 1}" },
                                        label = label,
                                        prompt = elem.str("prompt").ifBlank { label }.trim(),
                                    )
                                }
                            }
                            else -> null
                        }
                    }.take(MAX_CHOICE_OPTIONS)
                    if (parsedOptions.isNotEmpty()) {
                        choiceOffer = ChoiceOffer(prompt = prompt, options = parsedOptions)
                    }
                }
                TOOL_CREATE_ROUTINE -> {
                    val title = args?.str("title").orEmpty().trim()
                    val goal = args?.str("goal").orEmpty().trim()
                    if (title.isNotBlank() && goal.isNotBlank()) {
                        val triggerRaw = args?.str("trigger_type").orEmpty().trim().uppercase()
                        val triggerType = if (triggerRaw in RoutineTriggerType.all) {
                            triggerRaw
                        } else {
                            RoutineTriggerType.MANUAL
                        }
                        routines += RoutineCreation(
                            title = title,
                            goalPrompt = goal,
                            triggerType = triggerType,
                            triggerExpr = args?.str("trigger_expr").orEmpty().trim(),
                            resourceScope = args?.str("resource_scope").orEmpty().trim(),
                        )
                    }
                }
                ForegroundCoordinator.TOOL_SAY -> {
                    val text = args?.str("text").orEmpty().trim()
                    if (text.isNotBlank()) explicitSay = text
                }
                else -> Logger.w(TAG, "忽略未注册的前台工具: ${call.name}")
            }
        }

        val combinedSay = fallbackText.trim().ifBlank { explicitSay }
        return ParsedActions(
            sayText = combinedSay,
            reactions = reactions,
            dispatches = dispatches,
            steerings = steerings,
            choiceOffer = choiceOffer,
            routineCreations = routines,
        )
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content.orEmpty()

    companion object {
        private const val TAG = "AgentStreamCoord"
        private const val MAX_EMOJI_CHARS = 8
        private const val MAX_CHOICE_OPTIONS = 4
        private const val DEFAULT_DISPATCH_EMOJI = "👌"
        private const val DEFAULT_ACK_TEXT = "好的，我先放到后台帮你处理，弄好后马上告诉你。"
        private const val DEFAULT_STEER_ACK_TEXT = "收到，我已把新要求同步给正在执行的任务。"
        private const val DEFAULT_ROUTINE_ACK_TEXT = "已经为你保存成可复用的惯例，随时可在工作台管理。"

        const val TOOL_REACT = "react_message"
        const val TOOL_LAUNCH_MISSION = "launch_mission"
        const val TOOL_STEER_MISSION = "steer_mission"
        const val TOOL_OFFER_CHOICES = "offer_choices"
        const val TOOL_CREATE_ROUTINE = "create_routine"

        private val json = Json { ignoreUnknownKeys = true }

        fun fromChatService(chatService: ChatService): StreamingClient =
            StreamingClient { messages, tools, providerConfig, model, sessionId ->
                chatService.streamChat(
                    messages = messages,
                    model = model,
                    tools = tools,
                    toolChoice = "auto",
                    providerConfig = providerConfig,
                    mode = ChatRequestMode.CHAT,
                    sessionId = sessionId,
                )
            }

        val AGENT_TOOLS: List<ToolDefinition> = listOf(
            ToolDefinition(
                name = TOOL_REACT,
                description = "在用户消息底角贴单个 Emoji 反应（如 👌 👀 ❤️ 🤔）。",
                parametersJsonSchema = buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "emoji",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "单个 Emoji 字符")
                                },
                            )
                            put(
                                "target_message_id",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "目标消息 id，留空取用户最新消息")
                                },
                            )
                        },
                    )
                    put("required", buildJsonArray { add(JsonPrimitive("emoji")) })
                }.toString(),
            ),
            ToolDefinition(
                name = TOOL_LAUNCH_MISSION,
                description = "派发后台异步任务（多步工具、脚本、文件或虚拟屏）。",
                parametersJsonSchema = buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "goal",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "目标描述")
                                },
                            )
                            put(
                                "acceptance",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "验收标准与交付物")
                                },
                            )
                            put(
                                "resource_scope",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "允许改动的路径范围；只读留空")
                                },
                            )
                            put(
                                "assignee_bot_id",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "委派队友 id；留空由当前执行")
                                },
                            )
                        },
                    )
                    put("required", buildJsonArray { add(JsonPrimitive("goal")) })
                }.toString(),
            ),
            ToolDefinition(
                name = TOOL_STEER_MISSION,
                description = "向运行中的后台任务注入新要求。",
                parametersJsonSchema = buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "task_id",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "运行中任务 id")
                                },
                            )
                            put(
                                "instruction",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "追加指令或变更约束")
                                },
                            )
                        },
                    )
                    put(
                        "required",
                        buildJsonArray {
                            add(JsonPrimitive("task_id"))
                            add(JsonPrimitive("instruction"))
                        },
                    )
                }.toString(),
            ),
            ToolDefinition(
                name = TOOL_OFFER_CHOICES,
                description = "在回复下方挂 2~4 个可点选后续方案卡片。",
                parametersJsonSchema = buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "prompt",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "简短提问")
                                },
                            )
                            put(
                                "options",
                                buildJsonObject {
                                    put("type", "array")
                                    put(
                                        "items",
                                        buildJsonObject {
                                            put("type", "object")
                                            put(
                                                "properties",
                                                buildJsonObject {
                                                    put("label", buildJsonObject { put("type", "string") })
                                                    put("prompt", buildJsonObject { put("type", "string") })
                                                },
                                            )
                                            put("required", buildJsonArray { add(JsonPrimitive("label")) })
                                        },
                                    )
                                },
                            )
                        },
                    )
                    put("required", buildJsonArray { add(JsonPrimitive("options")) })
                }.toString(),
            ),
            ToolDefinition(
                name = TOOL_CREATE_ROUTINE,
                description = "创建可复用自动化惯例（手动/定时/事件触发）。",
                parametersJsonSchema = buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put("title", buildJsonObject { put("type", "string") })
                            put("goal", buildJsonObject { put("type", "string") })
                            put(
                                "trigger_type",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "MANUAL / CRON / NOTIFICATION / CHANNEL")
                                },
                            )
                            put(
                                "trigger_expr",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "Cron（如 0 8 * * *）或触发关键词")
                                },
                            )
                            put("resource_scope", buildJsonObject { put("type", "string") })
                        },
                    )
                    put(
                        "required",
                        buildJsonArray {
                            add(JsonPrimitive("title"))
                            add(JsonPrimitive("goal"))
                        },
                    )
                }.toString(),
            ),
        )
    }
}
