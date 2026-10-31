package io.zer0.muse.web

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.zer0.muse.BuildConfig
import io.zer0.muse.automation.AutomationInitializer
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.stats.AutoBackupLogDao
import io.zer0.muse.data.stats.AutoBackupLogEntity
import io.zer0.muse.notification.MuseNotificationListenerService
import io.zer0.muse.tools.system.ShizukuAuthorizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 系统状态只读命令(`system.status`)的载荷投影。
 *
 * 回执只做「数据源 → 协议 DTO」的搬运:没有测量、没有探测,拿不到的部分保持 DTO 的默认值。
 */
internal object HostSystemStatusReads {

    const val TYPE = "system.status"

    /** `system.status` 回执。 */
    fun event(status: HostSystemStatus, requestId: String?): HostEvent = HostEvent(
        type = TYPE,
        requestId = requestId,
        systemStatus = status,
    )
}

/**
 * 系统状态只读命令的数据源。
 *
 * 抽成接口是为了让网关在没有 Android 运行时(测试、最小装配)的场景下也能照常回执:
 * 未装配时下发一份全默认的状态,而不是让命令报错。
 */
interface HostSystemStatusSource {

    /** 当下一次测量结果。 */
    suspend fun status(): HostSystemStatus

    /** 未装配时的空实现:版本/体积/备份/能力位全为默认值,电量 null。 */
    object Empty : HostSystemStatusSource {
        override suspend fun status(): HostSystemStatus = HostSystemStatus()
    }
}

/**
 * 按目录统计占用字节数。
 *
 * ## 口径
 *
 * 只算 [databaseDir] / [mediaDir] / [voiceDir] / [workspaceDir] / [cacheDir] 这五个目录,
 * 各自递归求和;`totalAppBytes` 是这五项之和。不遍历整个沙盒:日志、崩溃转储、临时文件
 * 加起来通常不到这些目录的零头,却要多走几千个目录项,对「看一眼占了多少」没有帮助。
 * 某个目录不存在(新装、被清理过)按 0 计。
 *
 * ## 线程
 *
 * 求和是一次完整目录遍历,文件多的设备上可能几十毫秒到几百毫秒,因此 [measure] 固定在
 * [Dispatchers.IO] 上跑,不占调用方协程(WebSocket 连接协程)的线程。
 *
 * 根目录全部可注入:生产装配走 [of],测试给临时目录。
 */
