package io.zer0.ai.video

import io.zer0.ai.core.ProviderConfig
import io.zer0.common.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.137: 视频生成统一服务(重构)。
 *
 * 设计:
 *  - 通过 [VideoProviderRegistry] 按 specId / host / type 选择 [VideoProvider]
 *    (修复 v1.136 的 providerId 硬匹配 bug:preset_kling ≠ kling 导致路由失败)
 *  - 统一异步轮询机制(对齐参考实现 openhanako plugins/image-gen/lib/poller.ts):
 *    - 5 秒一个 tick
 *    - 按任务年龄自适应频率:
 *      <2min 每 5s, 2-10min 每 15s, >10min 每 30s
 *    - 连续 [MAX_CONSECUTIVE_ERRORS] 次查询错误才标记失败
 *    - 取消围栏([cancelledTasks])防止 in-flight 查询在用户取消后误写
 *  - 进度回调([onProgress])每 5 秒刷新"已生成 Xs"
 *
 * 用法:
 * ```
 * val service = VideoGenerationService(registry)
 * val videoUrl = service.generateVideo(
 *     providerConfig = config,
 *     request = VideoGenRequest(prompt = "...", model = "..."),
 *     onProgress = { elapsed -> updateUI("已生成 ${elapsed}s") },
 * ).getOrThrow()
 * ```
 *
 * @param registry Video Provider 注册表
 */
