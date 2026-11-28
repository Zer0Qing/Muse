package io.zer0.muse.computenode

import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import io.zer0.common.Logger
import io.zer0.muse.data.SecureKeyCipher
import io.zer0.muse.data.SecureKeyStore
import io.zer0.muse.data.computenode.RemoteComputeNodeEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * SSH 本地端口转发管理器。
 *
 * 每个节点按需维持一条到 127.0.0.1:daemonPort 的 SSH 隧道；连接中断后按指数退避
 * 重建会话，直到调用方显式关闭。退出时必须调用 [close] 或 [closeAll]，否则
 * JSch 的后台线程会继续持有 socket。
 */
class SshTunnelManager(
    private val scope: CoroutineScope,
    private val cipher: SecureKeyCipher = SecureKeyStore,
) : AutoCloseable {

    private val tunnels = ConcurrentHashMap<String, TunnelHandle>()
    private val lock = Any()

    /**
     * 确保节点的隧道存在并返回其状态流。
     *
     * 同一 nodeId 已存在活动隧道时直接复用；上次以 [SshTunnelState.Failed] 结束时
     * 会重新建立（用户修正凭据后无需先手动 close）。
     */
    suspend fun ensureTunnel(node: RemoteComputeNodeEntity, remotePort: Int = node.daemonPort): StateFlow<SshTunnelState> {
        val existing = tunnels[node.nodeId]
        if (existing != null && existing.remotePort == remotePort &&
            existing.state.value !is SshTunnelState.Failed
        ) {
            return existing.state
        }
        val spec = node.toSshConnectionSpec(cipher)
        return start(node.nodeId, spec, remotePort)
    }

    /** 用已解析的连接参数建立隧道，适合扫码配对后直接调用。 */
    fun ensureTunnel(nodeId: String, spec: SshConnectionSpec, remotePort: Int): StateFlow<SshTunnelState> = start(nodeId, spec, remotePort)

    /** 等待隧道可用并返回本地端口；超时或认证失败会抛出异常。 */
    suspend fun awaitLocalPort(
        node: RemoteComputeNodeEntity,
        remotePort: Int = node.daemonPort,
        timeoutMs: Long = DEFAULT_AWAIT_TIMEOUT_MS,
    ): Int {
        val state = ensureTunnel(node, remotePort)
        val current = withTimeout(timeoutMs) {
            state.first { it is SshTunnelState.Active || it is SshTunnelState.Failed }
        }
        return when (current) {
            is SshTunnelState.Active -> current.localPort
            is SshTunnelState.Failed -> throw SshBootstrapException("SSH tunnel failed: " + current.reason)
            is SshTunnelState.Idle,
            is SshTunnelState.Connecting,
            is SshTunnelState.Reconnecting,
            -> throw SshBootstrapException("SSH tunnel did not become active")
        }
    }

    fun state(nodeId: String): StateFlow<SshTunnelState>? = tunnels[nodeId]?.state

    /** 关闭一个节点的隧道并释放 session。 */
    fun close(nodeId: String) {
        val handle = synchronized(lock) { tunnels.remove(nodeId) } ?: return
        handle.job?.cancel()
        handle.session?.disconnect()
        handle.state.value = SshTunnelState.Idle
    }

    fun closeAll() {
        tunnels.keys.toList().forEach(::close)
    }

    override fun close() = closeAll()

    private fun start(nodeId: String, spec: SshConnectionSpec, remotePort: Int): StateFlow<SshTunnelState> = synchronized(lock) {
        val existing = tunnels[nodeId]
        if (existing != null) {
            if (existing.remotePort == remotePort &&
                existing.state.value !is SshTunnelState.Failed
            ) {
                return existing.state
            }
            existing.job?.cancel()
            existing.session?.disconnect()
        }
        val handle = TunnelHandle(
            nodeId = nodeId,
            spec = spec,
            remotePort = remotePort,
            state = MutableStateFlow(SshTunnelState.Connecting),
        )
        tunnels[nodeId] = handle
        handle.job = scope.launch(Dispatchers.IO) { runLoop(handle) }
        handle.state
    }

    private suspend fun runLoop(handle: TunnelHandle) {
        var attempt = 0
        while (currentCoroutineContext().isActive && tunnels[handle.nodeId] === handle) {
            if (attempt > 0) {
                val delayMs = backoffDelayMs(attempt)
                handle.state.value = SshTunnelState.Reconnecting(
                    attempt = attempt,
                    delayMs = delayMs,
                    reason = handle.lastFailure,
                )
                delay(delayMs)
            }
            if (!currentCoroutineContext().isActive) break
            var session: Session? = null
            try {
                handle.state.value = SshTunnelState.Connecting
                session = SshSessions.connect(handle.spec)
                val localPort = session.setPortForwardingL(0, "127.0.0.1", handle.remotePort)
                handle.session = session
                handle.state.value = SshTunnelState.Active(
                    localPort = localPort,
                    remotePort = handle.remotePort,
                )
                attempt = 0
                while (currentCoroutineContext().isActive && session.isConnected) {
                    delay(HEALTH_CHECK_INTERVAL_MS)
                }
                if (!currentCoroutineContext().isActive) break
                throw JSchException("SSH session closed")
            } catch (e: CancellationException) {
                throw e
            } catch (e: JSchException) {
                val reason = sshFailureReason(e)
                handle.lastFailure = reason
                Logger.w(TAG, "tunnel " + handle.nodeId + " interrupted: " + reason)
                if (isFatalAuthFailure(e)) {
                    handle.state.value = SshTunnelState.Failed(reason)
                    break
                }
                attempt += 1
            } catch (e: IllegalStateException) {
                val reason = sshFailureReason(e)
                handle.lastFailure = reason
                Logger.w(TAG, "tunnel " + handle.nodeId + " interrupted: " + reason)
                if (isFatalAuthFailure(e)) {
                    handle.state.value = SshTunnelState.Failed(reason)
                    break
                }
                attempt += 1
            } finally {
                if (handle.session === session) handle.session = null
                session?.disconnect()
            }
        }
    }

    private fun backoffDelayMs(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, MAX_BACKOFF_EXPONENT)
        return (BASE_RETRY_DELAY_MS shl exponent).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    private fun isFatalAuthFailure(e: Exception): Boolean {
        val jsch = e as? JSchException ?: e.cause as? JSchException ?: return false
        val message = jsch.message?.lowercase() ?: return false
        return message.contains("auth fail") ||
            message.contains("auth cancel") ||
            message.contains("auth reject") ||
            message.contains("userauth") ||
            message.contains("invalid privatekey") ||
            message.contains("invalid private key") ||
            message.contains("host key") ||
            message.contains("hostkey")
    }

    private class TunnelHandle(
        val nodeId: String,
        val spec: SshConnectionSpec,
        val remotePort: Int,
        val state: MutableStateFlow<SshTunnelState>,
    ) {
        @Volatile
        var session: Session? = null

        @Volatile
        var lastFailure: String = "connection lost"

        var job: Job? = null
    }

    private companion object {
        private const val TAG = "SshTunnelManager"
        private const val BASE_RETRY_DELAY_MS = 1_000L
        private const val MAX_RETRY_DELAY_MS = 60_000L
        private const val MAX_BACKOFF_EXPONENT = 6
        private const val HEALTH_CHECK_INTERVAL_MS = 5_000L
        private const val DEFAULT_AWAIT_TIMEOUT_MS = 30_000L
    }
}

/** SSH 隧道生命周期状态。 */
sealed interface SshTunnelState {
    data object Idle : SshTunnelState

    data object Connecting : SshTunnelState

    data class Active(val localPort: Int, val remotePort: Int) : SshTunnelState

    data class Reconnecting(val attempt: Int, val delayMs: Long, val reason: String) : SshTunnelState

    data class Failed(val reason: String) : SshTunnelState
}
