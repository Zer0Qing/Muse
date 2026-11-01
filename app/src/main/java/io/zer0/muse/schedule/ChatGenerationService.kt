package io.zer0.muse.schedule

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.RequiresApi
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.notification.MuseNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * v1.43: 聊天生成前台服务。
 *
 * 当用户切到后台时,以前台服务形式保持进程存活,确保 AI 生成不被系统回收中断。
 * - 启动时显示常驻通知(复用 [MuseNotificationManager.updateLiveProgress] 的 ID)
 * - 生成结束后由调用方发送 [ACTION_STOP] 停止服务
 * - 服务本身不执行生成逻辑,仅作为前台保活容器;生成逻辑运行在 [ChatGenerationManager] 的应用级协程中
 *
 * v1.112: 修复 ForegroundServiceDidNotStartInTimeException 崩溃。
 *  - 原:在 onStartCommand 中才调用 startForeground(),若主线程繁忙/Koin 注入/通知构建
 *    耗时超过 5 秒,或传入未知 action 跳过 when 分支,系统抛超时异常直接崩溃。
 *  - 新:在 onCreate() 中立即 startForeground()(服务创建即进前台),onStartCommand 只处理
 *    后续逻辑;when 加 else 兜底;startForeground 传显式 type 适配 Android 14+(SDK 34+)。
 */
class ChatGenerationService : Service() {

    private val notificationManager: MuseNotificationManager by inject()
    private val chatGenerationManager: ChatGenerationManager by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observeJob: Job? = null
    private var emptyStateStopJob: Job? = null
    private val stopGate = ForegroundServiceStopGate()
    private var latestStartId: Int = 0

