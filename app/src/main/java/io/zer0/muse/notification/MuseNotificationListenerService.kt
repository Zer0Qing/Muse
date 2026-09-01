package io.zer0.muse.notification

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.Serializable

/**
 * 通知监听服务 — 感知其他 App 的通知作为事件源。
 *
 * 用户需在系统设置中授权"通知使用权"才能生效。
 * 授权后,当其他 App 发通知时,本服务会捕获并存储到 recentNotifications。
 * LLM 可通过 get_recent_notifications 工具查询最近的通知。
 *
 * 注意:本类持有静态 StateFlow,服务实例本身被系统绑定/解绑时不影响已采集的通知。
 */
class MuseNotificationListenerService : NotificationListenerService() {

    companion object {
        // L1-1: 过滤的系统包名,避免每次调用分配新 List
        private val IGNORED_PACKAGES: Set<String> = setOf("android", "com.android.systemui", "io.zer0.muse")
        // 审计修复 (1.5): 高敏通知包名 — 银行/支付/验证码类,正文直接不采集
        // (仅保留来源包名与时间,text 置占位),防止验证码/余额/流水经 LLM 外泄。
        private val SENSITIVE_PACKAGES: Set<String> = setOf(
            // 支付/银行(常见国内)
            "com.eg.android.AlipayGphone", "com.tencent.mm", "com.unionpay",
            "com.android.bankabc", "com.chinamworld.bocmbci", "com.icbc", "com.ccb.life",
            "com.cmbchina.ccd.pluto.cmbActivity", "com.android.citic", "com.spdbccc.app",
            // 短信/验证码聚合类
            "com.google.android.apps.messaging", "com.android.mms", "com.miui.securitycenter",
            "com.huawei.hwid", "com.oneplus.security",
            // B-29: 主流 IM / 邮箱 / 社交——自由文本(private 聊天、群里闲聊)经 PII 遮蔽仍挡不住,
            // 正文直接不采集,只保留来源包名与时间。
            // IM/即时通讯
            "com.tencent.mobileqq", "com.alibaba.android.rimet", "org.telegram.messenger",
            "com.slack", "com.tencent.tim", "com.immomo.momo", "com.tencent.wework",
            // 邮箱
            "com.google.android.gm", "com.microsoft.office.outlook",
            // 社交/短视频
            "com.sina.weibo", "com.ss.android.ugc.aweme",
        )
        // 通知监听不仅服务当前进程;保留最近 200 条,应用重启后仍可在页面和工具中查看。
        private const val MAX_RECENT_NOTIFICATIONS = 200
        private const val PREFS_NAME = "muse_notification_listener"
        private const val KEY_RECORDS = "records_json"
        private const val TAG = "MuseNotifListener"
        private const val REBIND_BACKOFF_MS = 30_000L
        private val stateLock = Any()
        private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val persistenceMutex = Mutex()
        // 事件快速到达时,只允许最新快照落盘,防止并发 IO 把旧列表写回。
        private var persistenceSequence = 0L
        private var persistedSequence = 0L
        private var preferences: SharedPreferences? = null
        @Volatile
        private var serviceInstance: MuseNotificationListenerService? = null
        @Volatile
        private var initialized = false
        // 最近通知列表(最多保留 MAX_RECENT_NOTIFICATIONS 条)
        private val _recentNotifications = MutableStateFlow<List<NotificationRecord>>(emptyList())
        val recentNotifications = _recentNotifications.asStateFlow()

        /** 审计修复 (1.5): 对通知文本做 PII 遮蔽,防止验证码/卡号/密码等敏感内容
         * 进入 LLM 上下文并随请求外发。命中敏感规则的内容替换为 [REDACTED]。 */
        private fun scrubText(text: String): String {
            if (text.isBlank()) return text
            return runCatching { io.zer0.memory.pii.PiiGuard.scrub(text).cleaned }
                .onFailure { error -> Logger.w(TAG, "通知正文脱敏失败,隐藏该字段: ${error.message}", error) }
                .getOrDefault("(正文已隐藏)")
        }

        // 是否已连接(用户已授权)
        @Volatile
        private var connected = false
        fun isConnected() = connected

        /** 请求服务从系统重新同步当前仍存在的通知。 */
        fun refreshActiveNotifications() {
            val service = serviceInstance ?: return
            persistenceScope.launch {
                runCatching {
                    val active = service.activeNotifications.orEmpty()
                    markRecordsNotActive(active.map(::sourceKey).toSet())
                    active.forEach(service::recordNotification)
                }.onFailure { error ->
                    Logger.w(TAG, "手动同步当前通知失败: ${error.message}", error)
                }
            }
        }

        /** 根据系统对象构建跨进程稳定键。 */
        private fun sourceKey(sbn: StatusBarNotification): String =
            "${sbn.key}|${sbn.id}|${sbn.tag.orEmpty()}"

        /** 初始化持久化存储;可由 UI 先调用,不必等系统绑定监听服务。 */
        fun initialize(context: Context) {
            synchronized(stateLock) {
                if (initialized) return
                val prefs = context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                preferences = prefs
                val restored = prefs.getString(KEY_RECORDS, null)
                    ?.let { raw ->
                        runCatching {
                            AppJson.decodeFromString(
                                ListSerializer(NotificationRecord.serializer()),
                                raw,
                            )
                        }.onFailure { error ->
                            Logger.w(TAG, "通知记录恢复失败,清空损坏缓存: ${error.message}", error)
                            prefs.edit().remove(KEY_RECORDS).apply()
                        }.getOrNull()
                    }
                    .orEmpty()
                _recentNotifications.value = normalize(restored)
                initialized = true
            }
        }

        /** 当前应用是否已在系统通知使用权列表中。 */
        fun hasListenerAccess(context: Context): Boolean = runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        }.onFailure { error ->
            Logger.w(TAG, "读取通知使用权失败: ${error.message}", error)
        }.getOrDefault(connected)

