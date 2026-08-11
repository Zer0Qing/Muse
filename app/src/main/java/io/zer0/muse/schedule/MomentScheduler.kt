package io.zer0.muse.schedule

import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.moment.MomentGenerator
import io.zer0.muse.data.moment.MomentRepository
import io.zer0.muse.util.GlobalCoroutineExceptionHandler
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
 * 调度循环:每 10 分钟检查一次是否到期,生成后记录到 [MomentRepository]。
 */
class MomentScheduler(
    private val appScope: CoroutineScope,
    private val settings: SettingsRepository,
    private val repository: MomentRepository,
    private val generator: MomentGenerator,
    private val assistantRepository: io.zer0.muse.data.assistant.AssistantRepository,
) {

    private val TAG = "MomentScheduler"
    private var job: Job? = null

    fun start() {
        job?.cancel()
        job = appScope.launch(GlobalCoroutineExceptionHandler) {
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
        }
    }

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
        return true
    }

    private suspend fun checkAndGenerate() {
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
        // 助手互赞互评:随机 0-2 个其他助手点赞,随机 0-1 个助手评论
        reactToMoment(moment, assistant)
    }

    /** 随机挑一个助手(所有助手都可发朋友圈;无助手时回退 Muse 默认身份)。 */
    private suspend fun pickAssistant(): io.zer0.muse.data.assistant.AssistantEntity? {
        val assistants = resultOf { assistantRepository.getAll() }.getOrNull() ?: emptyList()
        if (assistants.isEmpty()) return null
        return assistants[Random.nextInt(assistants.size)]
    }

    /** 新动态生成后,随机其他助手点赞/评论(互赞互评)。
     *  v1.0.74 fix: 此前每条动态必赞必评,每天 10-20 赞 + 10 评刷屏,
     *  改为概率触发(点赞 60%、评论 30%),避免 AI 自导自演一整版。 */
    private suspend fun reactToMoment(
        moment: io.zer0.muse.data.moment.MomentEntity,
        author: io.zer0.muse.data.assistant.AssistantEntity?,
    ) {
        val assistants = resultOf { assistantRepository.getAll() }.getOrNull() ?: emptyList()
        val others = assistants.filter { it.id != author?.id }
        if (others.isEmpty()) return

        // 60% 概率触发点赞(随机 1-2 个助手)
        val likers = if (Random.nextFloat() < 0.6f) {
            others.shuffled(Random).take(Random.nextInt(1, 3))
        } else {
            emptyList()
        }
        var updated = moment
        likers.forEach { liker ->
            updated = repository.likeBy(
                updated,
                likerType = "assistant",
                likerId = liker.id,
                likerName = liker.name,
            )
        }

        // 30% 概率触发评论(LLM 生成,失败跳过)
        if (Random.nextFloat() < 0.3f) {
            val commenter = others[Random.nextInt(others.size)]
            val reply = generator.generateReply(
                momentContent = moment.content,
                userComment = "(看了你的动态)",
                assistant = commenter,
            )
            if (!reply.isNullOrBlank()) {
                repository.insertComment(
                    momentId = moment.id,
                    sender = "assistant",
                    content = reply,
                    senderId = commenter.id,
                    senderName = commenter.name,
                )
            }
        }
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
