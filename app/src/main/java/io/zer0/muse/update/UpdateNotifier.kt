package io.zer0.muse.update

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.MainActivity
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.notification.MuseNotificationManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 应用更新通知器 — 协调 [UpdateChecker] 与 [SettingsRepository]/[MuseNotificationManager]。
 *
 * 行为:
 *  - 从 [SettingsRepository.lastUpdateCheckTimeFlow] 读上次检查时间,间隔不足 24 小时则跳过
 *    (forceCheck=true 时强制)
 *  - 调用 [UpdateChecker.checkLatestRelease] 拉取最新 Release
 *  - 与当前 versionName 语义比较,发现新版本时:
 *      · 把 [UpdateChecker.ReleaseInfo] 序列化为 JSON 缓存到
 *        [SettingsRepository.latestReleaseInfoFlow](供 UI Banner 读取)
 *      · 通过 [MuseNotificationManager] 已有渠道发通知(复用 CHANNEL_CHAT_COMPLETED)
 *  - 检查完毕更新 lastUpdateCheckTime
 *
 * @param settings 全局配置仓库
 * @param checker GitHub Releases 检查器
 */
class UpdateNotifier(
    private val settings: SettingsRepository,
    private val checker: UpdateChecker,
) {

    /**
     * 检查并通知更新。
     *
     * @param context 用于读取 versionName + 发通知
     * @param forceCheck true 时跳过 24 小时间隔检查(用户手动触发时使用)
     */
    suspend fun checkAndNotify(context: Context, forceCheck: Boolean = false) {
        // 用户可在设置中关闭自动检查;手动检查(forceCheck=true)仍允许
        val enabled = settings.updateCheckEnabledFlow.firstSafe() ?: true
        if (!enabled && !forceCheck) {
            Logger.i(TAG, "update check disabled, skip")
            return
        }
        if (!forceCheck && !shouldCheck()) {
            Logger.d(TAG, "update check interval not elapsed, skip")
            return
        }
        // 记录检查时间(无论是否成功,避免失败时反复重试导致打 GitHub API 频次过高)
        settings.saveLastUpdateCheckTime(System.currentTimeMillis())

        val currentVersion = getCurrentVersionName(context)
        val result = checker.checkLatestRelease()
        val release = result.getOrNull() ?: run {
            val errorMsg = (result as? io.zer0.common.Result.Error)?.message ?: "unknown"
            Logger.w(TAG, "checkLatestRelease failed: $errorMsg")
            return
        }
        if (compareVersions(currentVersion, release.tagName) >= 0) {
            // 当前版本 >= 最新版本,清空缓存的 ReleaseInfo(Banner 不再展示)
            settings.saveLatestReleaseInfo(null)
            Logger.i(TAG, "already up to date: current=$currentVersion, latest=${release.tagName}")
            return
        }
        Logger.i(TAG, "new version found: current=$currentVersion, latest=${release.tagName}")
        // 缓存 ReleaseInfo,UI 通过 latestReleaseInfoFlow 渲染 Banner
        settings.saveLatestReleaseInfo(
            AppJson.encodeToString(UpdateChecker.ReleaseInfo.serializer(), release),
        )
        notifyNewVersion(context, release)
    }

    /**
     * 判断距离上次检查是否已超过 24 小时。
     */
    private suspend fun shouldCheck(): Boolean {
        val lastTime = settings.lastUpdateCheckTimeFlow.firstSafe() ?: 0L
        if (lastTime <= 0L) return true
        val elapsed = System.currentTimeMillis() - lastTime
        return elapsed >= CHECK_INTERVAL_MILLIS
    }

    /**
     * 显示"发现新版本"通知 — 复用 MuseNotificationManager 的 CHANNEL_CHAT_COMPLETED 渠道。
     *
     * 通知标题:"发现新版本"
     * 通知正文:"<tagName> 已发布,点击查看详情"
     * 点击跳转 MainActivity(由用户在应用内点 Banner 完成 APK 下载/查看详情)。
     */
    private fun notifyNewVersion(context: Context, release: UpdateChecker.ReleaseInfo) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val title = context.getString(R.string.update_notif_title)
        val text = context.getString(R.string.update_notif_text, release.tagName)
        val notif = NotificationCompat.Builder(context, MuseNotificationManager.CHANNEL_CHAT_COMPLETED)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(buildMainActivityIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        resultOf { nm.notify(NOTIF_ID_NEW_VERSION, notif) }
            .onError { msg, _ -> Logger.w(TAG, "notify new version failed: $msg") }
    }

    /**
     * 构建 MainActivity 的 PendingIntent(点击通知回到应用)。
     */
    private fun buildMainActivityIntent(context: Context): android.app.PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        } else {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT
        }
        return android.app.PendingIntent.getActivity(context, 0, intent, flags)
    }

    /**
     * 从 PackageManager 读取当前应用的 versionName(如 "1.132")。
     * 读取失败返回 "0",保证后续比较可执行。
     */
    private fun getCurrentVersionName(context: Context): String {
        return resultOf {
            val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "0"
        }.getOrNull() ?: "0"
    }

    companion object {
        private const val TAG = "UpdateNotifier"
        /** 通知 ID(避免与 MuseNotificationManager 现有 ID 冲突,选 9000 段)。 */
        private const val NOTIF_ID_NEW_VERSION = 9001
        /** 自动检查最小间隔:24 小时。 */
        private const val CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

        /**
         * 便捷方法:语义版本比较 — 委托给 [UpdateChecker.compareVersions]。
         */
        fun compareVersions(current: String, latest: String): Int =
            UpdateChecker.compareVersions(current, latest)

        /**
         * 构造用于打开 release 页面 URL 的 [Intent](ACTION_VIEW)。
         * 调用方负责 startActivity / chooser。
         */
        fun buildViewReleaseIntent(htmlUrl: String): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(htmlUrl))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /**
         * 构造用于打开浏览器下载 APK 的 [Intent](ACTION_VIEW)。
         */
        fun buildDownloadApkIntent(downloadUrl: String): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * Flow.first() 的便捷包装 — 失败时返回 null,避免上游异常传播(用于读取设置项的简单场景)。
 * CancellationException 仍向上抛(协程取消不可吞)。
 */
private suspend fun <T> Flow<T>.firstSafe(): T? = try {
    first()
} catch (t: Throwable) {
    if (t is kotlin.coroutines.cancellation.CancellationException) throw t
    Logger.w("UpdateNotifier", "Flow.first() failed: ${t.message}")
    null
}