        /** 查询最近通知(供 ToolRegistry 调用)。 */
        fun getRecent(
            limit: Int = 20,
            packageName: String? = null,
            query: String? = null,
            unreadOnly: Boolean = false,
            activeOnly: Boolean = false,
        ): List<NotificationRecord> {
            val normalizedQuery = query?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            return _recentNotifications.value
                .let { list ->
                    list.filter { record ->
                        (packageName.isNullOrBlank() || record.packageName == packageName) &&
                            (!unreadOnly || !record.isRead) &&
                            (!activeOnly || record.isActive) &&
                            (normalizedQuery == null || record.matches(normalizedQuery))
                    }
                }
                .take(limit.coerceIn(1, MAX_RECENT_NOTIFICATIONS))
        }

        /** 当前已出现过的来源包名,供 UI 筛选菜单使用。 */
        fun getPackages(): List<String> = _recentNotifications.value
            .map { it.packageName }
            .distinct()
            .sorted()

        /** 获取单条记录的稳定键,兼容旧版没有 sourceKey 的记录。 */
        fun keyOf(record: NotificationRecord): String = record.sourceKey
            .takeIf { it.isNotBlank() }
            ?: "legacy:${record.packageName}:${record.timestamp}:${record.title}:${record.text}"

        /** 标记单条已读。 */
        fun markRead(record: NotificationRecord) {
            updateRecords { current ->
                current.map { item ->
                    if (keyOf(item) == keyOf(record)) item.copy(isRead = true) else item
                }
            }
        }

        /** 标记全部已读。 */
        fun markAllRead() {
            updateRecords { current -> current.map { it.copy(isRead = true) } }
        }

        /** 删除单条记录。 */
        fun delete(record: NotificationRecord) {
            updateRecords { current -> current.filterNot { keyOf(it) == keyOf(record) } }
        }

