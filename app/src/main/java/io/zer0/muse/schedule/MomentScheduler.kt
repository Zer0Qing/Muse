package io.zer0.muse.schedule

import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.moment.MomentGenerator
import io.zer0.muse.data.moment.MomentInteractionEngine
import io.zer0.muse.data.moment.MomentRepository
import io.zer0.muse.util.GlobalCoroutineExceptionHandler
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.koin.core.context.GlobalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * v1.0.72: AI 朋友圈调度器 — 按用户频率设置定时生成动态。
 *
 * 频率策略(用户自由选择,0-10 条/天):
 *  - 把一天按条数切段(如 2 条 → 上午/下午),每段随机取时间点(±1h 抖动)
 *  - 达到今日条数后不再生成(防打扰)
 *  - 手动生成(用户说"发一条朋友圈")走 [generateNow]
 *
 * v1.0.73: 多助手 — 每次生成随机选一个助手(所有助手都可以发朋友圈),
 * 生成后随机其他助手点赞/评论(助手互赞互评)。
 *
 * v1.0.75: 将互动逻辑委托给 [MomentInteractionEngine],统一防死循环与上限控制。
 *
 * 调度循环:每 10 分钟检查一次是否到期,生成后记录到 [MomentRepository]。
 */
class MomentScheduler(
    private val appScope: CoroutineScope,
    private val settings: SettingsRepository,
    private val repository: MomentRepository,
    private val generator: MomentGenerator,
    private val assistantRepository: io.zer0.muse.data.assistant.AssistantRepository,
    private val interactionEngine: MomentInteractionEngine,
) {

    private val TAG = "MomentScheduler"
    private var job: Job? = null

    /**
     * B-40: 进程内调度是否启动的标志。
     * 供 WorkManager 兜底 Worker([MomentWorker])判断"进程内 Runner 是否存活":
     * 存活则直接跳过,避免 10min 轮询与 15min Worker 重复巡检朋友圈。
     */
    @Volatile
    internal var running: Boolean = false
        private set

    fun start() {
        job?.cancel()
        running = true
        job = appScope.launch(GlobalCoroutineExceptionHandler) {
            try {
                Logger.i(TAG, "MomentScheduler started")
                while (isActive) {
                    try {
                        checkAndGenerate()
                    } catch (e: Exception) {
                        if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                        Logger.w(TAG, "朋友圈调度错误: ${e.message}")
                    }
                    delay(CHECK_INTERVAL_MS)
                }
            } finally {
                running = false
            }
        }
    }

    /**
     * B-40: 供 [MomentWorker] 调用的按需检查入口。
     * 复用 [checkAndGenerate] 的到期/上限判断,进程被杀后由 WorkManager 兜底触发;
     * 由 [checkAndGenerate] 内部的 dailyMomentCountFlow 与 countToday 上限保证幂等,
     * 不会超过每日条数。
     */
    suspend fun checkAndGenerateOnce() = checkAndGenerate()

    /** 手动生成一条(用户触发)。返回是否成功。 */
    suspend fun generateNow(): Boolean {
        val assistant = pickAssistant()
        val generated = generator.generate(assistant) ?: return false
        repository.insertMoment(
            generated.content, generated.type, generated.mood, source = "manual",
            imageUrl = generated.imageUrl,
            senderName = assistant?.name?.takeIf { it.isNotBlank() } ?: "Muse",
            senderId = assistant?.id,
            senderAvatar = assistant?.avatarEmoji,
        )
        Logger.i(TAG, "手动生成朋友圈: ${generated.content.take(30)}...")
        // v1.0.75: 手动生成不触发互动,避免刷屏
        return true
    }

    private suspend fun checkAndGenerate() {
        // B-25: 后台调度总控 — 关闭时跳过(周期调度仍由 WorkManager 保留,重开即恢复)
        if (!settings.scheduleWorkEnabledFlow.first()) {
            Logger.d(TAG, "后台调度总控已关闭,跳过朋友圈调度")
            return
        }
        val dailyCount = settings.dailyMomentCountFlow.firstSafeValue() ?: 2
        if (dailyCount <= 0) return  // 用户关闭

        val todayCount = repository.countToday()
        if (todayCount >= dailyCount) return  // 已达今日上限

        val now = System.currentTimeMillis()
        // 检查当前是否在"该发"的时间段:第 (todayCount+1) 条对应第 (todayCount+1) 段
        val nextSegment = todayCount + 1
        val targetTime = segmentTargetTime(nextSegment, dailyCount)
        if (now < targetTime) return  // 未到时间

        // 到期:随机选一个助手生成一条
        val assistant = pickAssistant()
        val generated = generator.generate(assistant) ?: return
        val moment = repository.insertMoment(
            generated.content, generated.type, generated.mood, source = "scheduled",
            imageUrl = generated.imageUrl,
            senderName = assistant?.name?.takeIf { it.isNotBlank() } ?: "Muse",
            senderId = assistant?.id,
            senderAvatar = assistant?.avatarEmoji,
        )
        Logger.i(TAG, "定时生成朋友圈 #${todayCount + 1}: ${generated.content.take(30)}...")
        // v1.0.75: 助手发动态 → 其他助手异步互动(统一走引擎)
        if (assistant != null) {
            interactionEngine.triggerOnAssistantPublish(moment, author = assistant)
        }
    }

    /** 随机挑一个助手(所有助手都可发朋友圈;无助手时回退 Muse 默认身份)。 */
    private suspend fun pickAssistant(): io.zer0.muse.data.assistant.AssistantEntity? {
        val assistants = resultOf { assistantRepository.getAll() }.getOrNull() ?: emptyList()
        if (assistants.isEmpty()) return null
        return assistants[Random.nextInt(assistants.size)]
    }

    /**
     * 计算第 [segmentIndex] 段的触发时间点(1-based)。
     * 段起点 = dayStart + (segmentIndex-1) * segmentLength;触发点 = 段起点 + 随机抖动(±segmentLength/3)。
     */
    private fun segmentTargetTime(segmentIndex: Int, dailyCount: Int): Long {
        val dayStart = java.time.LocalDate.now()
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val segmentLength = 86_400_000L / dailyCount
        val segmentStart = dayStart + (segmentIndex - 1) * segmentLength
        val jitterRange = segmentLength / 3
        val jitter = Random.nextLong(-jitterRange, jitterRange + 1)
        return (segmentStart + segmentLength / 2 + jitter).coerceAtLeast(segmentStart)
    }

    companion object {
        private const val TAG_C = "MomentScheduler"
        private const val CHECK_INTERVAL_MS = 10 * 60 * 1000L  // 10 分钟检查一次
    }
}

