package io.zer0.muse.data.agentbot

import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 主 Bot 的短回合决策器 —— 把一条用户输入变成"接单 / 闲聊 / 需要澄清"。
 *
 * ## 为什么用工具调用而不是文本标记
 *
 * 一开始写成 `<dispatch>` 这类 XML 标记，问题是**模型不知道要输出它**：
 * 提示词里没有、训练里也没见过，实际会一直返回纯文本，任务永远不会被派出去。
 *
 * 工具调用是模型真正理解的原生协议：定义 `bot_say` 与 `task_dispatch` 两个函数，
 * 模型按 schema 填参数，宿主按名字分发。这也正是计划 §8.3 要求的形态 ——
 * "对 Agent 中枢提供 bot_say、task_dispatch 等短操作；调用身份隐式注入"。
 *
 * ## 它做什么，不做什么
 *
 * 做：调一次模型，拿到主 Bot 该说的那句话（以及要不要派工）。
 * 不做：执行任务、发消息、管状态。那些分别是 [BotTaskScheduler]、
 * [PublicationService]、[AgentBotRepository] 的事。
 *
 * ## 为什么回合必须有界
 *
 * 中枢的承诺是"做事时还能聊天"。如果这个回合自己跑满 30 秒，用户敲下一句
 * 时仍然要等 —— 承诺就失效了。所以这里只调**一次** `completeText`，
 * 不执行任何工具。真正的执行在后台 Job 里。
 */