internal class HostStorageUsage(
    private val databaseDir: File?,
    private val mediaDir: File?,
    private val voiceDir: File?,
    private val workspaceDir: File?,
    private val cacheDir: File?,
) {

    /** 统计一次;任一项读不到按 0 计,不抛异常。 */
    suspend fun measure(): HostStorageBytes = withContext(Dispatchers.IO) {
        val database = sizeOf(databaseDir)
        val media = sizeOf(mediaDir)
        val voice = sizeOf(voiceDir)
        val workspace = sizeOf(workspaceDir)
        val cache = sizeOf(cacheDir)
        HostStorageBytes(
            databaseBytes = database,
            mediaBytes = media,
            voiceBytes = voice,
            workspaceBytes = workspace,
            cacheBytes = cache,
            totalAppBytes = database + media + voice + workspace + cache,
        )
    }

    companion object {
        /** 消息图片目录名,与 [WebServer] 登记媒体路由用的目录一致。 */
        private const val IMAGE_MEDIA_DIR = "muse_images"

        /** 语音存档目录名,与 [WebServer] 登记媒体路由用的目录一致。 */
        private const val VOICE_MEDIA_DIR = "voice_messages"

        /** 工作区目录名,与 [io.zer0.muse.workspace.WorkspaceManager] 的默认挂载目录一致。 */
        private const val WORKSPACE_DIR = "workspace"

        /** 主数据库文件名,只用来反推 `databases/` 目录。 */
        private const val MAIN_DB_NAME = "muse.db"

        /** 生产装配:目录取自应用沙盒。 */
        fun of(context: Context): HostStorageUsage = HostStorageUsage(
            databaseDir = context.getDatabasePath(MAIN_DB_NAME).parentFile,
            mediaDir = File(context.filesDir, IMAGE_MEDIA_DIR),
            voiceDir = File(context.filesDir, VOICE_MEDIA_DIR),
            workspaceDir = File(context.filesDir, WORKSPACE_DIR),
            cacheDir = context.cacheDir,
        )
    }

    /** 单个目录的递归字节数;目录不存在或不可读按 0 计。 */
    private fun sizeOf(dir: File?): Long {
        val root = dir?.takeIf { it.isDirectory } ?: return 0L
        return runCatching { root.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
    }
}

/**
 * 备份状态映射。
 *
 * [latest] 是 `auto_backup_log` 里最近一条成功记录;没有记录时两个时间/体积字段给 null ——
 * 给 0 会让网页端把「从没备份过」显示成「1970 年备份过一次,大小 0」。
 */
internal fun backupStatusOf(
    latest: AutoBackupLogEntity?,
    autoBackupEnabled: Boolean,
    cloudConfigured: Boolean,
    cloudAutoSync: Boolean,
): HostBackupStatus = HostBackupStatus(
    lastBackupAt = latest?.createdAt?.takeIf { it > 0L },
    lastBackupBytes = latest?.fileSizeBytes,
    autoBackupEnabled = autoBackupEnabled,
    cloudBackupEnabled = cloudConfigured && cloudAutoSync,
)

/**
 * 直接读 Android 运行时的系统状态数据源。
 *
 * - 体积:见 [HostStorageUsage] 的口径;
 * - 备份:后台调度总控 + 云备份配置 + `auto_backup_log` 最近一条成功记录;
 * - 能力位:与 App 内部工具预检同一个入口([AutomationManager.liveSnapshot]) —— 它只实时
 *   刷新开销极小的无障碍通道,Shell/Root 沿用最近一次探测结果,不在一次网页请求里跑 `su` 探针;
 * - 电量:[BatteryManager] 的剩余容量 + 充电状态广播,任一读不到就给 null。
 */
internal class AndroidHostSystemStatusSource(
    private val context: Context,
    private val settings: SettingsRepository,
    private val autoBackupLogDao: AutoBackupLogDao,
    private val storage: HostStorageUsage = HostStorageUsage.of(context),
    /** 自动化管理器;App 未初始化自动化模块时为 null,此时六项能力位一律 false。 */
    private val automation: () -> AutomationManager? = {
        if (AutomationInitializer.isInitialized) AutomationInitializer.manager else null
    },
) : HostSystemStatusSource {

    override suspend fun status(): HostSystemStatus = HostSystemStatus(
        appVersion = BuildConfig.VERSION_NAME,
        appVersionCode = BuildConfig.VERSION_CODE.toLong(),
        uptimeMs = processUptimeMs(),
        storage = storage.measure(),
        backup = backupStatus(),
        capabilities = capabilities(),
        battery = batteryStatus(),
    )

    /** 本次进程已运行的时长;取不到按 0 计。 */
    private fun processUptimeMs(): Long = runCatching {
        (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()).coerceAtLeast(0L)
    }.getOrDefault(0L)

    private suspend fun backupStatus(): HostBackupStatus {
        val latest = runCatching { autoBackupLogDao.getLatestSuccess() }.getOrNull()
        val scheduleWorkEnabled = runCatching { settings.scheduleWorkEnabledFlow.first() }.getOrDefault(false)
        val cloud = runCatching { settings.cloudBackupConfigFlow.first() }.getOrNull()
        return backupStatusOf(
            latest = latest,
            autoBackupEnabled = scheduleWorkEnabled,
            cloudConfigured = cloud?.isConfigured == true,
            cloudAutoSync = cloud?.autoSync == true,
        )
    }

    private fun capabilities(): HostDeviceCapabilities {
        val state = automation()?.liveSnapshot()
        val shell = state?.shellEnabled == true
        val root = state?.rootEnabled == true
        return HostDeviceCapabilities(
            accessibility = state?.accessibilityEnabled == true,
            shell = shell,
            root = root,
            shizuku = state?.shizukuState == ShizukuAuthorizer.ShizukuState.READY,
            // 虚拟屏的权限门与 Shell/Root 同源:有其中一条通道就能建屏。
            virtualDisplay = shell || root,
            notificationListener = notificationListenerEnabled(),
        )
    }

    /** 通知使用权:监听服务已连上,或系统列表里已勾选本应用(服务尚未绑定的窗口期)。 */
    private fun notificationListenerEnabled(): Boolean = runCatching {
        MuseNotificationListenerService.isConnected() || MuseNotificationListenerService.hasListenerAccess(context)
    }.getOrDefault(false)

    /** 电量;容量与充电状态都读不到时整块给 null。 */
    private fun batteryStatus(): HostBatteryStatus? {
        val manager = runCatching {
            context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        }.getOrNull() ?: return null
        val capacity = runCatching {
            manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrDefault(-1)
        val level = capacity.takeIf { it in 0..100 }
        val charging = chargingState()
        if (level == null && charging == null) return null
        return HostBatteryStatus(level = level, charging = charging)
    }

    /** 从 `ACTION_BATTERY_CHANGED` 的粘性广播读充电状态;状态不明给 null。 */
    private fun chargingState(): Boolean? {
        // 传 null 接收器只是查询系统粘性广播的当前值,没有第三方发送者参与,固定 NOT_EXPORTED;
        // 但标志不能省:ContextCompat 见到 0 会直接抛异常。
        val intent = runCatching {
            ContextCompat.registerReceiver(
                context,
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.getOrNull() ?: return null
        return when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> null
        }
    }
}
