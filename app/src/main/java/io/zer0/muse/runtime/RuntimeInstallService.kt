package io.zer0.muse.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.feature.FeatureFlags
import io.zer0.muse.data.agentbot.BotTaskScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import io.zer0.muse.util.GlobalCoroutineExceptionHandler
import java.io.File
import org.koin.core.context.GlobalContext

/**
 * 运行环境安装的前台服务。
 *
 * 为什么不用 WorkManager：76MB 的下载在慢网下会超过它的时间窗，且后台任务更容易被系统掐断。
 * 前台服务有常驻通知、有明确的生命周期，用户能看见进度，也不容易被省电策略杀掉。
 *
 * 安装逻辑本身在 [RuntimeInstaller] 里；这个服务只负责「让它在后台跑完，并把进度显示出来」。
 */
class RuntimeInstallService : Service() {

    // 接全局异常处理器：安装失败若只冒泡到线程默认处理器，用户只会看到进度条卡住。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + GlobalCoroutineExceptionHandler)
    private var installJob: Job? = null
    private var lastPercent = -1
    private var lastUpdateAt = 0L

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // 服务创建即进前台：Android 14 起超时未进前台会崩，和 ChatGenerationService 同一处理。
        startForegroundCompat(buildNotification(getString(R.string.runtime_notif_installing), null, null))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            installJob?.cancel()
            stopSelf()
            return START_NOT_STICKY
        }
        if (installJob?.isActive == true) {
            Logger.i(TAG, "已有安装在进行，忽略重复启动")
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra(EXTRA_RUNTIME_ID)
            ?.let { RuntimeId.entries.firstOrNull { r -> r.manifestKey == it } }
            ?: RuntimeId.UBUNTU_NODE

        val manager = RuntimeManager(
            context = applicationContext,
            installer = RuntimeInstaller(applicationContext, RuntimeStore(applicationContext)),
        )

        installJob = scope.launch {
            // 进度先接到通知上
            launch {
                manager.progress.collectLatest { p ->
                    p ?: return@collectLatest
                    maybeUpdateNotification(p)
                }
            }
            val result = when (intent?.action) {
                ACTION_INSTALL_REVISION -> {
                    val revision = intent?.getStringExtra(EXTRA_REVISION)
                    if (revision.isNullOrBlank()) {
                        Result.failure(IllegalArgumentException("没有选择运行环境版本"))
                    } else {
                        manager.installSelected(id, revision)
                    }
                }
                ACTION_INSTALL_LOCAL -> {
                    val revision = intent?.getStringExtra(EXTRA_REVISION)
                    val archivePath = intent?.getStringExtra(EXTRA_ARCHIVE_PATH)
                    val archive = archivePath?.let(::validatedLocalArchive)
                    if (revision.isNullOrBlank() || archive == null) {
                        Result.failure(IllegalArgumentException("本地运行环境归档不可用"))
                    } else {
                        manager.installFromArchive(id, revision, archive)
                    }
                }
                ACTION_INSTALL_CUSTOM -> {
                    val revision = intent?.getStringExtra(EXTRA_REVISION)
                    val url = intent?.getStringExtra(EXTRA_CUSTOM_URL)
                    val sha = intent?.getStringExtra(EXTRA_CUSTOM_SHA256)
                    if (revision.isNullOrBlank() || url.isNullOrBlank()) {
                        Result.failure(IllegalArgumentException("自定义运行环境来源不完整"))
                    } else {
                        manager.installCustomUrl(id, revision, url, sha)
                    }
                }
                ACTION_REPAIR -> manager.repair(id)
                ACTION_RESUME -> manager.resume(id)
                null -> manager.resume(id).let { resumed -> if (resumed.isSuccess) resumed else manager.install(id) }
                else -> manager.install(id)
            }
            if (result.isSuccess && FeatureFlags.AGENT_BOT) {
                try {
                    GlobalContext.getOrNull()?.get<BotTaskScheduler>()?.resumeRuntimeMissingTasks()
                } catch (error: Exception) {
                    Logger.w(TAG, "运行环境就绪后的任务续跑失败: ${error.message}", error)
                }
            }
            result.onSuccess {
                if (intent?.action == ACTION_INSTALL_LOCAL) {
                    intent.getStringExtra(EXTRA_ARCHIVE_PATH)?.let(::validatedLocalArchive)?.delete()
                }
                notifyFinal(getString(R.string.runtime_notif_done))
                Logger.i(TAG, "${id.manifestKey} 安装完成")
            }.onFailure { e ->
                notifyFinal(getString(R.string.runtime_notif_failed, e.message ?: ""))
                Logger.w(TAG, "${id.manifestKey} 安装失败: ${e.message}")
            }
            // 留一点时间让用户看清结果，然后收工
            kotlinx.coroutines.delay(FINAL_NOTIFICATION_MS)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 进度更新要节流：每 1% 或每 500ms 刷一次，避免通知栏被刷爆。 */
    private fun maybeUpdateNotification(progress: RuntimeInstaller.Progress) {
        val now = System.currentTimeMillis()
        when (progress) {
            is RuntimeInstaller.Progress.Downloading -> {
                val percent = if (progress.total > 0) {
                    ((progress.downloaded * 100) / progress.total).toInt()
                } else {
                    -1
                }
                if (percent >= 0 && percent == lastPercent) return
                if (now - lastUpdateAt < NOTIFY_THROTTLE_MS) return
                lastPercent = percent
                lastUpdateAt = now
                notificationManager.notify(
                    NOTIFY_ID,
                    buildNotification(
                        getString(R.string.runtime_notif_installing),
                        percent.takeIf { it >= 0 },
                        null,
                    ),
                )
            }
            RuntimeInstaller.Progress.Done -> {
                lastUpdateAt = now
                notificationManager.notify(
                    NOTIFY_ID,
                    buildNotification(getString(R.string.runtime_notif_done), null, null),
                )
            }
            else -> {
                if (now - lastUpdateAt < NOTIFY_THROTTLE_MS) return
                lastUpdateAt = now
                notificationManager.notify(
                    NOTIFY_ID,
                    buildNotification(getString(R.string.runtime_notif_installing), null, null),
                )
            }
        }
    }

    private fun notifyFinal(text: String) {
        runCatching {
            notificationManager.notify(NOTIFY_ID, buildNotification(text, null, text))
        }
    }

    private fun buildNotification(title: String, progressPercent: Int?, extra: String?): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(progressPercent != null)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (progressPercent != null) {
            builder.setProgress(100, progressPercent.coerceIn(0, 100), false)
        } else {
            if (title == getString(R.string.runtime_notif_installing)) {
                builder.setProgress(0, 0, true)
            } else {
                builder.setProgress(0, 0, false)
            }
        }
        if (progressPercent != null || title == getString(R.string.runtime_notif_installing)) {
            val cancelIntent = Intent(this, RuntimeInstallService::class.java).apply { action = ACTION_CANCEL }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val cancelPendingIntent = PendingIntent.getService(this, CANCEL_REQUEST_CODE, cancelIntent, flags)
            builder.addAction(android.R.drawable.ic_media_pause, getString(R.string.common_cancel), cancelPendingIntent)
        }
        extra?.takeIf { it.isNotBlank() }?.let { builder.setContentText(it) }
        return builder.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.runtime_notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.runtime_notif_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFY_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFY_ID, notification)
        }
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    companion object {
        private const val TAG = "RuntimeInstallService"
        private const val CHANNEL_ID = "runtime_install"
        private const val NOTIFY_ID = 0x5254
        private const val NOTIFY_THROTTLE_MS = 500L
        private const val FINAL_NOTIFICATION_MS = 1_500L

        const val ACTION_START = "io.zer0.muse.runtime.INSTALL"
        const val ACTION_CANCEL = "io.zer0.muse.runtime.CANCEL"
        const val ACTION_INSTALL_REVISION = "io.zer0.muse.runtime.INSTALL_REVISION"
        const val ACTION_INSTALL_LOCAL = "io.zer0.muse.runtime.INSTALL_LOCAL"
        const val ACTION_INSTALL_CUSTOM = "io.zer0.muse.runtime.INSTALL_CUSTOM"
        const val ACTION_REPAIR = "io.zer0.muse.runtime.REPAIR"
        const val ACTION_RESUME = "io.zer0.muse.runtime.RESUME"
        const val EXTRA_RUNTIME_ID = "runtime_id"
        const val EXTRA_REVISION = "runtime_revision"
        const val EXTRA_ARCHIVE_PATH = "runtime_archive_path"
        const val EXTRA_CUSTOM_URL = "runtime_custom_url"
        const val EXTRA_CUSTOM_SHA256 = "runtime_custom_sha256"
        private const val CANCEL_REQUEST_CODE = 0x5255

        /** 从任意位置发起安装。 */
        fun start(context: Context, id: RuntimeId = RuntimeId.UBUNTU_NODE) {
            startService(context, Intent(context, RuntimeInstallService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RUNTIME_ID, id.manifestKey)
            })
        }

        fun startSelected(context: Context, revision: String, id: RuntimeId = RuntimeId.UBUNTU_NODE) {
            require(RuntimeStore.isSafeRevision(revision)) { "运行环境版本目录名非法" }
            startService(context, Intent(context, RuntimeInstallService::class.java).apply {
                action = ACTION_INSTALL_REVISION
                putExtra(EXTRA_RUNTIME_ID, id.manifestKey)
                putExtra(EXTRA_REVISION, revision)
            })
        }

        fun startLocal(context: Context, revision: String, archiveFile: File, id: RuntimeId = RuntimeId.UBUNTU_NODE) {
            require(RuntimeStore.isSafeRevision(revision)) { "运行环境版本目录名非法" }
            val destination = localArchiveDir(context).apply { mkdirs() }.canonicalFile
            val source = archiveFile.canonicalFile
            require(source.isFile && source.path.startsWith(destination.path + File.separator)) {
                "本地运行环境归档必须位于应用私有导入目录"
            }
            startService(context, Intent(context, RuntimeInstallService::class.java).apply {
                action = ACTION_INSTALL_LOCAL
                putExtra(EXTRA_RUNTIME_ID, id.manifestKey)
                putExtra(EXTRA_REVISION, revision)
                putExtra(EXTRA_ARCHIVE_PATH, source.absolutePath)
            })
        }

        fun startRepair(context: Context, id: RuntimeId = RuntimeId.UBUNTU_NODE) {
            startService(context, Intent(context, RuntimeInstallService::class.java).apply {
                action = ACTION_REPAIR
                putExtra(EXTRA_RUNTIME_ID, id.manifestKey)
            })
        }

        fun startResume(context: Context, id: RuntimeId = RuntimeId.UBUNTU_NODE) {
            startService(context, Intent(context, RuntimeInstallService::class.java).apply {
                action = ACTION_RESUME
                putExtra(EXTRA_RUNTIME_ID, id.manifestKey)
            })
        }

        fun startCustomUrl(context: Context, revision: String, url: String, sha256: String?) {
            require(RuntimeStore.isSafeRevision(revision)) { "运行环境版本目录名非法" }
            startService(context, Intent(context, RuntimeInstallService::class.java).apply {
                action = ACTION_INSTALL_CUSTOM
                putExtra(EXTRA_RUNTIME_ID, RuntimeId.UBUNTU_NODE.manifestKey)
                putExtra(EXTRA_REVISION, revision)
                putExtra(EXTRA_CUSTOM_URL, url)
                putExtra(EXTRA_CUSTOM_SHA256, sha256)
            })
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, RuntimeInstallService::class.java).apply { action = ACTION_CANCEL })
        }

        private fun startService(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        private fun localArchiveDir(context: Context): File = File(context.filesDir, "runtime/imports")
    }

    private fun validatedLocalArchive(path: String): File? {
        val root = File(filesDir, "runtime/imports").canonicalFile
        val archive = File(path).canonicalFile
        return archive.takeIf {
            it.isFile && (it.parentFile?.canonicalPath == root.path || it.canonicalPath.startsWith(root.path + File.separator))
        }
    }
}