        /** 导出当前已保存的通知记录 JSON,由 UI 通过 SAF 写入用户选择的位置。 */
        fun exportJson(): String = AppJson.encodeToString(
            ListSerializer(NotificationRecord.serializer()),
            _recentNotifications.value,
        )

        /** 清空通知记录。 */
        fun clearAll() {
            updateRecords { emptyList() }
        }

        private fun markRecordsNotActive(activeKeys: Set<String>) {
            updateRecords { current ->
                current.map { record ->
                    if (record.isActive && keyOf(record) !in activeKeys) {
                        record.copy(isActive = false, removedAt = System.currentTimeMillis())
                    } else {
                        record
                    }
                }
            }
        }

        private fun updateRecords(transform: (List<NotificationRecord>) -> List<NotificationRecord>) {
            val snapshot = synchronized(stateLock) {
                val updated = normalize(transform(_recentNotifications.value))
                _recentNotifications.value = updated
                updated
            }
            persistAsync(snapshot)
        }

        private fun persistAsync(snapshot: List<NotificationRecord>) {
            val prefs = preferences ?: return
            val encoded = runCatching {
                AppJson.encodeToString(ListSerializer(NotificationRecord.serializer()), snapshot)
            }.getOrElse { error ->
                Logger.w(TAG, "通知记录序列化失败: ${error.message}", error)
                return
            }
            val sequence = synchronized(stateLock) {
                persistenceSequence += 1
                persistenceSequence
            }
            persistenceScope.launch {
                persistenceMutex.withLock {
                    // IO 调度可能乱序,旧快照即使后拿到锁也不能覆盖新快照。
                    if (sequence < persistedSequence) return@withLock
                    if (prefs.edit().putString(KEY_RECORDS, encoded).commit()) {
                        persistedSequence = sequence
                    }
                }
            }
        }

