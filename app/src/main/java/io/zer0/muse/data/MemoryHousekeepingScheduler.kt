package io.zer0.muse.data

import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.agentdm.AgentDmRepository
import io.zer0.muse.data.groupchat.GroupChatMemoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * v2.6.2（记忆审计）: 记忆辅助数据清理调度。
 *
 * 背景：`GroupChatMemoryRepository.cleanupOlderThan` 与 `AgentDmRepository.cleanupOlderThan`
 * 此前定义了但全仓无调用点 —— 注释写着"定期清理"，实际没有任何调度器调它，
 * 导致群聊记忆与 Agent 私信无限增长。
 *
 * 本调度器把它们挂上：启动时清理一次，之后每 [INTERVAL_MS] 清理一次。
 * 默认保留 30 天，可在调用处调整。
 */
class MemoryHousekeepingScheduler(
    private val settings: SettingsRepository,
    private val groupChatMemoryRepository: GroupChatMemoryRepository,
    private val agentDmRepository: AgentDmRepository,
    private val scope: CoroutineScope,
) {

    private var job: kotlinx.coroutines.Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            Logger.i(TAG, "MemoryHousekeepingScheduler started")
            while (isActive) {
                runCatching { sweep() }
                    .onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        Logger.w(TAG, "记忆清理失败: ${it.message}")
                    }
                delay(INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** 单次清理（供测试/手动触发）。 */
    suspend fun sweep() {
        // 尊重记忆总开关：用户关闭记忆时不清理（数据保留，便于重新开启）。
        if (!settings.isMemoryEnabled()) return
        val keepDays = RETENTION_DAYS
        resultOf { groupChatMemoryRepository.cleanupOlderThan(keepDays) }
            .onError { msg, t -> Logger.w(TAG, "清理群聊记忆失败: $msg", t) }
        resultOf { agentDmRepository.cleanupOlderThan(keepDays) }
            .onError { msg, t -> Logger.w(TAG, "清理 Agent 私信失败: $msg", t) }
    }

    companion object {
        private const val TAG = "MemoryHousekeeping"

        /** 清理间隔（12 小时）。 */
        private const val INTERVAL_MS = 12 * 60 * 60 * 1000L

        /** 保留天数。 */
        private const val RETENTION_DAYS = 30
    }
}
