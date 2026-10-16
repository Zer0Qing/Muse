package io.zer0.muse.data

import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.ai.NotificationMemoryDigest
import io.zer0.memory.ticker.MemoryTicker
import io.zer0.muse.notification.MuseNotificationListenerService
import io.zer0.muse.notification.NotificationRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * v2.6.2（用户反馈）: 通知 → 记忆 后台梳理调度。
 *
 * 用户希望助手「自己知道」收到的通知，并在主动消息里自然提起。本调度器负责：
 *  1. 周期性（跟随记忆 ticker 节奏）拉取通知监听服务采集到的、尚未梳理的通知
 *  2. 交给 [NotificationMemoryDigest]（走大工具模型）梳理/整合/去重
 *  3. 落库后，通知类事实即随记忆注入主动消息上下文（见 ProactiveMessageRunner）
 *
 * 节奏:与记忆编译对齐 —— 默认每 [POLL_INTERVAL_MS] 检查一次；只有出现新的、
 * 在时效窗口内的通知才会真正调 LLM，避免空转烧 token。
 *
 * 前置条件:
 *  - 用户已授权通知使用权（[MuseNotificationListenerService.isConnected]）
 *  - 记忆功能开启（[SettingsRepository.isMemoryEnabled]）
 *  - 总开关 [SettingsRepository.notificationMemoryEnabledFlow]（默认关，用户显式开启）
 */
class NotificationMemoryScheduler(
    private val settings: SettingsRepository,
    private val digest: NotificationMemoryDigest,
    private val memoryTicker: MemoryTicker,
    private val scope: CoroutineScope,
) {

    private var job: kotlinx.coroutines.Job? = null

    /** 上次处理到的最新通知时间戳，避免重复梳理旧通知。 */
    @Volatile
    private var lastProcessedAt: Long = 0L

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            Logger.i(TAG, "NotificationMemoryScheduler started")
            // 启动时初始化游标:只处理启动之后收到的通知（历史通知不回溯，避免一次性灌入）。
            lastProcessedAt = System.currentTimeMillis()
            while (isActive) {
                runCatching { tickOnce() }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else Logger.w(TAG, "tick 失败: ${it.message}") }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** 单次梳理（供测试/手动触发）。 */
    suspend fun tickOnce(): NotificationMemoryDigest.DigestResult? {
        val fresh = collectFresh() ?: return null
        return runDigest(fresh)
    }

    /** 收集游标之后的新鲜通知；无新通知或前置条件不满足时返回 null。 */
    private suspend fun collectFresh(): List<NotificationRecord>? {
        if (!shouldRun()) return null
        val windowStart = System.currentTimeMillis() - WINDOW_MS
        val since = maxOf(windowStart, lastProcessedAt)
        return MuseNotificationListenerService.recentNotifications.value
            .filter { it.timestamp > since }
            .sortedBy { it.timestamp }
            .takeIf { it.isNotEmpty() }
    }

    /** 前置条件:通知记忆开关 + 记忆功能 + 通知授权三者均就绪。 */
    private suspend fun shouldRun(): Boolean = settings.notificationMemoryEnabledFlow.first() &&
        settings.isMemoryEnabled() &&
        MuseNotificationListenerService.isConnected()

    /** 把一批新鲜通知交给大工具模型梳理并落库。 */
    private suspend fun runDigest(fresh: List<NotificationRecord>): NotificationMemoryDigest.DigestResult {
        val assistantId = settings.getDefaultAssistantId().ifBlank { "main" }
        val scopeId = if (assistantId == "default") "main" else assistantId
        val spaceId = settings.currentSpaceIdFlow.first().ifBlank { "default" }
        val result = digest.digest(
            notifications = fresh.map { it.toInput() },
            scope = scopeId,
            spaceId = spaceId,
            model = null,
            // 由 MemoryLlmClient 路由到大工具档
            locale = settings.getLanguageSync().let { if (it == "system" || it.isBlank()) "zh-CN" else it },
        )
        // 无论是否落库，都推进游标，避免同一批通知反复送 LLM。
        lastProcessedAt = fresh.maxOf { it.timestamp }
        if (result.extracted > 0) {
            // 触发一次记忆编译，让新事实进入 memory.md（与 backfill 迁移后的做法一致）。
            resultOf { memoryTicker.tick() }
                .onError { msg, t -> Logger.w(TAG, "通知落库后 tick() 失败: $msg", t) }
        }
        return result
    }
    private fun NotificationRecord.toInput() = NotificationMemoryDigest.NotificationInput(
        packageName = packageName,
        appLabel = appLabel,
        title = title,
        text = text,
        timestamp = timestamp,
    )

    companion object {
        private const val TAG = "NotifMemorySched"

        /** 检查间隔（10 分钟）——通知梳理不是实时需求，低频即可。 */
        private const val POLL_INTERVAL_MS = 10 * 60 * 1000L

        /** 只梳理最近 24 小时内的通知（时效窗口）。 */
        private const val WINDOW_MS = 24 * 60 * 60 * 1000L
    }
}
