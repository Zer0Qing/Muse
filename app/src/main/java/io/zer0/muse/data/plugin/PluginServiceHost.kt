package io.zer0.muse.data.plugin

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
import io.zer0.muse.util.GlobalCoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * 前台服务型插件（`runtime.service`）的宿主。
 *
 * 一个 Android 前台服务，承载**所有**拿到授权的 `kind = "service"` 插件：
 * 每个插件启动时调用一次 `onServiceStart`，然后服务一直挂着（带常驻通知）。
 *
 * ## 为什么只开一个服务，而不是每个插件一个
 *
 * 每个插件开一个前台服务等于每个插件都占一条常驻通知和一份进程优先级预算 ——
 * 用户装三个插件就会看到三条常驻通知，那是骚扰而不是功能。这里收成一个：
 * 一条通知说清"有几个插件服务在跑"，用户想停就停。
 *
 * ## 停止的语义
 *
 * 停止服务只停**服务宿主**，不改插件本身的启用状态与授权 —— 用户点"停止"
 * 表达的是"现在别在后台跑"，不是"把这个插件卸载"。
 */
class PluginServiceHostService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + GlobalCoroutineExceptionHandler)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Logger.i(TAG, "用户停止插件服务")
            stopSelf()
            return START_NOT_STICKY
        }
        // 安全模式总闸关掉时连服务都不该起来
        if (PluginKillSwitch.isDisabled(this)) {
            Logger.i(TAG, "安全模式总闸已关，不启动插件服务")
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        scope.launch { runEligiblePlugins() }
        // 不 sticky：被系统回收后由启动路径（前台/开机）重新拉，不在后台静默复活
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runEligiblePlugins() {
        val koin = runCatching { GlobalContext.get() }.getOrNull() ?: return
        val runner = runCatching { koin.get<PluginActivationBridge>() }.getOrNull() ?: return
        val manager = runCatching { koin.get<PluginManager>() }.getOrNull() ?: return
        val ledger = PluginCapabilityLedgers.shared(this)
        val eligible = PluginServiceEligibility.select(manager.list()) { pluginId ->
            ledger.isApproved(pluginId, PluginCapabilityGateway.RUNTIME_SERVICE)
        }
        Logger.i(TAG, "插件服务启动：${eligible.size} 个插件")
        eligible.forEach { plugin ->
            runner.invokeServiceStart(plugin.id)
        }
        if (eligible.isEmpty()) stopSelf()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        ensureChannel(manager)
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, PluginServiceHostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quick_capture_close)
            .setContentTitle(getString(R.string.plugin_service_notification_title))
            .setContentText(getString(R.string.plugin_service_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.plugin_service_notification_stop), stopIntent)
            .build()
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.plugin_service_channel_name),
                // 低优先级：常驻通知是"状态"，不是"提醒"
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.plugin_service_channel_desc)
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val TAG = "PluginServiceHost"
        private const val CHANNEL_ID = "plugin_service"
        private const val NOTIFICATION_ID = 1017
        private const val ACTION_STOP = "io.zer0.muse.action.STOP_PLUGIN_SERVICE"

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, PluginServiceHostService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Logger.w(TAG, "启动插件服务失败: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PluginServiceHostService::class.java)) }
        }
    }
}

/**
 * 哪些插件该进服务宿主。
 *
 * 抽成纯函数是为了能直接测：这段判定错了的表现是"插件装了但不跑"或"没授权的也跑"，
 * 两者都不会报错，只能靠测试盯住。
 */
object PluginServiceEligibility {

    /** 只有 `kind = "service"` 的插件进服务宿主；工具型插件不该被常驻拉起。 */
    const val KIND_SERVICE = "service"

    fun select(
        plugins: List<PluginManager.InstalledPlugin>,
        isGranted: (String) -> Boolean,
    ): List<PluginManager.InstalledPlugin> = plugins
        .filter { it.enabled && it.kind == KIND_SERVICE }
        .filter { isGranted(it.id) }
        .take(MAX_SERVICE_PLUGINS)

    /** 一个服务宿主最多承载几个插件：再多，那条常驻通知会变得没有意义。 */
    const val MAX_SERVICE_PLUGINS = 5
}
