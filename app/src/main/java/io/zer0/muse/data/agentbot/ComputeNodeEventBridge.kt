package io.zer0.muse.data.agentbot

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.computenode.ComputeNodeEvent
import io.zer0.muse.computenode.ComputeNodeManager
import io.zer0.muse.computenode.OutboxEvent
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.computenode.RemoteComputeNodeDao
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.notification.MuseNotificationTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * 云端电脑事件桥接器。
 *
 * 将 [ComputeNodeManager] 发出的节点重连、恢复上线与离机出箱事件接入 Agent 中枢：
 * - 节点掉线需要修复时，为每个启用的 Agent 绑定写入确定的 [BotCardKind.RECONNECT] 卡；
 * - 节点重新上线时，自动将对应重连卡标记为已解决；
 * - 收到离机 outbox 事件时，整理摘要并通过 [PublicationService.publishIdempotent] 落入对话流，
 *   确认至少一个启用绑定完成发布后才调用 [ComputeNodeManager.acknowledgeOutbox]。
 */
class ComputeNodeEventBridge(
    private val context: Context,
    private val computeNodeManager: ComputeNodeManager,
    private val nodeDao: RemoteComputeNodeDao,
    private val repository: AgentBotRepository,
    private val publication: PublicationService,
    private val settings: SettingsRepository,
    private val notifier: suspend (BotBindingEntity, String) -> Unit = { binding, text ->
        notifyIfBackground(context, settings, binding, text)
    },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private var job: Job? = null

    /** 启动事件监听；幂等，重复调用不会起第二个协程。 */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            computeNodeManager.events.collect { event ->
                try {
                    handleEvent(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "处理云端电脑事件失败: ${e.message}", e)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    internal suspend fun handleEvent(event: ComputeNodeEvent) {
        when (event) {
            is ComputeNodeEvent.ReconnectRequired -> handleReconnectRequired(event)
            is ComputeNodeEvent.Online -> handleOnline(event)
            is ComputeNodeEvent.OutboxReceived -> handleOutboxReceived(event)
            is ComputeNodeEvent.Offline -> Unit
        }
    }

    private suspend fun handleReconnectRequired(event: ComputeNodeEvent.ReconnectRequired) {
        val targets = activeTargets()
        if (targets.isEmpty()) return
        val now = clock()
        val payloadJson = AppJson.encodeToString(ReconnectCardPayload.serializer(), event.payload)
        val title = context.getString(R.string.agent_reconnect_title)
        for (binding in targets) {
            val card = BotMessageCardEntity(
                cardId = reconnectCardId(binding.botId, event.nodeId),
                botId = binding.botId,
                originSessionId = binding.canonicalAgentSessionId,
                taskId = event.nodeId,
                cardKind = BotCardKind.RECONNECT,
                title = title,
                subtitle = event.payload.reason,
                payloadJson = payloadJson,
                status = BotCardStatus.ACTIVE,
                selectedOptionId = "",
                createdAt = now,
                updatedAt = now,
            )
            repository.upsertMessageCard(card)
        }
    }

    private suspend fun handleOnline(event: ComputeNodeEvent.Online) {
        val bindings = repository.enabledBindings()
        for (binding in bindings) {
            repository.resolveMessageCard(
                cardId = reconnectCardId(binding.botId, event.nodeId),
                selectedOptionId = RESOLVED_ONLINE,
            )
        }
    }

    private suspend fun handleOutboxReceived(event: ComputeNodeEvent.OutboxReceived) {
        if (event.events.isEmpty()) return
        val targets = activeTargets()
        if (targets.isEmpty()) return

        val nodeName = runCatching { nodeDao.getById(event.nodeId)?.name.orEmpty() }
            .onFailure { Logger.w(TAG, "读取云端电脑名称失败(${event.nodeId}): ${it.message}", it) }
            .getOrDefault("")
            .ifBlank { event.nodeId }
        val summary = formatOutboxSummary(nodeName, event.events)
        val batchKey = stableBatchKey(event.events)

        var anyCommitted = false
        for (binding in targets) {
            val result = publication.publishIdempotent(
                publicationKey = "node-outbox:${binding.botId}:${event.nodeId}:$batchKey",
                scope = PublicationService.PublicationScope(
                    botId = binding.botId,
                    originSessionId = binding.canonicalAgentSessionId,
                ),
                content = PublicationService.PublishedContent(text = summary),
            )
            if (result.committed > 0) {
                anyCommitted = true
            }
            if (result.created && result.committed > 0) {
                runCatching { notifier(binding, summary) }
                    .onFailure { Logger.w(TAG, "发送离机出箱通知失败: ${it.message}", it) }
            }
        }

        if (!anyCommitted) return
        val ackIds = event.events.mapNotNull { it.eventId.takeIf(String::isNotBlank) }.distinct()
        if (ackIds.isEmpty()) return
        runCatching { computeNodeManager.acknowledgeOutbox(event.nodeId, ackIds) }
            .onFailure { Logger.w(TAG, "确认云端电脑出箱事件失败(${event.nodeId}): ${it.message}", it) }
    }

    private suspend fun activeTargets(): List<BotBindingEntity> =
        repository.enabledBindings().filter { it.canonicalAgentSessionId.isNotBlank() }

    private fun formatOutboxSummary(nodeName: String, events: List<OutboxEvent>): String = buildString {
        appendLine(context.getString(R.string.compute_node_outbox_title, nodeName))
        val emptyDetail = context.getString(R.string.compute_node_outbox_empty_detail)
        for (item in events) {
            val detail = item.outputTail
                .ifBlank { item.output }
                .trim()
                .replace('\r', ' ')
                .replace('\n', ' ')
                .take(MAX_ITEM_DETAIL_CHARS)
                .ifBlank { emptyDetail }
            val line = if (item.watcherId.isNotBlank()) {
                context.getString(R.string.compute_node_outbox_watcher_item, item.watcherId, detail)
            } else {
                val label = item.runId.ifBlank { item.eventId.ifBlank { item.type.ifBlank { "-" } } }
                context.getString(R.string.compute_node_outbox_exec_item, label, detail)
            }
            appendLine(line)
        }
    }.trimEnd()

    private fun stableBatchKey(events: List<OutboxEvent>): String {
        val raw = events.mapIndexed { idx, item ->
            item.eventId.ifBlank { "${item.createdAt}:${item.runId}:${item.watcherId}:$idx" }
        }.sorted().joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "ComputeNodeEventBridge"
        private const val RESOLVED_ONLINE = "online"
        private const val MAX_ITEM_DETAIL_CHARS = 180

        fun reconnectCardId(botId: String, nodeId: String): String =
            "reconnect:node:$botId:$nodeId"

        private suspend fun notifyIfBackground(
            context: Context,
            settings: SettingsRepository,
            binding: BotBindingEntity,
            text: String,
        ) {
            val policy = runCatching { settings.notificationPolicyFlow.first() }
                .onFailure { Logger.w(TAG, "读取通知策略失败，按 when_unfocused 处理: ${it.message}") }
                .getOrDefault("when_unfocused")
            MuseNotificationManager(context).notifyChatCompletedWithPolicy(
                policy = policy,
                sessionTitle = context.getString(R.string.agent_bot_notification_title),
                preview = text,
                target = binding.canonicalAgentSessionId.takeIf { it.isNotBlank() }
                    ?.let { MuseNotificationTarget.Session(it) }
                    ?: MuseNotificationTarget.Chat,
            )
        }
    }
}
