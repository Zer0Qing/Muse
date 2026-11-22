package io.zer0.muse.data.agentbot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import android.content.Context
import io.zer0.muse.ui.chat.ChatUiState

/**
 * 中枢页面向用户显示的错误。
 *
 * ## 为什么是枚举而不是字符串
 *
 * 状态层不持有 Context，直接拼中文会让 7 语言下漏翻（硬编码中文门禁也会拦）。
 * 这里只表达"发生了什么"，文案由 Compose 侧用 stringResource 解析 ——
 * 同一个状态在任何语言下都有对应的话。
 */
enum class AgentBotError {
    /** 还没有可用的会话（宿主没绑定）。 */
    NO_SESSION,

    /** 消息落盘失败。内容已留在输入框。 */
    SAVE_FAILED,

    /** 这一步已经被结算过（双击 / 并发）。 */
    ALREADY_DECIDED,

    /** 审批已超时。**不是**用户拒绝。 */
    APPROVAL_EXPIRED,

    /** 审批参数或资源已变更，旧批准失效。 */
    APPROVAL_STALE,

    /**
     * 模型没有按约定调用 bot_say，回复没能发出。
     *
     * 这不是"模型答得不好"，是**这一轮没有产生任何对外内容**。
     * 必须如实告诉用户并给重试入口，不能拿模型正文兜底。
     */
    MISSING_BOT_SAY,

    /**
     * 当前 Provider 很可能不支持工具调用。
     *
     * 判据：两次调用都返回纯文本、都没有 tool_calls。
     *
     * 必须明确告知：不告知的话，用户只会看到主 Bot 聊得很正常但从不做事，
     * 而原因（换一个 Provider 就能好）完全无从得知。
     */
    PROVIDER_TOOL_BLIND,
}

/**
 * 任务行上"需要用户做什么"的提示。
 *
 * 与 [AgentBotError] 同理由：状态层只给语义，文案在 UI 侧解析。
 */
enum class AgentBotPrompt {
    /** 需要用户决定怎么继续。 */
    /**
     * 审批已通过，等用户确认是否重跑整件事。
     *
     * 与 [NEEDS_DECISION] 分开：那个是"执行出问题了，你看怎么办"，
     * 这个是"可以继续了，但要先告诉你重跑会重复已完成的步骤"。
     * 混成一句会让用户不知道该点"继续"还是该重试。
     */
    APPROVED_AWAITING_RETRY,

    NEEDS_DECISION,

    /** 结果无法确认，请核验。 */
    VERIFY_RESULT,
}

/**
 * Agent 中枢的分片 UI 状态。
 *
 * ## 为什么不用 ChatUiState
 *
 * 现有 ChatUiState 是普通 class（无 equals）、83 个字段、copy() 展开成 123 个参数，
 * MutableStateFlow 因此无法合并 —— 每 12 个字符的流式输出都会重建整个聊天页。
 * 中枢的核心承诺是"做事时还能继续聊天"，若沿用那套状态模型，短回合省下的时间
 * 会被整页重建全部吃掉。
 *
 * 这里按域拆成四个 data class，各自是值语义、各自一个 StateFlow：
 *  - [Composer]：用户正在打的字与待发附件
 *  - [Transcript]：已确认的对外消息（只含已 commit 的）
 *  - [TaskBoard]：在途任务与待审批
 *  - [Busy]：真实发布状态（typing / 发送中 / 失败）
 *
 * 四个域各自订阅，任务进度变化不会让输入框重组。
 */
class AgentBotUiState {

    /** 输入区。用户敲字只动这里。 */
    data class Composer(
        val text: String = "",
        val pendingImages: List<String> = emptyList(),
        /** 这条输入属于哪个 Bot；切换 Bot 时草稿不能串。 */
        val botId: String = "",
    )

    /** 一条已发布的对外消息。只从 PublicationOutbox 的 COMMITTED 状态来。 */
    data class PublishedMessage(
        val messageId: String,
        val role: Role,
        val text: String,
        val createdAt: Long,
        /** 这条消息属于哪次 bot_say 批次；用于"部分发布恢复"。 */
        val publicationId: String = "",
    )

    enum class Role { USER, BOT }

