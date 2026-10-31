package io.zer0.muse.web

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * F-39: 多客户端串行 / 焦点隔离令牌。
 *
 * chat.send / session.select / settings.update 等写命令直接改共享 App 状态,若不隔离,
 * 两个浏览器可并发交叉中断彼此生成。焦点令牌保证任意时刻至多一个连接持有本状态,
 * 其余连接的写命令返回明确的 focus_not_held 错误。
 */
internal class HostFocusTokenHolder {
    private val mutex = Mutex()
    private var holderConnectionId: String? = null
    private var holderSessionId: String? = null

    /** 抢占焦点;仅当当前无人持有或就是本连接时成功。 */
    suspend fun acquire(connectionId: String, sessionId: String?): Boolean = mutex.withLock {
        if (holderConnectionId == null || holderConnectionId == connectionId) {
            holderConnectionId = connectionId
            if (!sessionId.isNullOrBlank()) holderSessionId = sessionId
            true
        } else {
            false
        }
    }

    /**
     * 写命令前校验;仅持焦点的连接可通过,并顺手刷新会话焦点。
     * 若当前无人持有(比如持有人已断开),首次写命令即抢占("首次 send 抢占")。
     */
    suspend fun holds(connectionId: String, sessionId: String?): Boolean = mutex.withLock {
        when {
            holderConnectionId == connectionId -> {
                if (!sessionId.isNullOrBlank()) holderSessionId = sessionId
                true
            }
            holderConnectionId == null -> {
                holderConnectionId = connectionId
                if (!sessionId.isNullOrBlank()) holderSessionId = sessionId
                true
            }
            else -> false
        }
    }

    /** 连接断开时释放焦点(仅当该连接还持有);若此前已被新连接抢占则不动。 */
    suspend fun release(connectionId: String) = mutex.withLock {
        if (holderConnectionId == connectionId) {
            holderConnectionId = null
            holderSessionId = null
        }
    }
}
