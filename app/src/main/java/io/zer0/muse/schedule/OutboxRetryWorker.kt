package io.zer0.muse.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.zer0.common.Logger
import io.zer0.muse.data.session.MessageOutboxEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.network.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit

/**
 * v1.0.30: 消息发送出站箱的 WorkManager 兜底 Worker。
 *
 * 当用户发送消息后进程被系统杀死(前台服务被杀),消息可能留在 outbox 表而未投递到 LLM。
 * 本 Worker 通过 WorkManager 周期性调度(最小 15 分钟),进程被杀后由系统拉起重试。
 *
 * doWork 扫描 pending outbox,对每条记录:
 *  - 检查 retryCount < MAX_RETRIES,超限则删除(放弃投递)
 *  - 等待网络可用(最多 30s),无网则跳过本次
 *  - 检查消息是否已存在于 messages 表(避免重复投递)
 *  - 若不存在则重新 appendMessage
 *  - 清除 outbox 记录
 *
 * Koin 拿依赖:与 [ProactiveMessageWorker] 一致 — Worker 启动时 MuseApp.onCreate 已执行。
 */
class OutboxRetryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val koin = GlobalContext.getOrNull()
        if (koin == null) {
            Logger.w("OutboxRetryWorker", "Koin 未初始化,跳过")
            return Result.success()
        }
        return withContext(Dispatchers.IO) {
            val sessionRepo = runCatching { koin.get<SessionRepository>() }.getOrNull()
            val networkMonitor = runCatching { koin.get<NetworkMonitor>() }.getOrNull()
            if (sessionRepo == null) {
                Logger.w("OutboxRetryWorker", "SessionRepository 不可用,跳过")
                return@withContext Result.success()
            }

            val pending = runCatching { sessionRepo.getPendingOutbox() }.getOrNull()
            if (pending.isNullOrEmpty()) {
                return@withContext Result.success()
            }

            Logger.i("OutboxRetryWorker", "发现 ${pending.size} 条待重试 outbox 记录")
            var processed = 0
            for (msg in pending) {
                if (msg.retryCount >= MAX_RETRIES) {
                    Logger.w("OutboxRetryWorker", "outbox ${msg.id} 重试已达上限,删除")
                    runCatching { sessionRepo.deleteOutbox(msg.id) }
                    continue
                }

                // 等待网络恢复
                if (networkMonitor != null) {
                    val online = withTimeoutOrNull(NETWORK_WAIT_MS) {
                        while (!networkMonitor.isOnline.value) {
                            kotlinx.coroutines.delay(1000)
                        }
                        true
                    }
                    if (online != true) {
                        Logger.w("OutboxRetryWorker", "网络不可用,跳过本轮")
                        continue
                    }
                }

                // 消息是否已持久化
                val alreadySaved = runCatching {
                    sessionRepo.messageExists(msg.sessionId, msg.userMessageId)
                }.getOrNull() ?: false

                if (!alreadySaved) {
                    val images = kotlin.runCatching {
                        kotlinx.serialization.json.Json.decodeFromString<List<String>>(msg.imageBase64Json)
                    }.getOrDefault(emptyList())
                    val appendOk = kotlin.runCatching {
                        sessionRepo.appendMessage(msg.sessionId, io.zer0.ai.core.UIMessage(
                            id = kotlin.uuid.Uuid.parse(msg.userMessageId),
                            role = io.zer0.ai.core.MessageRole.USER,
                            content = msg.text,
                            imageBase64List = images,
                            createdAt = msg.createdAt,
                        ))
                        true
                    }.getOrDefault(false)

                    if (!appendOk) {
                        Logger.w("OutboxRetryWorker", "appendMessage 失败")
                        // 增加重试计数后重新插入 outbox(原记录已删,用新计数重新投递)
                        kotlin.runCatching { sessionRepo.deleteOutbox(msg.id) }
                        kotlin.runCatching {
                            sessionRepo.insertOutbox(msg.copy(
                                retryCount = msg.retryCount + 1,
                                lastError = "appendMessage failed",
                            ))
                        }
                        continue
                    }
                }

                kotlin.runCatching { sessionRepo.deleteOutbox(msg.id) }
                processed++
            }
            Logger.i("OutboxRetryWorker", "处理完成: $processed/${pending.size}")
            Result.success()
        }
    }

    companion object {
        private const val MAX_RETRIES = 5
        private const val NETWORK_WAIT_MS = 30_000L
        const val UNIQUE_WORK_NAME = "outbox_retry"

        fun register(context: Context) {
            try {
                val request = PeriodicWorkRequestBuilder<OutboxRetryWorker>(
                    15, TimeUnit.MINUTES,
                ).build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
                Logger.i("OutboxRetryWorker", "WorkManager 兜底已注册(15min)")
            } catch (t: Throwable) {
                Logger.w("OutboxRetryWorker", "注册失败: ${t.message}")
            }
        }
    }
}