class VideoGenerationService(
    private val registry: VideoProviderRegistry,
) {
    /**
     * 按 taskId 的取消围栏。
     *
     * 用户取消([cancel])后加入此集合;in-flight 的 poll 返回后检查围栏,
     * 若已取消则丢弃结果,避免取消后的查询误更新状态。
     */
    private val cancelledTasks: ConcurrentHashMap<String, Boolean> = ConcurrentHashMap()

    /**
     * 生成视频 — 提交任务 → 等待完成 → 返回视频 URL。
     *
     * @param providerConfig 供应商配置(用于 registry 路由 + 注入 apiKey/baseUrl)
     * @param request 生成请求(prompt / model / 参考图 / 时长 / 分辨率等)
     * @param onProgress 进度回调,参数为已用秒数;每 5 秒触发一次;可为 null
     * @param timeoutMs 总超时(毫秒),默认 10 分钟(视频生成最长约 5-10 分钟)
     * @return Result.success(videoUrl) 或 Result.failure(exception)
     */
    suspend fun generateVideo(
        providerConfig: ProviderConfig,
        request: VideoGenRequest,
        onProgress: ((elapsedSec: Long) -> Unit)? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val provider = registry.selectFor(providerConfig)
            Logger.i(
                TAG,
                "generateVideo: provider=${provider.providerId} " +
                    "specId=${providerConfig.specId} host=${VideoProviderRegistry.extractHost(providerConfig.resolvedBaseUrl())}",
            )

            // 注入凭证
            val requestWithCreds = request.copy(
                apiKey = request.apiKey.ifBlank { providerConfig.apiKey },
                baseUrl = request.baseUrl ?: providerConfig.resolvedBaseUrl(),
                videoGenerationsPath = request.videoGenerationsPath
                    ?: (providerConfig.resolvedSpecific() as? io.zer0.ai.core.ProviderSpecificConfig.OpenAI)?.videoGenerationsPath,
            )

            // 1. 提交任务
            val submitResult = provider.submit(requestWithCreds)
            val taskId = submitResult.taskId
            if (taskId.isNullOrBlank()) {
                error("视频任务提交失败:无 taskId")
            }

            // 同步返回:直接取 videoUrl
            if (!submitResult.isAsync) {
                val url = submitResult.videoUrl
                    ?: error("视频任务同步返回但未提供 videoUrl")
                onProgress?.invoke(0)
                return@runCatching url
            }

            // 2. 异步轮询
            pollUntilCompletion(
                provider = provider,
                taskId = taskId,
                onProgress = onProgress,
                timeoutMs = timeoutMs,
            )
        }
    }

    /**
     * 仅提交任务(不等待完成),返回提交结果。
     *
     * 适用于 UI 层需要手动轮询的场景(如 VideoGenerationPage 后续可拆分提交/轮询)。
     */
    suspend fun submit(
        providerConfig: ProviderConfig,
        request: VideoGenRequest,
    ): Result<VideoSubmitResult> = withContext(Dispatchers.IO) {
        runCatching {
            val provider = registry.selectFor(providerConfig)
            val requestWithCreds = request.copy(
                apiKey = request.apiKey.ifBlank { providerConfig.apiKey },
                baseUrl = request.baseUrl ?: providerConfig.resolvedBaseUrl(),
                videoGenerationsPath = request.videoGenerationsPath
                    ?: (providerConfig.resolvedSpecific() as? io.zer0.ai.core.ProviderSpecificConfig.OpenAI)?.videoGenerationsPath,
            )
            provider.submit(requestWithCreds)
        }
    }

    /**
     * 取消指定 taskId 的任务。
     *
     * 加入取消围栏,后续 in-flight 的 poll 结果将被丢弃。
     * 注意:本方法只标记本地取消,不会通知远端 API(各 Provider 的 cancel 语义由其自身实现)。
     */
    fun cancel(taskId: String) {
        cancelledTasks[taskId] = true
        Logger.i(TAG, "任务已取消: taskId=$taskId")
    }

    /**
     * 轮询直到任务完成 / 失败 / 超时 / 取消。
     *
     * 自适应频率(对齐 QingTian Poller):
     *  - 5s 一个 tick
     *  - ageMs < 2min:     每 tick 都查(5s)
     *  - 2min <= ageMs < 10min:  每 3 个 tick 查一次(15s)
     *  - ageMs >= 10min:   每 6 个 tick 查一次(30s)
     *
     * 错误处理:
     *  - 单次 poll 抛异常或返回 PENDING+errorMessage → 计入连续错误计数
     *  - 连续 [MAX_CONSECUTIVE_ERRORS] 次错误才判失败(瞬时网络抖动可自愈)
     *  - 任何一次成功查询(返回明确 SUCCESS/FAILED)重置错误计数
     *
     * 取消围栏:
     *  - 每次 poll 返回后检查 [cancelledTasks]
     *  - 若已取消,丢弃结果并抛出 [CancellationException]
     */
    private suspend fun pollUntilCompletion(
        provider: VideoProvider,
        taskId: String,
        onProgress: ((elapsedSec: Long) -> Unit)?,
        timeoutMs: Long,
    ): String {
        val startedAt = System.currentTimeMillis()
        var tickCount = 0
        var consecutiveErrors = 0
        var lastProgressSec = -1L

        val result = withTimeoutOrNull(timeoutMs) {
            while (true) {
                // 协程取消会通过 delay() / provider.poll() 传播,无需显式检查 isActive

                val ageMs = System.currentTimeMillis() - startedAt
                tickCount++

                // 进度回调:每 5s 触发一次(每个 tick)
                val elapsedSec = ageMs / 1000
                if (onProgress != null && elapsedSec != lastProgressSec) {
                    lastProgressSec = elapsedSec
                    onProgress.invoke(elapsedSec)
                }

                // 自适应频率判断
                if (shouldCheckThisTick(ageMs, tickCount)) {
                    try {
                        val pollResult = provider.poll(taskId)

                        // 取消围栏:poll 返回后检查是否已被取消
                        if (cancelledTasks.remove(taskId) != null) {
                            throw CancellationException("任务已被用户取消: taskId=$taskId")
                        }

                        when (pollResult.status) {
                            PollStatus.SUCCESS -> {
                                consecutiveErrors = 0
                                val url = pollResult.videoUrl
                                    ?: error("${provider.providerId} 任务成功但未返回 videoUrl")
                                return@withTimeoutOrNull url
                            }
                            PollStatus.FAILED -> {
                                consecutiveErrors = 0
                                error(pollResult.errorMessage ?: "${provider.providerId} 任务失败")
                            }
                            PollStatus.PENDING -> {
                                // PENDING + errorMessage 表示查询本身出错(网络/HTTP 错误)
                                if (pollResult.errorMessage != null) {
                                    consecutiveErrors++
                                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                                        error(
                                            "${provider.providerId} 任务连续 $consecutiveErrors 次查询失败," +
                                                "最后错误: ${pollResult.errorMessage}",
                                        )
                                    }
                                    Logger.w(
                                        TAG,
                                        "查询失败 (${consecutiveErrors}/${MAX_CONSECUTIVE_ERRORS})" +
                                            ",将重试: ${pollResult.errorMessage}",
                                    )
                                } else {
                                    // 纯 PENDING(正常处理中),重置错误计数
                                    consecutiveErrors = 0
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        consecutiveErrors++
                        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                            error(
                                "${provider.providerId} 任务连续 $consecutiveErrors 次查询异常: ${e.message}",
                            )
                        }
                        Logger.w(
                            TAG,
                            "查询异常 (${consecutiveErrors}/${MAX_CONSECUTIVE_ERRORS})" +
                                ",将重试: ${e.message}",
                        )
                    }
                }

                delay(TICK_MS)
            }
            null
        } ?: error("${provider.providerId} 任务超时(taskId=$taskId, timeoutMs=${timeoutMs})")

        // 清理取消围栏(任务已正常完成)
        cancelledTasks.remove(taskId)
        return result
    }

    companion object {
        private const val TAG = "VideoGenerationService"

        /** 轮询 tick 间隔(5 秒)。 */
        private const val TICK_MS = 5_000L

        /** 2 分钟阈值(毫秒)。 */
        private const val TWO_MINUTES_MS = 2L * 60 * 1000

        /** 10 分钟阈值(毫秒)。 */
        private const val TEN_MINUTES_MS = 10L * 60 * 1000

        /** 连续查询错误上限(达到后判失败)。 */
        private const val MAX_CONSECUTIVE_ERRORS = 5

        /** 默认总超时(10 分钟)。 */
        private const val DEFAULT_TIMEOUT_MS = 10L * 60 * 1000

        /**
         * 判断当前 tick 是否应该执行查询(自适应频率)。
         *
         * - ageMs < 2min:   每个 tick 都查(5s 间隔)
         * - 2-10min:        每 3 个 tick 查一次(15s 间隔)
         * - >10min:         每 6 个 tick 查一次(30s 间隔)
         *
         * 对齐参考实现 openhanako plugins/image-gen/lib/poller.ts shouldCheckThisTick。
         */
        internal fun shouldCheckThisTick(ageMs: Long, tickCount: Int): Boolean {
            return when {
                ageMs < TWO_MINUTES_MS -> true
                ageMs < TEN_MINUTES_MS -> tickCount % 3 == 0
                else -> tickCount % 6 == 0
            }
        }
    }
}

/**
 * 视频任务状态(UI 层展示用)。
 *
 * v1.137: 保留给 UI 层(VideoGenerationPage)使用;内部逻辑使用 [PollStatus]。
 * [PROCESSING] 仍保留用于 UI 区分"已开始处理"与"排队中"的展示,
 * 但 [PollStatus] 不再区分二者(都归入 PENDING)。
 */
enum class VideoTaskStatus {
    /** 排队中(任务已提交,尚未开始处理)。 */
    PENDING,

    /** 处理中(模型正在生成视频)。 */
    PROCESSING,

    /** 成功(视频已生成,可下载视频 URL)。 */
    SUCCESS,

    /** 失败(任务出错或被拒绝)。 */
    FAILED,
}
