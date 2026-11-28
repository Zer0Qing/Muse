package io.zer0.muse.channel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 渠道实时连接状态枚举。
 *
 * 所有平台接收器通过 [ChannelRuntimeRegistry] 汇报同一套生命周期态，
 * 供渠道中心卡片、设置首页徽标与 Agent 健康灯直接消费。
 */
enum class ChannelConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    AUTH_ERROR,
    ERROR,
    STOPPED,
}

/** 单个渠道实例的实时运行快照。 */
data class ChannelRuntimeStatus(
    val channelId: String,
    val platform: ChannelPlatform,
    val state: ChannelConnectionState = ChannelConnectionState.IDLE,
    val botDisplayName: String = "",
    val lastConnectedAt: Long = 0L,
    val lastInboundAt: Long = 0L,
    val lastError: String = "",
    val reconnectAttempts: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * 进程级渠道实时状态注册中心（单向 StateFlow）。
 *
 * 替代过去「配置了多少条 = 连接状态」的静态估算；所有接收器按 `channelId` 上报。
 */
object ChannelRuntimeRegistry {

    private val _statuses = MutableStateFlow<Map<String, ChannelRuntimeStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, ChannelRuntimeStatus>> = _statuses.asStateFlow()

    fun markConnecting(channelId: String, platform: ChannelPlatform) {
        if (channelId.isBlank()) return
        val now = System.currentTimeMillis()
        _statuses.update { map ->
            val prev = map[channelId]
            map + (
                channelId to ChannelRuntimeStatus(
                    channelId = channelId,
                    platform = platform,
                    state = if (prev != null && prev.reconnectAttempts > 0) {
                        ChannelConnectionState.RECONNECTING
                    } else {
                        ChannelConnectionState.CONNECTING
                    },
                    botDisplayName = prev?.botDisplayName.orEmpty(),
                    lastConnectedAt = prev?.lastConnectedAt ?: 0L,
                    lastInboundAt = prev?.lastInboundAt ?: 0L,
                    lastError = "",
                    reconnectAttempts = prev?.reconnectAttempts ?: 0,
                    updatedAt = now,
                )
                )
        }
    }

    fun markConnected(channelId: String, platform: ChannelPlatform, botDisplayName: String = "") {
        if (channelId.isBlank()) return
        val now = System.currentTimeMillis()
        _statuses.update { map ->
            val prev = map[channelId]
            map + (
                channelId to ChannelRuntimeStatus(
                    channelId = channelId,
                    platform = platform,
                    state = ChannelConnectionState.CONNECTED,
                    botDisplayName = botDisplayName.ifBlank { prev?.botDisplayName.orEmpty() },
                    lastConnectedAt = now,
                    lastInboundAt = prev?.lastInboundAt ?: 0L,
                    lastError = "",
                    reconnectAttempts = 0,
                    updatedAt = now,
                )
                )
        }
    }

    fun markReconnecting(channelId: String, platform: ChannelPlatform, error: String = "") {
        if (channelId.isBlank()) return
        val now = System.currentTimeMillis()
        _statuses.update { map ->
            val prev = map[channelId]
            map + (
                channelId to ChannelRuntimeStatus(
                    channelId = channelId,
                    platform = platform,
                    state = ChannelConnectionState.RECONNECTING,
                    botDisplayName = prev?.botDisplayName.orEmpty(),
                    lastConnectedAt = prev?.lastConnectedAt ?: 0L,
                    lastInboundAt = prev?.lastInboundAt ?: 0L,
                    lastError = error.take(240),
                    reconnectAttempts = (prev?.reconnectAttempts ?: 0) + 1,
                    updatedAt = now,
                )
                )
        }
    }

    fun markAuthError(channelId: String, platform: ChannelPlatform, error: String) {
        if (channelId.isBlank()) return
        val now = System.currentTimeMillis()
        _statuses.update { map ->
            val prev = map[channelId]
            map + (
                channelId to ChannelRuntimeStatus(
                    channelId = channelId,
                    platform = platform,
                    state = ChannelConnectionState.AUTH_ERROR,
                    botDisplayName = prev?.botDisplayName.orEmpty(),
                    lastConnectedAt = prev?.lastConnectedAt ?: 0L,
                    lastInboundAt = prev?.lastInboundAt ?: 0L,
                    lastError = error.take(240),
                    reconnectAttempts = (prev?.reconnectAttempts ?: 0) + 1,
                    updatedAt = now,
                )
                )
        }
    }

    fun markError(channelId: String, platform: ChannelPlatform, error: String) {
        if (channelId.isBlank()) return
        val now = System.currentTimeMillis()
        _statuses.update { map ->
            val prev = map[channelId]
            map + (
                channelId to ChannelRuntimeStatus(
                    channelId = channelId,
                    platform = platform,
                    state = ChannelConnectionState.ERROR,
                    botDisplayName = prev?.botDisplayName.orEmpty(),
                    lastConnectedAt = prev?.lastConnectedAt ?: 0L,
                    lastInboundAt = prev?.lastInboundAt ?: 0L,
                    lastError = error.take(240),
                    reconnectAttempts = (prev?.reconnectAttempts ?: 0) + 1,
                    updatedAt = now,
                )
                )
        }
    }

    fun markStopped(channelId: String, platform: ChannelPlatform) {
        if (channelId.isBlank()) return
        val now = System.currentTimeMillis()
        _statuses.update { map ->
            val prev = map[channelId]
            map + (
                channelId to ChannelRuntimeStatus(
                    channelId = channelId,
                    platform = platform,
                    state = ChannelConnectionState.STOPPED,
                    botDisplayName = prev?.botDisplayName.orEmpty(),
                    lastConnectedAt = prev?.lastConnectedAt ?: 0L,
                    lastInboundAt = prev?.lastInboundAt ?: 0L,
                    lastError = "",
                    reconnectAttempts = 0,
                    updatedAt = now,
                )
                )
        }
    }

    fun markInbound(channelId: String, platform: ChannelPlatform) {
        if (channelId.isBlank()) return
        val now = System.currentTimeMillis()
        _statuses.update { map ->
            val prev = map[channelId]
            map + (
                channelId to (
                    prev?.copy(
                        state = ChannelConnectionState.CONNECTED,
                        lastConnectedAt = if (prev.lastConnectedAt > 0L) prev.lastConnectedAt else now,
                        lastInboundAt = now,
                        lastError = "",
                        reconnectAttempts = 0,
                        updatedAt = now,
                    ) ?: ChannelRuntimeStatus(
                        channelId = channelId,
                        platform = platform,
                        state = ChannelConnectionState.CONNECTED,
                        lastConnectedAt = now,
                        lastInboundAt = now,
                        updatedAt = now,
                    )
                    )
                )
        }
    }

    fun remove(channelId: String) {
        if (channelId.isBlank()) return
        _statuses.update { map -> map - channelId }
    }

    fun prune(activeChannelIds: Set<String>) {
        _statuses.update { map -> map.filterKeys { it in activeChannelIds } }
    }

    /** 已启用且当前在线的渠道数量。 */
    fun onlineCount(channels: List<ChannelConfig>): Int {
        val current = _statuses.value
        return channels.count { it.enabled && current[it.id]?.state == ChannelConnectionState.CONNECTED }
    }

    /** 已启用但处于错误/鉴权失效状态的渠道数量。 */
    fun issueCount(channels: List<ChannelConfig>): Int {
        val current = _statuses.value
        return channels.count { cfg ->
            if (!cfg.enabled) return@count false
            val state = current[cfg.id]?.state
            state == ChannelConnectionState.AUTH_ERROR || state == ChannelConnectionState.ERROR
        }
    }

    /** 是否属于鉴权类异常文案（用于把 401/403 等映射成可操作 AUTH_ERROR）。 */
    fun looksLikeAuthError(message: String?): Boolean {
        val lower = message.orEmpty().lowercase()
        return lower.contains("401") ||
            lower.contains("403") ||
            lower.contains("unauthorized") ||
            lower.contains("invalid token") ||
            lower.contains("appsecret") ||
            lower.contains("errcode=-1") ||
            lower.contains("ret=11")
    }

    internal fun resetForTest() {
        _statuses.value = emptyMap()
    }
}
