package io.zer0.muse.ui.chat
import io.zer0.muse.chat.PendingToolCallStore
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.session.ConversationSessionManager
import io.zer0.muse.tools.SessionPermissionStore
import io.zer0.muse.tools.ToolApprovalState
import io.zer0.muse.ui.PendingToolApproval
import kotlinx.serialization.json.Json

/**
 * v2.x 重构 S4/S5: [ChatToolApprovalCoordinator] 与 [ChatToolResumeCoordinator]
 * 访问 ChatViewModel 的窄桥接口(审批与恢复同域,共用一个桥)。
 *
 * 迁移性质:纯抽取。VM 侧以匿名对象实现,全部为**直接转发**,无逻辑。
 */
internal interface ChatToolApprovalHostBridge {

    // ── 依赖 ───────────────────────────────────────────────────────────

    val stateStore: ChatStateStore
    val appContext: android.content.Context
    val settings: SettingsRepository
    val sessionPermissionStore: SessionPermissionStore
    val toolConfigStore: io.zer0.muse.tools.ToolConfigStore?
    val toolRegistry: io.zer0.muse.tools.ToolRegistry
    val notificationManager: MuseNotificationManager
    val sessionManager: ConversationSessionManager
    val skillRepository: SkillRepository

    /** 原 VM 的 idListJson 解析器。 */
    val idListJson: Json
    val coroutineScope: kotlinx.coroutines.CoroutineScope

    // ── 审批状态容器(原 VM 的可变 Map,状态归属本次不动)──────────────

    val toolApprovalResults: MutableMap<String, kotlinx.coroutines.CompletableDeferred<ToolApprovalState>>
    val toolApprovalSessions: MutableMap<String, String>
    val pendingToolApprovalRecords: MutableMap<String, PendingToolApproval>

    /** 原 `approvalTimeoutPaused`(读)。 */
    val approvalTimeoutPaused: Boolean

    // ── 方法转发 ───────────────────────────────────────────────────────

    fun addError(type: io.zer0.muse.ui.ChatErrorType, message: String, isRecoverable: Boolean = true)
    val skillExecutor: io.zer0.muse.tools.SkillExecutor
    val routeGuard: io.zer0.muse.tools.ToolRouteExecutionGuard
    suspend fun recheckApprovalForResume(chatId: String, pending: PendingToolCallStore.PendingToolCall): ToolApprovalState?
    val sessionRepository: io.zer0.muse.data.session.SessionRepository
    fun isToolResultSuccess(result: String): Boolean
    fun launchStream(assistantId: kotlin.uuid.Uuid, sessionId: String, isNewBranch: Boolean = false)
    fun currentSessionIdForApproval(): String?
}