        private fun normalize(records: List<NotificationRecord>): List<NotificationRecord> = records
            .distinctBy(::keyOf)
            .sortedByDescending { it.timestamp }
            .take(MAX_RECENT_NOTIFICATIONS)
    }

    private var lastRebindRequestAt = 0L

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        initialize(this)
    }

    override fun onDestroy() {
        if (serviceInstance === this) serviceInstance = null
        connected = false
        super.onDestroy()
    }

    override fun onListenerConnected() {
        connected = true
        Logger.i(TAG, "通知监听服务已连接")
        // 系统重新绑定服务时补读当前仍存在的通知,避免授权重连期间页面长期空白。
        runCatching {
            val active = activeNotifications.orEmpty()
            markRecordsNotActive(active.map(::sourceKey).toSet())
            active.forEach(::recordNotification)
        }.onFailure { error ->
            Logger.w(TAG, "恢复当前通知失败: ${error.message}", error)
        }
    }

    override fun onListenerDisconnected() {
        connected = false
        Logger.w(TAG, "通知监听服务已断开")
        // Android 可能因系统回收/权限切换主动解绑;请求一次系统重绑,但做退避防止日志/IPC 风暴。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val now = System.currentTimeMillis()
            if (now - lastRebindRequestAt >= REBIND_BACKOFF_MS) {
                lastRebindRequestAt = now
                runCatching {
                    requestRebind(ComponentName(this, MuseNotificationListenerService::class.java))
                }.onFailure { error ->
                    Logger.w(TAG, "请求通知监听服务重绑失败: ${error.message}", error)
                }
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching { recordNotification(sbn) }
            .onFailure { error -> Logger.w(TAG, "处理通知失败: ${error.message}", error) }
    }

    private fun recordNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        val extras = notification.extras
        val pkg = sbn.packageName

        // 过滤掉系统 UI 和自身通知,避免噪音
        if (pkg in IGNORED_PACKAGES) return

        val rawTitle = firstNonBlank(
            extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        val rawText = firstNonBlank(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.joinToString("\n"),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
        )

        // 高敏包名只记来源不记正文;其余通知正文过 PII 遮蔽。
        val (safeTitle, safeText) = if (pkg in SENSITIVE_PACKAGES) {
            "[敏感通知]" to "(已隐藏正文,来源: $pkg)"
        } else {
            scrubText(rawTitle) to scrubText(rawText)
        }
        val appLabel = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.onFailure { error -> Logger.d(TAG, "读取通知应用名称失败($pkg): ${error.message}") }
            .getOrDefault(pkg)

        val record = NotificationRecord(
            packageName = pkg,
            appLabel = appLabel,
            title = safeTitle,
            text = safeText,
            timestamp = sbn.postTime,
            sourceKey = sourceKey(sbn),
            channelId = notification.channelId,
            category = notification.category,
            groupKey = sbn.groupKey,
            priority = notification.priority,
            visibility = notification.visibility,
            isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            isClearable = sbn.isClearable,
            isActive = true,
            removedAt = null,
            removedReason = null,
            isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0,
        )

        val snapshot = synchronized(stateLock) {
            val recordKey = keyOf(record)
            val old = _recentNotifications.value.firstOrNull { keyOf(it) == recordKey }
            val preservedRead = old?.takeIf {
                it.title == record.title && it.text == record.text
            }?.isRead ?: false
            val updatedRecord = record.copy(isRead = preservedRead)
            val updated = normalize(
                listOf(updatedRecord) + _recentNotifications.value.filterNot { keyOf(it) == recordKey },
            )
            _recentNotifications.value = updated
            updated
        }
        persistAsync(snapshot)
    }

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }.orEmpty()

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        markNotificationRemoved(sbn, null)
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap,
        reason: Int,
    ) {
        markNotificationRemoved(sbn, reason)
    }

    private fun markNotificationRemoved(sbn: StatusBarNotification, reason: Int?) {
        val removedKey = sourceKey(sbn)
        updateRecords { current ->
            current.map { record ->
                if (keyOf(record) == removedKey) {
                    record.copy(
                        isActive = false,
                        removedAt = System.currentTimeMillis(),
                        removedReason = reason,
                    )
                } else {
                    record
                }
            }
        }
    }
}

/**
 * 单条通知记录(供 LLM 工具读取)。
 */
@Serializable
data class NotificationRecord(
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
    /** 系统 sbn.key,用于更新去重;旧记录为空时由内容生成兼容键。 */
    val sourceKey: String = "",
    /** 是否已在 Muse 中查看。 */
    val isRead: Boolean = false,
    /** 是否为常驻通知。 */
    val isOngoing: Boolean = false,
    /** 应用可读名称,取不到时回退包名。 */
    val appLabel: String = "",
    /** Android 通知渠道 id。 */
    val channelId: String? = null,
    /** Android 通知类别。 */
    val category: String? = null,
    /** 系统分组键。 */
    val groupKey: String? = null,
    /** 通知优先级与可见性。 */
    val priority: Int = Notification.PRIORITY_DEFAULT,
    val visibility: Int = Notification.VISIBILITY_PRIVATE,
    /** 是否为通知组摘要。 */
    val isGroupSummary: Boolean = false,
    /** 系统是否允许用户清除。 */
    val isClearable: Boolean = true,
    /** 通知是否仍存在于系统通知栏。 */
    val isActive: Boolean = true,
    /** 移除时间与系统移除原因(旧数据为空)。 */
    val removedAt: Long? = null,
    val removedReason: Int? = null,
)

private fun NotificationRecord.matches(query: String): Boolean =
    packageName.lowercase().contains(query) ||
        appLabel.lowercase().contains(query) ||
        title.lowercase().contains(query) ||
        text.lowercase().contains(query) ||
        channelId.orEmpty().lowercase().contains(query) ||
        category.orEmpty().lowercase().contains(query)
