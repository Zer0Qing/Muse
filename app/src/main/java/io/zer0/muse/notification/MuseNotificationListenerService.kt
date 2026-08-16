package io.zer0.muse.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
        // L1-2: 最近通知最大保留条数
        private const val MAX_RECENT_NOTIFICATIONS = 50
        // 最近通知列表(最多保留 MAX_RECENT_NOTIFICATIONS 条)
        private val _recentNotifications = MutableStateFlow<List<NotificationRecord>>(emptyList())
        val recentNotifications = _recentNotifications.asStateFlow()

        /** 审计修复 (1.5): 对通知文本做 PII 遮蔽,防止验证码/卡号/密码等敏感内容
         * 进入 LLM 上下文并随请求外发。命中敏感规则的内容替换为 [REDACTED]。 */
        private fun scrubText(text: String): String {
            if (text.isBlank()) return text
            return runCatching { io.zer0.memory.pii.PiiGuard.scrub(text).cleaned }
                .getOrDefault(text)
        }

        // 是否已连接(用户已授权)
        @Volatile
        private var connected = false
        fun isConnected() = connected

        /** 查询最近通知(供 ToolRegistry 调用)。 */
        fun getRecent(limit: Int = 20, packageName: String? = null): List<NotificationRecord> {
            return _recentNotifications.value
                .let { list ->
                    if (packageName != null) list.filter { it.packageName == packageName }
                    else list
                }
                .take(limit)
        }

        /** 清空通知记录。 */
        fun clearAll() {
            _recentNotifications.value = emptyList()
        }
    }

    override fun onListenerConnected() {
        connected = true
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        val extras = notification.extras

        val title = extras.getString(Notification.EXTRA_TITLE, "")
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val pkg = sbn.packageName

        // 过滤掉系统 UI 和自身通知,避免噪音
        if (pkg in IGNORED_PACKAGES) return

        // 审计修复 (1.5): 高敏包名只记来源不记正文;其余通知正文过 PII 遮蔽
        val (safeTitle, safeText) = if (pkg in SENSITIVE_PACKAGES) {
            "[敏感通知]" to "(已隐藏正文,来源: $pkg)"
        } else {
            scrubText(title) to scrubText(text)
        }

        val record = NotificationRecord(
            packageName = pkg,
            title = safeTitle,
            text = safeText,
            timestamp = sbn.postTime,
        )

        _recentNotifications.update { current ->
            (listOf(record) + current).take(MAX_RECENT_NOTIFICATIONS)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // 不处理移除事件
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
)
