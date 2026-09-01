package io.zer0.muse.ui.chat

import android.content.Context
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.chat.ConversationTreeSnapshotStore
import io.zer0.muse.schedule.ChatGenerationManager
import io.zer0.muse.session.ConversationSessionManager
import io.zer0.muse.tools.SessionPermissionStore

/**
 * v1.x: 会话编排(createNewSession/switchSession/restartContext 等)的依赖 bundle。
 *
 * 收口会话生命周期切换所需的共享状态/服务 + 跨职责回调,供 ChatSessionController
 * 在不反向依赖 ChatViewModel 的情况下执行会话创建/切换/重启。ChatViewModel 私有
 * 的 stopTts/disposeAsr/notifySessionEnd/currentSessionIdForApproval 经回调注入。
 */
@Suppress("LongParameterList")
internal class SessionDeps(
    val stateStore: ChatStateStore,
    val settings: SettingsRepository,
    val assistantRepository: AssistantRepository,
    val sessionPermissionStore: SessionPermissionStore,
    val sessionManager: ConversationSessionManager,
    val appContext: Context,
    val onStopTts: () -> Unit,
    val onDisposeAsr: () -> Unit,
    val onNotifySessionEnd: () -> Unit,
    val currentSessionIdForApproval: () -> String?,
    val globalActiveProviderId: () -> String?,
    val globalSelectedModelId: () -> String?,
    val onSend: () -> Unit,
    val messageController: ChatMessageController,
    val chatGenerationManager: ChatGenerationManager,
    val onClearDelegation: () -> Unit,
    val treeSnapshotStore: ConversationTreeSnapshotStore?,
    val restorePendingApprovalsForSession: (sessionId: String) -> Unit,
    val activeProviderForSession: (sessionId: String?) -> String?,
    val selectedModelForSession: (sessionId: String?) -> String?,
    val onSessionSwitched: (sessionId: String) -> Unit,
    val requeueOutboxForSession: suspend (sessionId: String) -> Unit,
)