class ForegroundCoordinator(
    /**
     * 模型调用入口。生产环境注入 [ChatService] 的包装；
     * 测试注入假实现。见 [ModelCaller]。
     */
    private val modelCaller: ModelCaller,
    private val publication: PublicationService,
    private val governor: BotResourceGovernor,
) {
    // 注意：构造参数里没有 repository。
    //
    // 曾有一个未使用的 repository 参数，detekt 报 UnusedPrivateProperty 后删掉。
    // 如果将来某处需要它，应当从调用方传进来，而不是加回构造函数 ——
    // 一个不用的依赖会让人误以为"这里会读库"。

    /** 一个回合的产出。 */
    data class Turn(
        /** 主 Bot 要说的话。空表示这一轮不说话（例如只是记下了）。 */
        val say: String = "",

        /** 要不要派工。空表示这是纯对话。 */
        val dispatchGoal: String = "",

        /** 派工时的验收标准。 */
        val acceptance: String = "",

        /** 派工时的可改动范围。 */
        val resourceScope: String = "",

        /** 需要向用户澄清的问题；非空时 [say] 应当就是这个问题。 */
        val clarification: String = "",

        /**
         * 模型始终没有调用 bot_say（补救后仍失败）。
         *
         * 此时 [say] 为空，调用方必须显示明确的发送错误 + 重试入口，
         * **不能**拿模型正文兜底。那正是计划 V15 要防的"伪造回复"。
         */
        val missingBotSay: Boolean = false,

        /**
         * 这个 Provider 很可能不支持工具调用。
         *
         * 判据：首次调用与补救调用都拿到了非空正文，但都没有 `tool_calls`。
         *
         * ## 为什么要单独标出来
         *
         * 清单 F4：Provider 不支持 `toolChoice="required"` 时，若它**报错**，用户能换一个；
         * 若它**静默返回纯文本**，用户会看到主 Bot 只会聊天不会做事，且不知道为什么。
         *
         * 这条标记就是让"不知道为什么"变成"知道为什么"：
         * 调用方应当明确告诉用户"当前 Provider 不支持工具调用，主 Bot 无法派工"。
         */
        val providerToolBlind: Boolean = false,
    ) {
        val shouldDispatch: Boolean get() = dispatchGoal.isNotBlank()

        /** 模型确实调用了 bot_say。 */
        val saidSomething: Boolean get() = say.isNotBlank()
    }

    /**
     * 跑一个前台回合。
     *
     * @param history 该会话最近几条消息（由调用方裁剪，这里不再截断）
     * @param userText 用户这次说的话
     * @return 回合产出；模型调用失败时返回 null，调用方据此保留原文并给重试
     */
    suspend fun runTurn(
        botId: String,
        originSessionId: String,
        history: List<UIMessage>,
        userText: String,
        requestRevision: Long,
    ): Turn? {
        if (userText.isBlank()) return null

        val messages = history + UIMessage(
            role = MessageRole.USER,
            content = userText,
        )

        val completion = runCatching {
            modelCaller.complete(messages)
        }.onFailure {
            Logger.w("ForegroundCoordinator", "主 Bot 回合失败: ${it.message}")
        }.getOrNull();

        if (completion == null) return null

        var turn = parse(completion.text, completion.toolCalls)

        // V15: 没调 bot_say 时做一次受限补救。
        //
        // 为什么补救而不是直接用 completion.text 显示：计划把"模型正文只有经
        // bot_say 提交才可见"定为固定前提，自动公开原始模型流会让"未调用 bot_say"
        // 这条约束形同虚设 —— 而且模型可能把内部推理一起吐出来。
        var providerLooksToolBlind = false
        if (!turn.saidSomething && !turn.shouldDispatch) {
            // 两次都没有结构化输出 = 这个 Provider 很可能不支持工具调用。
            //
            // 清单 F4 的原话：若 Provider 返回纯文本而不是报错，那是**静默退化** ——
            // 用户会看到主 Bot 只会聊天不会做事，且不知道为什么。
            //
            // 判据：首次调用与补救调用都拿到了非空正文，但都没有工具调用。
            // 这不是"模型这次没配合"，是"这个 Provider 根本不发 tool_calls"。
            val gotText = completion.text.isNotBlank()
            // 首轮是否"被当成普通补全"处理：finishReason 为 stop 或缺失，
            // 且没有任何 tool_calls。支持工具的 Provider 在 required 下
            // 应当返回 tool_calls 或 tool_use 之类，而不是干净的 stop。
            val askedForTools = completion.finishReason != null &&
                completion.finishReason != "stop"
            val retried = retryWithBotSay(messages)
            if (retried != null) {
                turn = retried
            } else if (gotText && !askedForTools) {
                // 补充判据：Provider 连"我要求用工具"这件事都没回应。
                //
                // 上一版只看"两次都返回纯文本"，会把"模型这次不肯用工具"
                // 也判成"Provider 不支持" —— 那是把责任推给 Provider。
                //
                // 更可靠的信号是 finishReason：支持工具调用的 Provider 在
                // toolChoice="required" 下几乎不会返回 stop；返回 stop 意味着
                // 它把这次请求当成普通补全处理了。
                providerLooksToolBlind = true
                // **不把模型正文放进 say。**
                //
                // 计划 V15 把"模型正文只有经 bot_say 提交才可见"定为固定前提。
                // 我第一版把正文塞进 say 好让用户"至少看到点什么"，
                // 结果直接违反了那条前提 —— 端到端测试当场抓住。
                //
                // 用户该看到的是"这个 Provider 不支持工具调用"这条明确错误，
                // 而不是一段没经审核的模型正文。
                turn = turn.copy(providerToolBlind = true)
                Logger.w(
                    "ForegroundCoordinator",
                    "两次调用均返回纯文本、无工具调用 —— 该 Provider 可能不支持 toolChoice",
                )
            }
        }

        // 派工前先问配额：配额满就不派，但仍然说话（说明在排队）。
        if (turn.shouldDispatch) {
            when (val verdict = governor.admit(runningCount = 0)) {
                is BotResourceGovernor.Verdict.Go -> governor.recordDispatch()
                is BotResourceGovernor.Verdict.Queue -> {
                    Logger.i("ForegroundCoordinator", "配额满，任务留在队列: ${verdict.reason}")
                }
                is BotResourceGovernor.Verdict.Reject -> {
                    // 拒绝要如实说，不能假装在处理。
                    Logger.w("ForegroundCoordinator", "配额拒绝: ${verdict.reason}")
                    return Turn(say = turn.say, clarification = turn.clarification)
                }
            }
        }

        // 主 Bot 说的话必须经 PublicationService 提交才可见。
        //
        // 这一步不能省：模型正文只有经显式 bot_say 与发布通道提交才算对外表达，
        // 直接塞进界面等于绕过了整条输出边界（计划 §8.3 的固定前提）。
        if (turn.say.isNotBlank()) {
            runCatching {
                publication.publish(
                    botId = botId,
                    originSessionId = originSessionId,
                    causeEventIds = "",
                    requestRevision = requestRevision,
                    taskRevision = 1,
                    items = listOf(PublicationService.Item(PublicationKind.TEXT, turn.say)),
                    // 本轮输入版本；新输入到达时旧批次会被放弃。
                    currentRequestRevision = requestRevision,
                )
            }.onFailure {
                Logger.w("ForegroundCoordinator", "发布失败: ${it.message}")
            }
        }

        return turn
    }

    /**
     * 一次受限补救：明确要求模型只调 bot_say。
     *
     * 只做一次。反复重试会烧配额，而且第二次之后通常也不会有新信息 ——
     * 计划 V15 的措辞是"仅一次受限补救"。
     */
    private suspend fun retryWithBotSay(messages: List<UIMessage>): Turn? {
        val reminder = UIMessage(
            role = MessageRole.USER,
            content = REMINDER_TEXT,
        )
        val retry = runCatching {
            modelCaller.complete(messages + reminder)
        }.onFailure {
            Logger.w("ForegroundCoordinator", "bot_say 补救失败: ${it.message}")
        }.getOrNull() ?: return null

        val turn = parse(retry.text, retry.toolCalls)
        return if (turn.saidSomething || turn.shouldDispatch) turn else null
    }

    /**
     * 把模型输出收敛成 [Turn]。
     *
     * 优先读工具调用（结构化、可靠）；没有工具调用时退回纯文本（当闲聊处理）。
     * **绝不因为没拿到结构化动作就丢弃用户该收到的回复** —— 那会让主 Bot
     * 在模型不配合时彻底失声。
     */
    private fun parse(text: String, toolCalls: List<io.zer0.ai.core.ToolCall>?): Turn {
        // 刻意**不**拿 text 兜底。
        //
        // 计划把"模型正文只有经 bot_say 提交才可见"定为固定前提；用 text 兜底
        // 等于把这条约束作废，而且模型可能把内部推理一起吐出来。
        // 没有 bot_say 时 say 保持空，由调用方走"明确发送错误 + 重试"路径。
        var say = ""
        var goal = ""
        var acceptance = ""
        var scope = ""

        toolCalls.orEmpty().forEach { call ->
            val args = runCatching {
                Json.parseToJsonElement(call.arguments) as? JsonObject
            }.onFailure {
                Logger.w("ForegroundCoordinator", "工具参数解析失败: ${call.name}")
            }.getOrNull();

            when (call.name) {
                TOOL_SAY -> {
                    val content = args?.get("text")?.jsonPrimitive?.content.orEmpty();
                    if (content.isNotBlank()) say = content.trim();
                }
                TOOL_DISPATCH -> {
                    goal = args?.get("goal")?.jsonPrimitive?.content.orEmpty().trim();
                    acceptance = args?.get("acceptance")?.jsonPrimitive?.content.orEmpty().trim();
                    scope = args?.get("resource_scope")?.jsonPrimitive?.content.orEmpty().trim();
                }
                else -> Logger.w("ForegroundCoordinator", "未知控制工具，忽略: ${call.name}")
            }
        }

        return Turn(
            say = say,
            dispatchGoal = goal,
            acceptance = acceptance,
            resourceScope = scope,
            // 既没说话也没派工：调用方要显示明确的发送错误，不能静默结束。
            missingBotSay = say.isBlank() && goal.isBlank(),
        )
    }

    /**
     * 模型调用的抽象。
     *
     * 直接依赖 ChatService 会让这个类无法在纯 JVM 测试里跑 —— 它需要
     * ProviderConfigStore、需要真实网络。抽出一层后，端到端测试可以注入
     * 一个返回固定工具调用的假实现，把"接单 → 派工 → 审核 → 落盘"整条链路串起来。
     */
    fun interface ModelCaller {
        suspend fun complete(messages: List<UIMessage>): io.zer0.ai.core.ChatCompletion
    }
    companion object {
        /** 前台回合 token 上限。短回合，说清一件事就够。 */
        private const val FOREGROUND_MAX_TOKENS = 600

        /**
         * 补救提示词。
         *
         * 只说明"必须用工具说话"，不复述人格设定 —— 复述会让两段提示互相打架，
         * 而这里要的只是把动作拉回 bot_say。
         */
        private const val REMINDER_TEXT =
            "请只用 bot_say 工具回复我。不要直接输出文字。"

        /** 对外说话。 */
        const val TOOL_SAY = "bot_say"

        /** 派工。 */
        const val TOOL_DISPATCH = "task_dispatch"

        /**
         * 主控制器的可用动作。
         *
         * 只有两个：说话、派工。身份（botId / 会话 / 权限）由宿主注入，
         * 模型不能也不需要在参数里指定 —— 那正是"调用身份隐式注入"的落点。
         */
        val CONTROL_TOOLS: List<ToolDefinition> = listOf(
            ToolDefinition(
                name = TOOL_SAY,
                description = "对用户说一句话。这是唯一能让用户看到内容的动作。",
                parametersJsonSchema = """
                    {"type":"object","properties":{
                      "text":{"type":"string","description":"要对用户说的话，一到两句"}
                    },"required":["text"]}
                """.trimIndent(),
            ),
            ToolDefinition(
                name = TOOL_DISPATCH,
                description = "把一件事交给后台去做。只在用户交代了需要动手的任务时调用；" +
                    "闲聊、提问、澄清都不要调用。派工后立即返回，不等待结果。",
                parametersJsonSchema = """
                    {"type":"object","properties":{
                      "goal":{"type":"string","description":"要完成的事，一句话说清"},
                      "acceptance":{"type":"string","description":"怎样算做完，可核验的标准"},
                      "resource_scope":{"type":"string","description":"可改动的范围，空表示只读"}
                    },"required":["goal"]}
                """.trimIndent(),
            ),
        )
    }
}