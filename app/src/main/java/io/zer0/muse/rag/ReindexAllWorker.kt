package io.zer0.muse.rag

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.MainActivity
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.knowledge.KnowledgeBaseDao
import io.zer0.muse.notification.MuseNotificationManager
import org.koin.core.context.GlobalContext

/**
 * v1.133: 批量重索引 Worker — 在后台重新索引所有 KB 的全部文档。
 *
 * 触发场景:
 *  - 用户在"知识库管理"页点击"重新索引全部"(应用在前台,直接走 [RagService.reindexAllInKbs] 即可)
 *  - 用户在 RAG 设置页调整分块参数后,希望批量重新索引所有文档(走本 Worker,后台执行)
 *  - 升级 embedding 模型后,旧向量维度不匹配 → 走本 Worker
 *
 * 设计:
 *  - 用 setForeground + 进度通知,长任务可被用户感知(Android 12+ 强制要求 foreground service)
 *  - 失败不重试(同 [CloudBackupWorker]):返回 retry 会让 WorkManager 30s 内反复重试
 *  - 通知 channel 复用 [MuseNotificationManager.CHANNEL_WEB_SERVER](低优先级,无声音)
 *  - 进度更新通过 setProgressAsync 同步到 WorkManager,UI 可观察
 *
 * 用法:
 * ```
 * ReindexAllWorker.enqueue(context, kbIds = listOf("default", "kb-xxx"))
 * ```
 */
class ReindexAllWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val koin = resultOf { GlobalContext.get() }.getOrNull()
        if (koin == null) {
            Logger.w(TAG, "Koin 未初始化(Safe Mode?),跳过重索引")
            return Result.success()
        }
        val ragService = resultOf { koin.get<RagService>() }.getOrNull()
            ?: return Result.success().also { Logger.w(TAG, "RagService 解析失败") }
        val settings = resultOf { koin.get<SettingsRepository>() }.getOrNull()
            ?: return Result.success().also { Logger.w(TAG, "SettingsRepository 解析失败") }
        val kbDao = resultOf { koin.get<KnowledgeBaseDao>() }.getOrNull()
            ?: return Result.success().also { Logger.w(TAG, "KnowledgeBaseDao 解析失败") }
        val notifMgr = resultOf { koin.get<MuseNotificationManager>() }.getOrNull()

        // 1. 取要重索引的 KB 列表(未指定则全部)
        val kbIds = inputData.getStringArray(KEY_KB_IDS)?.toList()
            ?: kbDao.getAll().map { it.id }
        if (kbIds.isEmpty()) {
            Logger.i(TAG, "无 KB 需要重索引")
            return Result.success()
        }

        // 2. 切前台服务 + 进度通知
        val totalCount = resultOf { koin.get<io.zer0.muse.data.knowledge.KnowledgeDocDao>().getByKbIds(kbIds).size }
            .getOrNull() ?: 0
        if (totalCount == 0) {
            Logger.i(TAG, "无文档需要重索引(kbIds=$kbIds)")
            return Result.success()
        }
        setForeground(buildForegroundInfo(0, totalCount, applicationContext))
        notifMgr?.ensureChannels()

        // 3. 读取 RAG 配置
        val ragConfig = resultOf { settings.getRagConfig() }.getOrNull() ?: RagConfig()

        // 4. 执行重索引
        Logger.i(TAG, "开始重索引:$totalCount 篇文档,kbIds=$kbIds")
        val failures = ragService.reindexAllInKbs(
            kbIds = kbIds,
            ragConfig = ragConfig,
            onProgress = { current, total ->
                // 更新 WorkManager 进度(非 suspend,可从普通回调调用)
                setProgressAsync(workDataOf(KEY_PROGRESS to current, KEY_TOTAL to total))
                // setForeground 是 suspend,在回调里无法直接调用 — 通过 NotificationManager 直接更新通知
                resultOf { updateProgressNotification(current, total, applicationContext) }
                    .onError { msg, _ -> Logger.w(TAG, "更新进度通知失败: $msg") }
            },
        )
        Logger.i(TAG, "重索引完成:成功 ${totalCount - failures.size}/$totalCount,失败 ${failures.size}")
        return Result.success()
    }

    /**
     * 构建前台服务通知(进度条形式)。
     *
     * 复用 [MuseNotificationManager.CHANNEL_WEB_SERVER] 渠道(低优先级,无声音),
     * 避免新建渠道打扰用户。
     */
    private fun buildForegroundInfo(current: Int, total: Int, context: Context): ForegroundInfo {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = context.getString(R.string.kb_reindex_all)
        val content = if (total > 0) {
            context.getString(R.string.kb_reindex_in_progress, current, total)
        } else {
            title
        }
        val builder = NotificationCompat.Builder(context, MuseNotificationManager.CHANNEL_WEB_SERVER)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (total > 0) {
            builder.setProgress(total, current, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        val notification: Notification = builder.build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID_REINDEX, notification)
        } else {
            @Suppress("DEPRECATION")
            ForegroundInfo(NOTIF_ID_REINDEX, notification)
        }
    }

    /**
     * 直接通过 NotificationManager 更新进度通知(非 suspend,可从回调调用)。
     * 与 [buildForegroundInfo] 共用 [NOTIF_ID_REINDEX] 通知 id,覆盖更新。
     */
    private fun updateProgressNotification(current: Int, total: Int, context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = context.getString(R.string.kb_reindex_all)
        val content = if (total > 0) {
            context.getString(R.string.kb_reindex_in_progress, current, total)
        } else {
            title
        }
        val builder = NotificationCompat.Builder(context, MuseNotificationManager.CHANNEL_WEB_SERVER)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (total > 0) {
            builder.setProgress(total, current, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        nm.notify(NOTIF_ID_REINDEX, builder.build())
    }

    companion object {
        private const val TAG = "ReindexAllWorker"
        const val UNIQUE_WORK_NAME = "muse_reindex_all_worker"
        const val KEY_KB_IDS = "kb_ids"
        const val KEY_PROGRESS = "progress"
        const val KEY_TOTAL = "total"
        private const val NOTIF_ID_REINDEX = 1005

        /**
         * 入队一次性重索引任务。
         *
         * @param context 任意 Context
         * @param kbIds 要重索引的 KB id 列表;为空时重索引所有 KB
         */
        fun enqueue(context: Context, kbIds: List<String> = emptyList()) {
            val request = OneTimeWorkRequestBuilder<ReindexAllWorker>()
                .setInputData(workDataOf(KEY_KB_IDS to kbIds.toTypedArray()))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                androidx.work.ExistingWorkPolicy.REPLACE,
                request,
            )
            Logger.i(TAG, "已入队重索引任务,kbIds=$kbIds")
        }
    }
}
