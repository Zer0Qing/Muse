package io.zer0.muse.proactive

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.ProcessWriteGate
import io.zer0.muse.data.ProactiveMessageConfig
import io.zer0.muse.data.assistant.AssistantEntity
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
    )

    /** 准备阶段的产物。任一为 null 说明这轮不该继续。 */
    private data class Prepared(
        val config: ProactiveMessageConfig,
        val assistant: AssistantEntity,
        val sessionId: String,
        val context: ProactiveContextLoader.SessionContext,
        val decision: ProactiveTimingEngine.Decision,
    )

    /**
     * 执行一轮。
     *
     * @param force 手动测试：跳过总开关与四问里的时机限制，但**不越过账本** ——
     *              没有牵挂就没有内容可生成，强行发只会生成空洞的话。
     */
    suspend fun execute(trigger: String, force: Boolean): Outcome =
        when (val prepared = prepare(trigger, force)) {
            is Preparation.Blocked -> prepared.outcome
            is Preparation.Ready -> runPrepared(trigger, prepared.value)
        }

    /** 拿到准备好的材料后，决定说还是不说。 */
    private suspend fun runPrepared(trigger: String, ready: Prepared): Outcome {
        val decision = ready.decision
        if (!decision.shouldSend) {
            val detail = ProactiveTimingEngine.describe(decision)
            Logger.i(TAG, "巡检不发送（$trigger）: $detail")
            return Outcome(decision.verdict, false, hasOpenThoughts(ready), detail)
        }
        return publishResult(decision, ready)
    }

    /** 账本里还有没有没说的。只影响下次复查的间隔，不再是能不能说的前提。 */
    private suspend fun hasOpenThoughts(ready: Prepared): Boolean =
        stores.thoughts.getOpen(ready.assistant.id).isNotEmpty()

    private suspend fun publishResult(
        decision: ProactiveTimingEngine.Decision,
        ready: Prepared,
    ): Outcome = when (val produced = produce(ready)) {
        is ProactiveReplyParser.Result.Failed -> {
            Logger.w(TAG, "生成失败: ${produced.reason}")
            Outcome(decision.verdict, false, hasOpenThoughts(ready), "生成失败：${produced.reason}")
        }
        is ProactiveReplyParser.Result.Skip -> {
            Logger.i(TAG, "模型判断没什么可接的: ${produced.reason}")
            Outcome(
                decision.verdict,
                false,
                hasOpenThoughts(ready),
                "它觉得这会儿没什么可接的（${produced.reason}）",
            )
        }
        is ProactiveReplyParser.Result.Message -> {
            val ok = publish(ready, produced)
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

    private suspend fun prepare(trigger: String, force: Boolean): Preparation {
        val gate = gateBlock(force)
        return gate ?: assemble(trigger)
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
    private suspend fun assemble(trigger: String): Preparation {
        val config = runtime.settings.proactiveMessageConfigFlow.first()
        val now = System.currentTimeMillis()

        // 助手是后面所有步骤的前提，拿不到就没必要继续凑材料
        val assistant = loader.loadAssistant(config.agentId) ?: return blocked("没有可用助手")

        // 例行清理 + 推导新牵挂，全是本地操作，不花钱。
        // 主动消息关着的时候也做：账本照常积累，用户打开时才有东西可说。
        stores.thoughts.maintenance(now)
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
                ),
            ).also { d -> Logger.d(TAG, "判定[$trigger]: ${d.verdict} budget=${d.remainingBudget}") }
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
        val route = ProactiveModelPicker.pick(runtime.settings, ready.assistant)
        return withTimeoutOrNull(COMPOSE_TIMEOUT_MS) {
            GenerationGate.withPermit {
                composer.compose(
                    input = ProactiveComposer.Input(
                        assistant = ready.assistant,
                        candidates = stores.thoughts.getOpen(ready.assistant.id)
                            .sortedByDescending { it.weight },
                        sessionId = ready.sessionId,
                        recentMessages = ready.context.recentMessages,
                        recentProactiveMessages = ready.context.recentProactiveTexts,
                        now = System.currentTimeMillis(),
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

        if (written == null) return false

        // 这次开口可能来自某条牵挂，也可能单纯是接着对话说的。
        // 有就标记，没有也不影响记账 —— 牵挂只是配菜。
        val actual = message.thoughtId
            ?.let { id -> stores.thoughts.getOpen(ready.assistant.id).firstOrNull { it.id == id } }
        actual?.let { stores.thoughts.markSurfaced(it.id, now) }

        stores.relations.consume(ready.assistant.id)
        stores.outcomes.recordSent(
            thought = actual,
            sessionId = ready.sessionId,
            now = now,
            assistantId = ready.assistant.id,
            messageId = written,
        )

        runtime.notifier.notifyProactiveMessage(
            ready.assistant,
            message.content,
            MuseNotificationTarget.Session(ready.sessionId),
        )
        Logger.i(TAG, "已发送: thought=${actual?.id ?: "无(接话)"} tone=${message.tone}")
        return true
    }
}