    /** 任务看板上的一行。只放用户需要知道的部分，不放子 Agent 与工具流水。 */
    data class TaskRow(
        val taskId: String,
        val goal: String,
        val status: String,
        /** 一句话进展（来自审核或关键进度事件），空表示还没有可说的。 */
        val note: String = "",
        /** 需要用户决定时的提示；非空时这一行必须可见。 */
        val needsUser: AgentBotPrompt? = null,
        /**
         * 这一行允许哪些操作。
         *
         * ## 为什么不用 status 推断
         *
         * NEEDS_USER 有两个来源（审批通过 / 审核要决策），该给的操作完全不同：
         * 前者"可以重跑"，后者"要用户处理"。靠 status 猜只能猜对一半 ——
         * 猜错就会在"需要你处理"的行上放一个"继续重跑"按钮，用户点了才发现不对。
         *
         * 所以由构造方（ViewModel，它知道事件来源）显式给出。
         */
        val actions: Set<TaskAction> = emptySet(),
    )

    /** 任务行上可用的操作。 */
    enum class TaskAction {
        /** 取消这个任务。 */
        CANCEL,

        /**
         * 用户确认重跑。
         *
         * 只在"审批已通过、任务停住等确认"时出现。重跑会重复已执行的步骤，
         * 所以必须由用户显式同意。
         */
        CONTINUE,
    }

    /** 待审批的一行。 */
    data class ApprovalRow(
        val approvalId: String,
        val taskId: String,
        val toolName: String,
        val risk: String,
        /** 全量参数摘要；卡片与执行必须同源。 */
        val argumentsPreview: String,
        /**
         * 建卡时的参数哈希。
         *
         * 批准时必须原样回传 —— 服务端拿它和当前参数比对，不一致判 INVALIDATED。
         * 此前界面传的是空串，于是**每一次批准都被判失效**，
         * 任务永久停在 WAITING_APPROVAL，用户只看到"参数已变更"。
         */
        val argumentsHash: String,
        /**
         * 建卡时的资源版本。
         *
         * 与 [argumentsHash] 同理：资源在这期间变了，旧批准不该生效。
         */
        val resourceVersion: String,
        /** 过期时刻。卡片据此显示倒计时，过期不伪造用户拒绝。 */
        val expiresAt: Long,
    )

    /** 暂存区里的一条候选（V04 的审计入口）。 */
    data class CandidateRow(
        val stagedId: String,
        /** 它想改的目标。 */
        val targetRef: String,
        /** 产出时对应的任务版本。 */
        val taskRevision: Int,
    )

    data class TaskBoard(
        val tasks: List<TaskRow> = emptyList(),
        val approvals: List<ApprovalRow> = emptyList(),
    )

    /**
     * 真实发布状态。
     *
     * 后台任务独自执行时 [typing] 必须为 false —— 让主对话永久显示"正在输入"
     * 是最容易被用户当成卡死的假象。
     */
    data class Busy(
        val typing: Boolean = false,
        val sending: Boolean = false,
        /** 宿主侧发送错误；非空时界面给重试入口，不用私有文本兜底。 */
        val error: AgentBotError? = null,
    )

    // ── 四个独立流 ────────────────────────────────────────────────────

    private val _composer = MutableStateFlow(Composer())
    val composer: StateFlow<Composer> = _composer.asStateFlow()

    private val _transcript = MutableStateFlow<List<PublishedMessage>>(emptyList())
    val transcript: StateFlow<List<PublishedMessage>> = _transcript.asStateFlow()

    private val _taskBoard = MutableStateFlow(TaskBoard())
    val taskBoard: StateFlow<TaskBoard> = _taskBoard.asStateFlow()

    private val _busy = MutableStateFlow(Busy())
    val busy: StateFlow<Busy> = _busy.asStateFlow()

    // ── 写入口（全部是值语义，StateFlow 可合并）────────────────────────

    fun setComposerText(text: String) = _composer.update { it.copy(text = text) }

    fun setComposerBot(botId: String) = _composer.update {
        // 换 Bot 时草稿不串：上一个 Bot 的草稿属于上一个 Bot。
        if (it.botId == botId) it else it.copy(botId = botId, text = "", pendingImages = emptyList())
    }

    fun appendPublished(message: PublishedMessage) = _transcript.update { current ->
        // 同 messageId 重复投递只留一条：恢复时可能重投已提交的 item。
        if (current.any { it.messageId == message.messageId }) current else current + message
    }

    fun replacePublished(messages: List<PublishedMessage>) = _transcript.update { messages }

    fun setTaskBoard(board: TaskBoard) = _taskBoard.update { board }

    fun setTyping(typing: Boolean) = _busy.update { it.copy(typing = typing) }

    fun setSending(sending: Boolean) = _busy.update { it.copy(sending = sending) }

    fun setError(error: AgentBotError?) = _busy.update { it.copy(error = error) }
}