    // v1.0.15: Wakelock 保活,防止 Doze 模式下 CPU 休眠导致网络挂起
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        stopGate.reset()
        // v2.2.0: Koin 未启动时优雅退场(不崩溃) — 系统 sticky 重启服务可发生在
        // MuseApp 跳过 startKoin 的 Safe Mode 进程里; 此处若无防御, 后续 by inject
        // 会抛 "KoinApplication has not been started" 形成崩溃循环。
        if (org.koin.core.context.GlobalContext.getOrNull() == null) {
            Logger.w("ChatGenService", "Koin 未启动(Safe Mode/进程异常恢复) — 放弃保活")
            runCatching { stopSelf() }
            return
        }
        // 兜底（真机崩溃修复）：**任何**初始化异常都不允许让 Service 创建失败。
        // Service onCreate 抛异常时系统会反复重启它 → 崩溃循环，用户看到的就是「一开就闪退」。
        // 这里的 try 比「只在 Koin 判断上加守卫」更稳：Koin 版本差异、通知构建、WakeLock
        // 任何一处抛 Throwable 都只会导致「这次保活放弃」，而不是整个应用挂掉。
        try {
            initForegroundAndWakeLock()
        } catch (t: Throwable) {
            Logger.e("ChatGenService", "onCreate 初始化失败 — 放弃保活(避免崩溃循环)", t)
            runCatching { stopSelf() }
        }
    }

    /** onCreate 的正常路径：进前台 + 拿 WakeLock。异常由 [onCreate] 统一兜底。 */
    private fun initForegroundAndWakeLock() {
        // v1.112: 服务创建即进入前台状态,避免 onStartCommand 延迟导致 5 秒超时崩溃。
        // 用默认标题构造通知;后续 onStartCommand 会按实际生成状态更新。
        val notification = notificationManager.buildGenerationNotification(
            getString(R.string.schedule_generating_default_title),
            0,
            io.zer0.muse.notification.MuseNotificationTarget.Chat,
        )
        startForegroundCompat(notification)
        // v1.0.15: 获取 PARTIAL_WAKE_LOCK,保证生成期间 CPU 不休眠、网络不被 Doze 挂起
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Muse:ChatGeneration")
            .also { it.setReferenceCounted(false) }
        // 前台服务的生命周期就是生成保活生命周期,不使用 10 分钟超时版本。
        // 长 MCP/工具任务可能超过 10 分钟;stopService/onDestroy 会明确释放锁。
        runCatching { wakeLock?.acquire() }
            .onFailure { Logger.w("ChatGenService", "wakelock acquire 失败", it) }
        Logger.i("ChatGenService", "service created & foreground started & wakelock acquired")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        // v2.2.0: 同 onCreate 的 Koin 防御 — null Intent(sticky 重启)在此场景最常见,
        // 必须在访问任何 by inject 属性前拦截。
        // 2026-09-27 真机崩溃修复: ChatGenerationService 曾是唯一无 Koin 防御的组件,
        // 缺失时抛 "KoinApplication has not been started" 崩溃。
        if (org.koin.core.context.GlobalContext.getOrNull() == null) {
            Logger.w("ChatGenService", "Koin 未启动(Safe Mode?) — 停止服务,不进入观察循环")
            runCatching { stopSelf() }
            return START_NOT_STICKY
        }
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_START -> startObserve()
            ACTION_STOP -> stopService()
            else -> {
                // v1.112: 未知 action 兜底也启动观察,确保不会因跳过分支导致服务空转。
                Logger.w("ChatGenService", "未知 action: $action,按 START 处理")
                startObserve()
            }
        }
        // 服务只负责保活和观察应用级生成管理器;自身被 ROM 回收后应尝试恢复观察,
        // 让仍存活的 appScope 生成继续保持前台优先级。
        return START_STICKY
    }

    private fun startObserve() {
        stopGate.reset()
        // 用最新生成状态更新通知标题
        val active = chatGenerationManager.activeGenerations.value.values
            .maxByOrNull { it.lastUpdatedAt }
        val title = active?.sessionTitle ?: getString(R.string.schedule_generating_default_title)
        try {
            val target = active?.sessionId
                ?.takeIf { it.isNotBlank() }
                ?.let(io.zer0.muse.notification.MuseNotificationTarget::Session)
                ?: io.zer0.muse.notification.MuseNotificationTarget.Chat
            val notification = notificationManager.buildGenerationNotification(
                title,
                0,
                target,
            )
            startForegroundCompat(notification)
        } catch (t: Throwable) {
            Logger.w("ChatGenService", "更新前台通知失败", t)
        }

        // 监听生成状态变化,更新通知内容/进度
        observeJob?.cancel()
        observeJob = serviceScope.launch {
            chatGenerationManager.activeGenerations.collect { generations ->
                if (generations.isEmpty()) {
                    // 给“启动服务”和“登记任务”留一个很短的竞态窗口;如果进程重启后
                    // manager 没有任何任务,则快速退出,不要让 START_STICKY 服务空耗 15 分钟。
                    if (emptyStateStopJob == null) {
                        emptyStateStopJob = launch {
                            delay(EMPTY_STATE_GRACE_MS)
                            if (chatGenerationManager.activeGenerations.value.isEmpty()) {
                                stopService()
                            }
                        }.also { job ->
                            job.invokeOnCompletion { emptyStateStopJob = null }
                        }
                    }
                    return@collect
                }
                emptyStateStopJob?.cancel()
                emptyStateStopJob = null
                val stale = generations.values.firstOrNull { generation ->
                    System.currentTimeMillis() - generation.lastUpdatedAt > HEARTBEAT_TIMEOUT_MS
                }
                if (stale != null) {
                    Logger.w("ChatGenService", "生成心跳超时,停止该会话(会话: ${stale.sessionTitle})")
                    // 多会话并发时只取消失去心跳的任务,不能把其他正常任务的前台保活
                    // 一起关掉。取消路径会由 ChatViewModel 持久化部分回复/检查点。
                    chatGenerationManager.stop(stale.sessionId)
                    return@collect
                }
                // 多会话并发时通知展示最近更新的任务;保活判定使用完整快照。
                // v2.5.0 fix: 展示真实字数(此前恒传 0,通知文案字数不变,用户误以为卡死)。
                val gen = generations.values.maxByOrNull { it.lastUpdatedAt } ?: return@collect
                notificationManager.updateLiveProgress(
                    gen.sessionTitle,
                    gen.currentChars,
                    true,
                    io.zer0.muse.notification.MuseNotificationTarget.Session(gen.sessionId),
                )
            }
        }
    }

    private fun stopService() {
        if (!stopGate.tryRequest()) return
        observeJob?.cancel()
        observeJob = null
        emptyStateStopJob?.cancel()
        emptyStateStopJob = null
        // v1.0.15: 释放 wakelock
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        // v2.5.0 fix: 先取消通知再退前台 —— startForeground 的通知无法被 nm.cancel
        // 单独移除(系统会恢复),必须先 stopForeground;反过来先 cancel 会被前台身份吞掉,
        // 通知栏残留一条字数不变的"动态进度条"(用户实测)。
        notificationManager.clearLiveProgress()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            .onFailure { Logger.w("ChatGenService", "移除前台状态失败", it) }
        if (latestStartId > 0) {
            stopSelfResult(latestStartId)
        } else {
            stopSelf()
        }
        Logger.i("ChatGenService", "service stopped")
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, foregroundServiceType: Int) {
        Logger.w(
            "ChatGenService",
            "前台服务达到系统时限,取消活跃生成并停止服务 (startId=$startId, type=$foregroundServiceType)",
        )
        runCatching { stopService() }
            .onFailure { Logger.e("ChatGenService", "系统超时后停止前台服务失败", it) }
        runCatching { chatGenerationManager.stop() }
            .onFailure { Logger.e("ChatGenService", "系统超时后取消生成失败", it) }
    }

    /**
     * v1.112: 兼容 Android 14+(SDK 34+)的前台服务启动。
     *
     * Android 14 起要求 startForeground 显式传入 foregroundServiceType,且必须与 manifest
     * 声明的类型一致(本服务声明为 dataSync)。两参重载在部分 OEM ROM 上会因类型推断
     * 失败而触发 ForegroundServiceDidNotStartInTimeException。
     */
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        observeJob?.cancel()
        emptyStateStopJob?.cancel()
        // v1.0.15: 释放 wakelock(防御性:stopService 已释放,这里兜底)
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        // M-009: 取消 serviceScope,避免服务销毁后仍有协程持有引用导致泄漏
        serviceScope.cancel()
        // v2.5.0 fix: 兼底清理进度通知 —— 服务被系统强杀时 stopService 不会执行,
        // 通知栏会残留一条字数不变的动态进度条(用户实测"退出应用也一直在")。
        notificationManager.clearLiveProgress()
    }

    companion object {
        private const val NOTIFICATION_ID = 2001

        /** P1 审计: 生成心跳超时阈值(15 分钟无任何进度则停止保活,避免永久常驻)。 */
        private const val HEARTBEAT_TIMEOUT_MS = 15 * 60 * 1000L

        /** 服务重启后没有任务时的短暂竞态缓冲,避免 START_STICKY 空转。 */
        private const val EMPTY_STATE_GRACE_MS = 5_000L
        const val ACTION_START = "io.zer0.muse.action.START_GENERATION"
        const val ACTION_STOP = "io.zer0.muse.action.STOP_GENERATION"

        fun start(context: Context) {
            val intent = Intent(context, ChatGenerationService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ChatGenerationService::class.java).apply {
                action = ACTION_STOP
            }
            // 停止服务走 stopService,避免再次调用 startForegroundService 后未在 5s 内
            // 调用 startForeground 而触发 ForegroundServiceDidNotStartInTimeException。
            context.stopService(intent)
        }
    }
}
