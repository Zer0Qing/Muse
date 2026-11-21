package io.zer0.muse.data.agentbot

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.notification.MuseNotificationTarget
import io.zer0.muse.util.GlobalCoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * 中枢任务的前台保活容器。
 *
 * ## 为什么需要它
 *
 * 中枢的核心承诺是"交代一件事，它后台做完再告诉你"。没有前台服务，
 * 用户锁屏后系统按 Doze 把进程冻住 —— 任务停在半路，而用户以为它在做。
 * 那比"做不了"更糟：用户会在几小时后发现什么都没发生。
 *
 * ## 它不做什么
 *
 * 不执行任务、不调度、不写库。执行在 [BotTaskScheduler] 的应用级协程里，
 * 这里只是"进程别被杀"的容器 —— 与聊天生成服务同一套做法。
 *
 * ## 空状态为什么要退出
 *
 * 前台服务长期空转会被系统判定为滥用，也会让用户看到一个永远不消失的通知。
 * 所以没有在途任务时，宽限 [EMPTY_STATE_GRACE_MS] 后自行停止。
 */
class AgentBotKeepAliveService : Service() {

    private val notificationManager: MuseNotificationManager by inject()
    private val scheduler: BotTaskScheduler by inject()

    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + GlobalCoroutineExceptionHandler)

    private var observeJob: Job? = null
    private var emptyStateStopJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        // Koin 未启动（Safe Mode / 系统 sticky 重启）时优雅退场。
        // 不这样写的话，后面的 by inject 会抛异常，Service 创建失败会被系统反复重启 ——
        // 用户看到的就是"一开就闪退"。
        if (org.koin.core.context.GlobalContext.getOrNull() == null) {
            Logger.w("AgentBotKeepAlive", "Koin 未启动，放弃保活")
            runCatching { stopSelf() }
            return
        }
        try {
            initForeground()
        } catch (t: Throwable) {
            Logger.e("AgentBotKeepAlive", "onCreate 初始化失败，放弃保活", t)
            runCatching { stopSelf() }
        }
    }

    /** 服务创建即进前台：晚于 5 秒会触发 ForegroundServiceDidNotStartInTimeException。 */
    private fun initForeground() {
        startForegroundCompat(buildNotification(getString(R.string.agent_bot_keepalive_default)))
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Muse:AgentBot")
            .also { it.setReferenceCounted(false) }
        // 不用带超时的 acquire：任务可能跑很久，释放由 stopService / onDestroy 明确负责。
        runCatching { wakeLock?.acquire() }
            .onFailure { Logger.w("AgentBotKeepAlive", "wakelock acquire 失败", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (org.koin.core.context.GlobalContext.getOrNull() == null) {
            runCatching { stopSelf() }
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                stopService()
                return START_NOT_STICKY
            }
            else -> startObserve()
        }
        // 被 ROM 回收后要能恢复：进程还活着时任务仍需前台优先级。
        return START_STICKY
    }

    private fun startObserve() {
        observeJob?.cancel()
        observeJob = serviceScope.launch {
            // 观察调度器是否有在跑的任务：有就保持，没有就宽限后退出。
            while (true) {
                // 读失败时按"还在忙"处理：宁可多保活一会儿，也不能因为一次查询
                // 异常就在任务跑到一半时退出 —— 那正是这个服务要防的事。
                val busy = runCatching { scheduler.hasRunningWork() }.getOrDefault(true)
                if (busy) {
                    emptyStateStopJob?.cancel()
                    emptyStateStopJob = null
                } else if (emptyStateStopJob == null) {
                    emptyStateStopJob = launch {
                        delay(EMPTY_STATE_GRACE_MS)
                        val stillBusy = runCatching { scheduler.hasRunningWork() }.getOrDefault(true)
                        if (!stillBusy) stopService()
                    }.also { job -> job.invokeOnCompletion { emptyStateStopJob = null } }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopService() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { stopSelf() }
    }

    override fun onDestroy() {
        observeJob?.cancel()
        emptyStateStopJob?.cancel()
        serviceScope.cancel()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            .onFailure { Logger.w("AgentBotKeepAlive", "wakelock release 失败", it) }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(title: String) =
        notificationManager.buildGenerationNotification(
            sessionTitle = title,
            currentChars = 0,
            target = MuseNotificationTarget.Chat,
        )

    /** Android 14+ 要求 startForeground 显式声明类型。 */
    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFY_ID, notification)
        }
    }

    companion object {
        private const val NOTIFY_ID = 0x4D55

        const val ACTION_START = "io.zer0.muse.agentbot.START"
        const val ACTION_STOP = "io.zer0.muse.agentbot.STOP"

        /** 空状态宽限。太短会让"刚派工就退出"，太长会让用户看到常驻通知。 */
        private const val EMPTY_STATE_GRACE_MS = 15_000L

        private const val POLL_INTERVAL_MS = 3_000L

        /** 启动保活。由调度器在有任务时调用。 */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(
                    Intent(context, AgentBotKeepAliveService::class.java).setAction(ACTION_START),
                )
            }.onFailure { Logger.w("AgentBotKeepAlive", "启动保活失败", it) }
        }

        /** 主动停止保活。 */
        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, AgentBotKeepAliveService::class.java).setAction(ACTION_STOP),
                )
            }.onFailure { Logger.w("AgentBotKeepAlive", "停止保活失败", it) }
        }
    }
}