/** Flow.first() 便捷包装(失败返回默认值)。 */
private suspend fun Flow<Int>.firstSafeValue(): Int? = try {
    first()
} catch (e: Exception) {
    if (e is kotlin.coroutines.cancellation.CancellationException) throw e
    Logger.w("MomentScheduler", "读取频率设置失败: ${e.message}")
    null
}

/**
 * B-40: AI 朋友圈的 WorkManager 兜底 Worker。
 *
 * [MomentScheduler] 的 10min 协程轮询仅在 App 进程存活时有效;App 被杀后朋友圈无法定时生成。
 * 本 Worker 通过 WorkManager 周期性调度(Android 最小周期 15 分钟),进程被杀也能由系统拉起执行。
 *
 * 去重策略(与 [CloudBackupWorker]/[ScheduledTaskWorker] 对齐):
 *  - 若进程内 Runner([MomentScheduler.running])仍存活(冷启动后 MuseApp 已重启进程内轮询),
 *    说明已有 10min 轮询在做事,直接返回 success 跳过,避免重复巡检
 *  - 进程内 Runner 未启动时才真正调用 checkAndGenerateOnce,兜底生成
 *  - [checkAndGenerate] 内部由 dailyMomentCountFlow + countToday 上限保证幂等,不会超发
 *
 * 设计取舍:不设 setExpedited / 网络约束,符合"省电"目标;返回 success 而非 retry,
 * 生成失败记录在日志,由下一次触发重试。
 */
class MomentWorker(
    appContext: android.content.Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val koin = resultOf { GlobalContext.get() }.getOrNull()
        if (koin == null) {
            Logger.w(TAG, "Koin 未初始化(Safe Mode?),跳过本次 Worker 执行")
            return Result.success()
        }
        // B-25: 后台调度总控 — 关闭时跳过执行体,重开即恢复
        val workEnabled = resultOf {
            koin.get<SettingsRepository>().scheduleWorkEnabledFlow.first()
        }.getOrNull() ?: true
        if (!workEnabled) {
            Logger.i(TAG, "后台调度总控已关闭,跳过本次执行")
            return Result.success()
        }
        val scheduler = resultOf { koin.get<MomentScheduler>() }.getOrNull()
        if (scheduler == null) {
            Logger.w(TAG, "MomentScheduler 解析失败,跳过本次 Worker 执行")
            return Result.success()
        }
        // B-40: 进程内 Runner 仍存活则跳过,避免 10min 轮询与 15min Worker 重复巡检
        if (scheduler.running) {
            Logger.d(TAG, "MomentScheduler 进程内轮询存活,兜底 Worker 跳过")
            return Result.success()
        }
        resultOf { scheduler.checkAndGenerateOnce() }
            .onError { msg, t -> Logger.w(TAG, "checkAndGenerateOnce failed: ${t?.message ?: msg}") }
        return Result.success()
    }

    companion object {
        private const val TAG = "MomentWorker"
        const val UNIQUE_WORK_NAME = "muse_moment_worker"
    }
}
