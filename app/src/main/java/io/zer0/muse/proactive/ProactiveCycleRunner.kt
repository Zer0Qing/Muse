package io.zer0.muse.proactive

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.ProcessWriteGate
import io.zer0.muse.data.ProactiveMessageConfig
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.proactive.ProactiveRelationEntity
import io.zer0.muse.data.proactive.ProactiveTimingEngine
import io.zer0.muse.notification.MuseNotificationTarget
import io.zer0.muse.schedule.GenerationGate
import io.zer0.muse.schedule.readScheduleWorkEnabledOrFalse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 跑一轮主动消息巡检。
 *
 * 形状是「准备 → 判定 → 生成 → 发布」，四步各自成函数：
 *
 * ```
 * prepare  总控 / 开关 / 助手 / 关系 / 清理 / 记账   —— 全本地，零成本
 * decide   四问判定（见 ProactiveTimingEngine）
 * produce  一次 LLM 调用，允许 skip
 * publish  落库 → 标记 → 扣额度 → 记结果 → 通知
 * ```
 *
 * 只有 produce 花钱。所以「账本为空」时一次巡检的成本接近为零 ——
 * 这是与旧实现最大的差别：旧实现每次轮询都可能触发两次 LLM 调用。
 */
class ProactiveCycleRunner(
    private val stores: ProactiveStores,
    private val runtime: ProactiveRuntime,
    private val loader: ProactiveContextLoader,
    private val composer: ProactiveComposer,
    private val guard: ProactiveCycleGuard,
    /**
     * 发送概率闸的随机源，返回 `0..99`。
     *
     * 默认真随机，注入是为了让「概率」这件事可测：测试给固定值就能确定性地
     * 走发送/不发送两条分支，而不必去追求一个统计上的伪断言。
     */
    private val rollPercent: () -> Int = { kotlin.random.Random.nextInt(ProactiveSendProbability.ROLL_BOUND) },
) {

    companion object {
        private const val TAG = "ProactiveCycle"

        /** 生成调用的超时上限。 */
        private const val COMPOSE_TIMEOUT_MS = 60_000L
    }

    /** 一轮巡检的结论。 */
    data class Outcome(
        val verdict: ProactiveTimingEngine.Verdict?,
        val sent: Boolean,
        /** 账本里是否还有未处理的事，决定下次复查的间隔。 */
        val ledgerNotEmpty: Boolean,
        val detail: String,
        /**
         * 本次触发是否因为「已经有一轮在跑」而直接跳过。
         *
         * 单独成字段而不是让调用方去比对 [detail]：界面上要显示的是本地化文案，
         * 而 [detail] 是给日志看的中文短语，两者不该共用一份字符串。
         */
        val alreadyRunning: Boolean = false,
    )

    /** 此刻是否已经有一轮巡检在跑。供调度器与 Worker 做廉价预判。 */
    val isInFlight: Boolean get() = guard.isInFlight

    /** 准备阶段的产物。任一为 null 说明这轮不该继续。 */
    private data class Prepared(
        val config: ProactiveMessageConfig,
        val assistant: AssistantEntity,
        val sessionId: String,
        val context: ProactiveContextLoader.SessionContext,
        val decision: ProactiveTimingEngine.Decision,
        /**
         * 关系状态。主动等级与额度都在里面，生成阶段靠它决定这次开口的口径。
         *
         * 不能只传 [decision]：判定结果里只有「能不能发」，而「能发的话能说什么」
         * 是另一个问题，由用户选的等级决定。
         */
        val relation: ProactiveRelationEntity,
    )

    /**
     * 执行一轮。
     *
     * 所有触发源都必须从这里进 —— 单飞闸在这一层，不在各自的调用点上。
     * 放在调用点上意味着每加一路触发就要记得补一次判断，而漏掉的那一路
     * 恰恰是以后加的，等到有人发现重复发送时已经很难回溯是谁绕过了闸。
     *
     * @param force 手动测试：跳过总开关与四问里的时机限制，但**不越过账本** ——
     *              没有牵挂就没有内容可生成，强行发只会生成空洞的话。
     */
    suspend fun execute(trigger: String, force: Boolean): Outcome {
        val entry = guard.tryEnter(System.currentTimeMillis())
            ?: return skipped(trigger)
        var sent = false
        return try {
            run(trigger, force, entry).also { sent = it.sent }
        } finally {
            guard.leave(entry, sent)
        }
    }

    /** 已有一轮在跑时本轮的去向。记一条日志，说明为什么这一路什么都没做。 */
    private fun skipped(trigger: String): Outcome {
        val detail = "上一轮巡检还在进行（$trigger），本次跳过"
        Logger.i(TAG, detail)
        return Outcome(
            verdict = null,
            sent = false,
            ledgerNotEmpty = false,
            detail = detail,
            alreadyRunning = true,
        )
    }

    private suspend fun run(trigger: String, force: Boolean, entry: ProactiveCycleGuard.Entry): Outcome =
        when (val prepared = prepare(trigger, force, entry)) {
            is Preparation.Blocked -> prepared.outcome
            is Preparation.Ready -> runPrepared(trigger, prepared.value, force)
        }

    /** 拿到准备好的材料后，决定说还是不说。 */
    private suspend fun runPrepared(trigger: String, ready: Prepared, force: Boolean): Outcome {
        val decision = ready.decision
        if (!decision.shouldSend) {
            val detail = ProactiveTimingEngine.describe(decision)
            Logger.i(
                TAG,
                "巡检不发送（$trigger）: $detail | verdict=${decision.verdict} | " +
                    "interval=${decision.effectiveIntervalMinutes}min | ${skipContext(ready)}",
            )
            return Outcome(decision.verdict, false, hasOpenThoughts(ready), detail)
        }
        // 发送概率：所有硬闸都过了才掷骰子。放在生成之前，
        // 被概率放弃的这一轮就不该再花一次模型调用。
        // 手动测试（force）不走这里：那是排查工具，不是日常节奏。
        if (!force && !ProactiveSendProbability.allows(ready.config.sendProbability, rollPercent())) {
            val detail = "按发送概率跳过（${ready.config.sendProbability}%）"
            Logger.i(TAG, "巡检不发送（$trigger）: $detail | verdict=${decision.verdict}")
            return Outcome(decision.verdict, false, hasOpenThoughts(ready), detail)
        }
        return publishResult(trigger, decision, ready)
    }

    /**
     * 跳过一次巡检时的结构化现场。
     *
     * 光有一句「模型觉得没什么可接的」说明不了问题 —— 它看不出来到底是
     * 「刚发过所以没话说」还是「上一条发出去根本没人理，模型还在对着空气接话」。
     * 所以把模型做判断时手上的两个外部事实一并记下来：上一条主动消息距今多久、
     * 用户读到哪了。两者都与会话侧的最新状态对齐，不参与任何判定。
     */
    private suspend fun skipContext(ready: Prepared): String {
        val now = System.currentTimeMillis()
        val lastOutcome = runCatching { stores.outcomes.getRecent(ready.assistant.id, 1).firstOrNull() }
            .getOrNull()
        val minutes = lastOutcome?.let { (now - it.sentAt).coerceAtLeast(0L) / 60_000L }
            ?.toString() ?: "无"
        val readState = runCatching { runtime.sessions.getSessionById(ready.sessionId) }
            .getOrNull()
            ?.let { session ->
                val unread = (session.messageCount - session.lastReadCount).coerceAtLeast(0)
                "unread=$unread,lastRead=${session.lastReadCount}/${session.messageCount}"
            } ?: "读取失败"
        val outcomeState = lastOutcome?.state ?: "无"
        return "距上条主动消息=${minutes}分钟 | 用户已读状态=$readState | 上一条主动消息=$outcomeState"
    }

    /** 账本里还有没有没说的。只影响下次复查的间隔，不再是能不能说的前提。 */
    private suspend fun hasOpenThoughts(ready: Prepared): Boolean =
        stores.thoughts.getOpen(ready.assistant.id).isNotEmpty()

    private suspend fun publishResult(
        trigger: String,
        decision: ProactiveTimingEngine.Decision,
        ready: Prepared,
    ): Outcome = when (val produced = produce(ready)) {
        is ProactiveReplyParser.Result.Failed -> {
            Logger.w(TAG, "生成失败: ${produced.reason}")
            Outcome(decision.verdict, false, hasOpenThoughts(ready), "生成失败：${produced.reason}")
        }
        is ProactiveReplyParser.Result.Skip -> {
            // 这是模型自己的判断，它看到的是对话，看不到自己上一条有没有被读。
            // 把外部事实一起记上，才能分清「没东西可说」和「说了没人应」。
            Logger.i(TAG, "判定跳过 | reason=${produced.reason} | ${skipContext(ready)}")
            Outcome(
                decision.verdict,
                false,
                hasOpenThoughts(ready),
                "它觉得这会儿没什么可接的（${produced.reason}）",
            )
        }
        is ProactiveReplyParser.Result.Message -> {
            Logger.i(
                TAG,
                "主动消息候选生成 | assistantId=${ready.assistant.id} | len=${produced.content.length} | trigger=$trigger",
            )
            val ok = publish(trigger, ready, produced)
            Outcome(
                decision.verdict,
                ok,
                hasOpenThoughts(ready),
                if (ok) "已发送" else "落库失败，下次再试",
            )
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 准备
    // ══════════════════════════════════════════════════════════════

    private sealed interface Preparation {
        data class Ready(val value: Prepared) : Preparation
        data class Blocked(val outcome: Outcome) : Preparation
    }

    private suspend fun prepare(
        trigger: String,
        force: Boolean,
        entry: ProactiveCycleGuard.Entry,
    ): Preparation {
        val gate = gateBlock(force)
        return gate ?: assemble(trigger, entry)
    }

    /**
     * 前三道闸：恢复中 / 总控关闭 / 主动消息未开启。
     *
     * 单独成函数是因为这三条都是「根本不往下走」的硬条件，
     * 而下面的组装逻辑是「往下走但可能拿不到材料」，两类失败混在一起读不清。
     */
    private suspend fun gateBlock(force: Boolean): Preparation? = when {
        ProcessWriteGate.restoring -> blocked("备份恢复中，跳过")
        !readScheduleWorkEnabledOrFalse { runtime.settings.scheduleWorkEnabledFlow.first() } ->
            blocked("后台调度总控已关闭")
        !force && !runtime.settings.proactiveMessageConfigFlow.first().enabled ->
            blocked("主动消息未开启")
        else -> null
    }

    /** 把一轮巡检要的材料凑齐。拿不到的就降级为一次 Blocked。 */
    private suspend fun assemble(trigger: String, entry: ProactiveCycleGuard.Entry): Preparation {
        val config = runtime.settings.proactiveMessageConfigFlow.first()
        val now = System.currentTimeMillis()

        // 助手是后面所有步骤的前提，拿不到就没必要继续凑材料
        val assistant = loader.loadAssistant(config.agentId) ?: return blocked("没有可用助手")

        // 例行清理 + 推导新牵挂，全是本地操作，不花钱。
        // 主动消息关着的时候也做：账本照常积累，用户打开时才有东西可说。
        stores.thoughts.maintenance(now)
        // 发出去一天还没人理的，就在这轮收尾成 EXPIRED。放在巡检里而不是新起定时器：
        // 巡检本来就是这条链路上唯一会定期跑的本地活儿，多一个 Worker 就多一处
        // 「什么时候跑、跑在哪个进程」要维护。
        val expired = stores.outcomes.expireOverdue(now)
        if (expired > 0) Logger.i(TAG, "主动消息过期收尾 | 条数=$expired")
        loader.deriveThoughts(assistant.id, now)

        val relation = stores.relations.getOrCreate(assistant.id, now)
        val sessionId = loader.resolveSession(assistant)
        val context = sessionId?.let { loader.loadSessionContext(it, assistant.id) }
        val decision = context?.let {
            ProactiveTimingEngine.evaluate(
                ProactiveTimingEngine.Input(
                    now = now,
                    relation = relation,
                    allowedHourStart = config.allowedHourStart,
                    allowedHourEnd = config.allowedHourEnd,
                    allDayAllowed = config.allDayAllowed,
                    lastUserMessageAt = it.lastUserMessageAt,
                    isGenerating = runtime.generations.activeGenerations.value.isNotEmpty(),
                    lastProactiveSentAt = loader.lastSentAt(assistant.id),
                    // 本轮的尝试时间已经登记在闸上，但判定要问的是「上一轮」什么时候跑的：
                    // 拿本轮自己的时间戳去判，每一轮都会被自己拦下。
                    lastProactiveAttemptAt = entry.previousAttemptAt,
                    // 间隔与抖动都来自用户设置；助手 id 参与哈希，
                    // 让不同助手的偏移不在同一个方向上叠加。
                    pacing = ProactiveTimingEngine.Pacing(
                        intervalMinutes = config.intervalMinutes,
                        randomOffsetMinutes = config.randomOffsetMinutes,
                        assistantId = assistant.id,
                    ),
                ),
            ).also { d ->
                Logger.d(
                    TAG,
                    "判定[$trigger]: ${d.verdict} interval=${d.effectiveIntervalMinutes}min " +
                        "budget=${d.remainingBudget}",
                )
            }
        }

        return when {
            // 账本为空不再阻断：素材是最近对话，没有牵挂照样能接话。
            sessionId == null || context == null || decision == null -> blocked("没有可用会话")
            else -> Preparation.Ready(
                Prepared(
                    config = config,
                    assistant = assistant,
                    sessionId = sessionId,
                    context = context,
                    decision = decision,
                    relation = relation,
                ),
            )
        }
    }

    private fun blocked(
        detail: String,
        verdict: ProactiveTimingEngine.Verdict? = null,
        ledgerNotEmpty: Boolean = false,
    ): Preparation = Preparation.Blocked(Outcome(verdict, false, ledgerNotEmpty, detail))

    // ══════════════════════════════════════════════════════════════
    // 生成
    // ══════════════════════════════════════════════════════════════

    private suspend fun produce(ready: Prepared): ProactiveReplyParser.Result {
        val intensity = ready.relation.intensityEnum()

        // 候选按主动等级过滤，而不是全部给模型：安静档只允许「该说的事」，
        // 偶尔被想到不允许分享自己的事与关系节点。把本档位不该说的类型列进提示词，
        // 等于给模型递了一个犯规的选项 —— 它可能就用了，然后用户发现「选了安静还在闲聊」。
        // 用户反复划掉的类型同理：判定依据是结果账，样本门槛在 [ProactiveLearning]。
        val ledger = stores.thoughts.getOpen(ready.assistant.id)
        val banned = stores.outcomes.unpopularKinds(
            ready.assistant.id,
            ledger.mapTo(mutableSetOf()) { it.kindEnum() },
        )
        val candidates = ProactiveCandidateFilter.select(ledger, intensity, banned)
        if (banned.isNotEmpty()) {
            Logger.i(TAG, "候选过滤 | 已不感兴趣的类型=${banned.joinToString { it.name }} | 剩=${candidates.size}")
        }

        // 安静档：没有具体事由就不开口。连模型都不调，
        // 既省一次调用，也避免它在「只允许说事」的约束下硬凑出一句寒暄。
        if (intensity.onlyWhenNeeded && candidates.isEmpty()) {
            return ProactiveReplyParser.Result.Skip("安静档：账本里没有具体的事")
        }

        val route = ProactiveModelPicker.pick(runtime.settings, ready.assistant)
        // 先登记再调模型：结果账要等消息落库才写，而这次调用最长要几十秒。
        // 不登记的话，这段时间里巡检在数据层看起来「什么都没发生过」。
        guard.markAttempt(System.currentTimeMillis())
        return withTimeoutOrNull(COMPOSE_TIMEOUT_MS) {
            GenerationGate.withPermit {
                composer.compose(
                    input = ProactiveComposer.Input(
                        assistant = ready.assistant,
                        candidates = candidates,
                        sessionId = ready.sessionId,
                        recentMessages = ready.context.recentMessages,
                        recentProactiveMessages = ready.context.recentProactiveTexts,
                        now = System.currentTimeMillis(),
                        intensity = intensity,
                    ),
                    model = route?.second,
                    providerConfig = route?.first,
                    temperature = ready.config.temperature,
                )
            }
        } ?: ProactiveReplyParser.Result.Failed("生成超时")
    }

    // ══════════════════════════════════════════════════════════════
    // 发布
    // ══════════════════════════════════════════════════════════════

    /**
     * 落库 + 记账 + 通知。
     *
     * 顺序有讲究：**先落库，成功了才扣额度、标记已说**。
     * 落库失败时什么都不动，这轮当作没发生，下次重试 ——
     * 宁可重试也不丢消息，更不能扣了额度却没发出去。
     */
    private suspend fun publish(
        trigger: String,
        ready: Prepared,
        message: ProactiveReplyParser.Result.Message,
    ): Boolean {
        val now = System.currentTimeMillis()
        val written = runCatching {
            runtime.sessions.appendMessage(
                sessionId = ready.sessionId,
                message = UIMessage(
                    role = MessageRole.ASSISTANT,
                    content = message.content,
                    createdAt = now,
                ),
            )
        }.onFailure { e -> Logger.e(TAG, "落库失败，本次放弃: ${e.message}", e) }.getOrNull()

        if (written == null) {
            Logger.i(TAG, "主动消息落库 | sessionId=${ready.sessionId} | messageId=null(写入失败) | trigger=$trigger")
            return false
        }

        // 主动消息写的是「该助手最近的会话」，而用户可能正看着另一个会话。
        // 两个 id 同时记上，才能在用户说「日志里有消息但我没看到」时区分
        // 「写错了房间」和「写对了但当前页面不是那个房间」。
        val currentSessionId = runCatching { runtime.settings.getViewedSessionId() }.getOrNull()
        Logger.i(
            TAG,
            "主动消息落库 | sessionId=${ready.sessionId} | messageId=$written | " +
                "targetSessionId=${ready.sessionId} | currentSessionId=${currentSessionId ?: "无"}",
        )

        // 这次开口可能来自某条牵挂，也可能单纯是接着对话说的。
        // 有就标记，没有也不影响记账 —— 牵挂只是配菜。
        val actual = message.thoughtId
            ?.let { id -> stores.thoughts.getOpen(ready.assistant.id).firstOrNull { it.id == id } }
        actual?.let { stores.thoughts.markSurfaced(it.id, now) }

        stores.relations.consume(ready.assistant.id)
        // 结果账的 id 要一直带到通知上：「这次不用」按钮靠它回到同一条记录。
        val outcomeId = stores.outcomes.recordSent(
            thought = actual,
            sessionId = ready.sessionId,
            now = now,
            assistantId = ready.assistant.id,
            messageId = written,
        )

        notifyProactive(trigger, ready, message, currentSessionId, outcomeId)
        Logger.i(TAG, "已发送: thought=${actual?.id ?: "无(接话)"} tone=${message.tone}")
        return true
    }

    /**
     * 发通知，并把「到底送出去了没有」写进日志。
     *
     * 旧实现这里只有调用、没有任何回执：[MuseNotificationManager.notifyProactiveMessage]
     * 内部把异常吞了，所以「消息落库了但通知栏什么都没有」在日志里看不到任何痕迹。
     * 这里补两件事：调用本身有没有抛，以及系统层面会不会把这条拦下来（权限/渠道被关）。
     */
    private suspend fun notifyProactive(
        trigger: String,
        ready: Prepared,
        message: ProactiveReplyParser.Result.Message,
        currentSessionId: String?,
        outcomeId: String,
    ) {
        val blocker: String? = runCatching { runtime.notifier.proactiveNotificationBlocker() }.getOrNull()
        val failure = runCatching {
            runtime.notifier.notifyProactiveMessage(
                assistant = ready.assistant,
                preview = message.content,
                target = MuseNotificationTarget.Session(ready.sessionId),
                outcomeId = outcomeId,
            )
        }.exceptionOrNull()
        val posted = failure == null && blocker == null
        val reason = when {
            failure != null -> failure.message ?: failure::class.java.simpleName
            blocker != null -> blocker
            else -> "无"
        }
        Logger.i(
            TAG,
            "主动消息通知 | posted=$posted | reason=$reason | targetSessionId=${ready.sessionId} | " +
                "currentSessionId=${currentSessionId ?: "无"} | trigger=$trigger",
        )
    }
}
