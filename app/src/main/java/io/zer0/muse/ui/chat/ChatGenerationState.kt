package io.zer0.muse.ui.chat

import io.zer0.ai.core.UIMessage
import kotlinx.coroutines.channels.Channel
import kotlin.uuid.Uuid

/**
 * v5: 消息发送队列请求 — 串行化处理,防止快速连续发送导致竞态。
 *
 * 乐观更新回滚用:enqueueSend 创建的 user/assistant 消息 id;P0 修复携带完整 userMessage
 * (含原始 id + createdAt),消费端直接复用落盘,避免 consumer 重新 new UIMessage 导致
 * createdAt 取到消费时刻(晚于 assistantMsg.createdAt),切页重载后排序错乱。
 */
internal data class SendRequest(
    val text: String,
    val images: List<String>,
    val sessionId: String,
    val retryCount: Int = 0,
    val userMessage: UIMessage,
    val assistantMessageId: Uuid,
    // v1.0.15: outbox 记录 id(持久化发送队列,进程被杀后恢复用)
    val outboxId: String,
    /** 当前请求的自动任务路由;不写入会话永久模型覆盖,避免路由结果粘住后续消息。 */
    val taskRouteSelection: io.zer0.muse.data.SettingsRepository.TaskRouteSelection? = null,
)

/**
 * v1.x: 生成/发送管线的共享可变状态容器。
 *
 * 承载 sendChannel(发送队列)、outbox 恢复去重集、代际序号/令牌、工具媒体登记、
 * Agent 建会话重入标志。ChatViewModel 通过 getter/setter 委托到本容器,保持既有
 * 引用不变,为把 send/launchStream/消费循环整块迁入 ChatGenerationController 铺路。
 */
internal class ChatGenerationState {
    // 限制容量为 8,防止含 base64 图片的请求无界堆积导致 OOM
    val sendChannel = Channel<SendRequest>(capacity = 8)
    val outboxRecoveryQueuedIds: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    // exec* 工具执行(图片生成可达数十秒)期间若用户发送新消息/切会话,旧工具结果会写进
    // 错误消息(跨会话媒体污染);exec* 入口捕获当前令牌,写媒体前校验令牌未变。
    @Volatile
    var toolGenerationToken: Long = 0L

    // B-24: 流式生成序号 — 每次 launchStream 自增并写入 StreamRunState.generationSerial,
    // 收尾/错误路径清零 isStreaming 前校验"自己仍是最新生成"。
    @Volatile
    var streamGenerationSerial: Long = 0L

    // 全局默认与会话覆盖分开保存；聊天页切换只改当前 session 的覆盖，不反写全局设置。
    @Volatile
    var globalSelectedModelId: String? = null
    @Volatile
    var globalActiveProviderId: String? = null
    @Volatile
    var sessionModelOverrides: Map<String, String> = emptyMap()
    @Volatile
    var sessionProviderOverrides: Map<String, String> = emptyMap()

    // 本代生成中已附加媒体的工具轮消息 id(并发安全),runToolLoop 收尾兜底落盘。
    val toolMediaMessages: MutableSet<Uuid> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    // v1.135: 当前工具调用轮次对应的助手消息 id,供 exec* 媒体工具更新消息媒体字段。
    @Volatile
    var toolAssistantId: Uuid? = null
    // 审计修复 (S-01): toolAssistantId 对应的生成会话 id,与 toolAssistantId 同生命周期。
    @Volatile
    var activeToolSessionId: String? = null

    // v1.79 (M-CV8): 防止 Agent 模式创建会话重入
    @Volatile
    var isCreatingAgentSession: Boolean = false
}
