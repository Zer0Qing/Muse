package io.zer0.muse.computenode

import android.database.SQLException
import io.zer0.common.Logger
import io.zer0.muse.data.agentbot.ReconnectActionKind
import io.zer0.muse.data.agentbot.ReconnectCardPayload
import io.zer0.muse.data.computenode.ComputeNodeStatus
import io.zer0.muse.data.computenode.RemoteComputeNodeDao
import io.zer0.muse.data.computenode.RemoteComputeNodeEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 云电脑节点生命周期管理器。
 *
 * 按固定心跳轮询所有已存节点：在线状态写回 RemoteComputeNodeDao，掉线时向流内
 * 发射 ReconnectCard 事件；节点重新上线时自动补拉离线期间的 outbox 事件。
 * 所有网络调用都经 [MuseNodeClient]，因此 SSH 隧道与证书钉扎规则只有一处。
 */
class ComputeNodeManager(
    private val dao: RemoteComputeNodeDao,
    private val client: MuseNodeClient,
    private val scope: CoroutineScope,
    private val heartbeatIntervalMs: Long = DEFAULT_HEARTBEAT_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

    private val mutableEvents = MutableSharedFlow<ComputeNodeEvent>(
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 节点在线/离线、出箱事件与重连卡事件。 */
    val events: SharedFlow<ComputeNodeEvent> = mutableEvents.asSharedFlow()

    private val mutableSnapshots = MutableStateFlow<Map<String, ComputeNodeSnapshot>>(emptyMap())

    /** 各节点最近一次心跳快照，按 nodeId 索引。 */
    val snapshots: StateFlow<Map<String, ComputeNodeSnapshot>> = mutableSnapshots.asStateFlow()

    private var loopJob: Job? = null

    /** 启动心跳循环；重复调用无副作用。 */
    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (isActive) {
                refreshAll()
                delay(heartbeatIntervalMs)
            }
        }
    }

    /** 立即刷新全部节点。单个节点的失败不会中断其他节点。 */
    suspend fun refreshAll() {
        val nodes = try {
            dao.getAll()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SQLException) {
            Logger.w(TAG, "cannot load compute nodes: " + e.javaClass.simpleName)
            return
        } catch (e: IllegalStateException) {
            Logger.w(TAG, "cannot load compute nodes: " + e.javaClass.simpleName)
            return
        }
        for (node in nodes) {
            refresh(node)
        }
    }

    /** 刷新一个节点并写回状态；网络/鉴权失败会被收敛成状态值，不向调用方抛出。 */
    suspend fun refresh(node: RemoteComputeNodeEntity): ComputeNodeStatus {
        val previous = ComputeNodeStatus.fromWire(node.status) ?: ComputeNodeStatus.UNINITIALIZED
        val wasOnlineInSession = mutableSnapshots.value[node.nodeId]?.status == ComputeNodeStatus.ONLINE
        return try {
            val health = client.health(node)
            val now = clock()
            dao.updateStatus(node.nodeId, ComputeNodeStatus.ONLINE.wireValue, now, now)
            updateSnapshot(ComputeNodeSnapshot(node.nodeId, ComputeNodeStatus.ONLINE, health, ""))
            if (!wasOnlineInSession) {
                mutableEvents.emit(ComputeNodeEvent.Online(node.nodeId, health))
            }
            pullOutboxQuietly(node.nodeId)
            ComputeNodeStatus.ONLINE
        } catch (e: CancellationException) {
            throw e
        } catch (e: MuseNodeException) {
            val reason = failureReason(e)
            val status = if (isConfigurationFailure(e)) ComputeNodeStatus.ERROR else ComputeNodeStatus.OFFLINE
            val now = clock()
            dao.updateStatus(node.nodeId, status.wireValue, node.lastSeenAt, now)
            updateSnapshot(ComputeNodeSnapshot(node.nodeId, status, null, reason))
            if (previous != status && previous != ComputeNodeStatus.OFFLINE) {
                mutableEvents.emit(ComputeNodeEvent.Offline(node.nodeId, reason))
                if (previous == ComputeNodeStatus.ONLINE) {
                    mutableEvents.emit(
                        ComputeNodeEvent.ReconnectRequired(
                            nodeId = node.nodeId,
                            payload = ReconnectCardPayload(
                                action = ReconnectActionKind.COMPUTE_NODE,
                                taskId = node.nodeId,
                                reason = reason.take(MAX_REASON_LENGTH),
                            ),
                        ),
                    )
                }
            }
            status
        } catch (e: IllegalStateException) {
            val reason = failureReason(e)
            val now = clock()
            dao.updateStatus(node.nodeId, ComputeNodeStatus.OFFLINE.wireValue, node.lastSeenAt, now)
            updateSnapshot(ComputeNodeSnapshot(node.nodeId, ComputeNodeStatus.OFFLINE, null, reason))
            ComputeNodeStatus.OFFLINE
        }
    }

    /** 刷新指定节点；节点不存在时抛出 [MuseNodeException]。 */
    suspend fun refresh(nodeId: String): ComputeNodeStatus {
        val node = dao.getById(nodeId) ?: throw MuseNodeException(
            error = MuseNodeError.NODE_NOT_CONFIGURED,
            message = "compute node not found: " + nodeId,
        )
        return refresh(node)
    }

    /** 拉取 since 之后的新 outbox 事件并发射；返回本次事件列表。 */
    suspend fun pullOutbox(nodeId: String): List<OutboxEvent> {
        val node = dao.getById(nodeId) ?: throw MuseNodeException(
            error = MuseNodeError.NODE_NOT_CONFIGURED,
            message = "compute node not found: " + nodeId,
        )
        // The server removes acknowledged files and does not paginate; rereading retained items is
        // required so an event that failed local publication remains retryable in this process.
        val page = client.listOutbox(node, since = 0L)
        if (page.events.isNotEmpty()) {
            mutableEvents.emit(ComputeNodeEvent.OutboxReceived(nodeId, page.events))
        }
        return page.events
    }

    /** 确认已消费的 outbox 事件，返回服务端清理条数。 */
    suspend fun acknowledgeOutbox(nodeId: String, eventIds: List<String>): Int {
        if (eventIds.isEmpty()) return 0
        val node = dao.getById(nodeId) ?: throw MuseNodeException(
            error = MuseNodeError.NODE_NOT_CONFIGURED,
            message = "compute node not found: " + nodeId,
        )
        return client.ackOutbox(node, eventIds)
    }

    override fun close() {
        loopJob?.cancel()
        loopJob = null
    }

    private suspend fun pullOutboxQuietly(nodeId: String) {
        try {
            pullOutbox(nodeId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MuseNodeException) {
            // 心跳成功但 outbox 拉取失败：记录即可，下一轮心跳会重新拉取。
            Logger.w(TAG, "outbox pull failed for node " + nodeId + ": " + e.javaClass.simpleName)
        } catch (e: IllegalStateException) {
            Logger.w(TAG, "outbox pull failed for node " + nodeId + ": " + e.javaClass.simpleName)
        }
    }

    private fun updateSnapshot(snapshot: ComputeNodeSnapshot) {
        mutableSnapshots.update { current -> current + (snapshot.nodeId to snapshot) }
    }

    private fun isConfigurationFailure(e: Exception): Boolean {
        val error = (e as? MuseNodeException)?.error ?: return false
        return error == MuseNodeError.AUTH_REQUIRED ||
            error == MuseNodeError.TLS_FINGERPRINT_MISMATCH ||
            error == MuseNodeError.NODE_NOT_CONFIGURED
    }

    private fun failureReason(e: Exception): String = when (e) {
        is MuseNodeException -> e.error.name + ": " + e.message.orEmpty()
        else -> "connection failed: " + e.javaClass.simpleName
    }

    private companion object {
        private const val TAG = "ComputeNodeManager"
        private const val DEFAULT_HEARTBEAT_INTERVAL_MS = 30_000L
        private const val EVENT_BUFFER_CAPACITY = 32
        private const val MAX_REASON_LENGTH = 160
    }
}

/** 节点心跳快照；health 为空表示最近一次心跳失败。 */
data class ComputeNodeSnapshot(
    val nodeId: String,
    val status: ComputeNodeStatus,
    val health: NodeHealth? = null,
    val lastError: String = "",
)

/** 节点生命周期事件。 */
sealed interface ComputeNodeEvent {
    data class Online(val nodeId: String, val health: NodeHealth) : ComputeNodeEvent

    data class Offline(val nodeId: String, val reason: String) : ComputeNodeEvent

    data class ReconnectRequired(val nodeId: String, val payload: ReconnectCardPayload) : ComputeNodeEvent

    data class OutboxReceived(val nodeId: String, val events: List<OutboxEvent>) : ComputeNodeEvent
}
