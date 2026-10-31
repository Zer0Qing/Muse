package io.zer0.muse.schedule

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.notification.MuseNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * v2.6.9: 常驻保活前台服务（用户显式开启）。
 *
 * 为什么需要它：
 * 定时任务、AI 主动私信、朋友圈调度都跑在应用进程里。进程被 ROM 回收后，
 * WorkManager 最快也要 15 分钟才回来，中间的消息全部错过。
 * 一个常驻前台服务能把进程钉在前台级别，这是 Android 上唯一「合规且有效」的保活手段。
 *
 * 与 [ChatGenerationService] 的区别：
 * 那个是「生成期间临时进入前台」，生成完就退；这个是「用户主动开启的常驻」，
 * 生命周期由设置开关控制，不随单次生成结束。
 *
 * 诚实边界（写清楚，避免误导用户）：
 *  - Android 强制要求前台服务必须有可见通知，**不能隐藏**。任何声称能隐藏的都不是正规手段。
 *  - 国产 ROM（MIUI/EMUI/ColorOS…）会额外清理后台，仍需用户在系统设置里放自启动白名单，
 *    这部分只能引导、无法自动完成。
 *  - 不做任何伪装成其他应用的形为：改包名/仿冒图标/隐藏图标都属于恶意软件特征，
 *    会被应用商店判定为风险应用。
 *
 * 保活手段（全部合规）：
 *  1. startForeground + 常驻通知（进程优先级提升到前台）
 *  2. 可选 WakeLock（用户开启时持有，防止 Doze 让 CPU 休眠导致网络挂起）
 *  3. onTaskRemoved 触发重启（用户从最近任务划掉后尝试拉起）
 *  4. START_STICKY（系统内存回收后自动重建）
 *  5. 心跳协程定期刷新存活状态（错过的调度由各功能自己的 Worker 与前台补触发负责，不在这里做）
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAliveService"

        const val ACTION_START = "io.zer0.muse.action.KEEP_ALIVE_START"
        const val ACTION_STOP = "io.zer0.muse.action.KEEP_ALIVE_STOP"

        private const val NOTIFICATION_ID = 31001
        private const val CHANNEL_ID = MuseNotificationManager.CHANNEL_KEEP_ALIVE
        private const val HEARTBEAT_INTERVAL_MS = 60_000L

        /** 启动常驻服务。已是常驻状态时重复调用无副作用。 */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, KeepAliveService::class.java).setAction(ACTION_START)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { t ->
                Logger.w(TAG, "启动常驻服务失败: ${t.message}")
            }
        }

        /** 停止常驻服务。 */
        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, KeepAliveService::class.java).setAction(ACTION_STOP))
            }.onFailure { t ->
                Logger.w(TAG, "停止常驻服务失败: ${t.message}")
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        // Android 要求 startForeground 尽快调用，否则 ANR/崩溃。创建即进前台。
        startForegroundCompat()
        startHeartbeat()
        acquireWakeLockIfEnabled()
        Logger.i(TAG, "常驻服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Logger.i(TAG, "收到停止指令")
                stopSelf()
                return START_NOT_STICKY
            }
            else -> Logger.d(TAG, "常驻服务运行中")
        }
        // START_STICKY: 系统内存回收后自动重建服务（不保留 intent）
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 用户从最近任务划掉应用时触发。
     *
     * 这里尝试重新拉起自己——划掉后进程通常还会存活很短时间，
     * 这个窗口内重启服务成功率较高。ROM 深度清理时仍会失败，属预期。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.i(TAG, "任务被划掉，尝试重启常驻服务")
        val restart = Intent(applicationContext, KeepAliveService::class.java).setAction(ACTION_START)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(restart)
            } else {
                applicationContext.startService(restart)
            }
        }.onFailure { t ->
            Logger.w(TAG, "重启常驻服务失败（可能被 ROM 限制）: ${t.message}")
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        heartbeatJob?.cancel()
        releaseWakeLock()
        serviceScope.cancel()
        Logger.i(TAG, "常驻服务已停止")
        super.onDestroy()
    }

    /** 进入前台并挂常驻通知。失败时必须仍调 startForeground，否则系统抛超时异常。 */
    private fun startForegroundCompat() {
        // 渠道统一由通知管理器注册（幂等）。
        MuseNotificationManager(this).ensureChannels()
        val notification = buildNotification()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ 必须显式声明前台服务类型
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { t ->
            Logger.e(TAG, "startForeground 失败，放弃常驻", t)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                    )
                }
            }
        }
    }

    private fun buildNotification(): Notification {
        // v2.6.9: 读取用户自定义通知文案（空串 → 默认）。
        // 读取是 suspend，而这里需要在 startForeground 前同步拿到，
        // 故用 runBlocking 限时 200ms：拿不到就用默认，绝不阻塞启动。
        val (customTitle, customText) = runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(200L) {
                    val settings = GlobalContext.get().get<SettingsRepository>()
                    settings.keepAliveNotificationSnapshot()
                }
            }
        }.getOrNull() ?: ("" to "")
        val title = customTitle.ifBlank { getString(R.string.keep_alive_notification_title) }
        val body = customText.ifBlank { getString(R.string.keep_alive_notification_text) }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_muse_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        // 点击回到应用主界面
        runCatching {
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            if (launch != null) {
                builder.setContentIntent(
                    android.app.PendingIntent.getActivity(
                        this,
                        0,
                        launch,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                            android.app.PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
        }
        return builder.build()
    }

    /**
     * 心跳：定期记录存活状态。
     *
     * 这里刻意只做「轻量唤醒 + 日志」，不直接跑生成任务——
     * 生成任务由 ScheduledTaskRunner / ProactiveMessageRunner 各自的轮询负责，
     * 服务只保证进程活着，不重复实现调度逻辑（否则会出现双份轮询互抢）。
     */
    private fun startHeartbeat() {
        heartbeatJob = serviceScope.launch {
            var tick = 0L
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                tick++
                if (tick % 10 == 0L) {
                    Logger.d(TAG, "常驻心跳存活，已运行 $tick 分钟")
                }
            }
        }
    }

    /** 用户显式开启「屏幕关闭也保持连接」时持有 WakeLock。 */
    private fun acquireWakeLockIfEnabled() {
        val settings = runCatching {
            GlobalContext.get().get<SettingsRepository>()
        }.getOrNull() ?: return
        // 设置读取是 suspend，用 IO scope 异步取，取不到就不持有
        serviceScope.launch {
            val enabled = runCatching { settings.keepAliveWakeLockFlowSnapshot() }.getOrDefault(false)
            if (!enabled) return@launch
            runCatching {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Muse::KeepAlive").apply {
                    setReferenceCounted(false)
                    acquire(24 * 60 * 60 * 1000L) // 最多 24 小时，到期自动释放，避免忘记释放耗电
                }
                Logger.i(TAG, "已获取 WakeLock（用户开启）")
            }.onFailure { t ->
                Logger.w(TAG, "获取 WakeLock 失败: ${t.message}")
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }
}
