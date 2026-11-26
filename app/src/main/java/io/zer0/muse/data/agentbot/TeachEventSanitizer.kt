package io.zer0.muse.data.agentbot

import android.view.accessibility.AccessibilityEvent

/**
 * 示范录制的无障碍事件脱敏器。
 *
 * 录制只需要"用户做了什么"的路径，不需要输入内容本身：
 *  - 密码框事件一律拦截，任何字符都不进入录制轨迹；
 *  - 文本输入只保留字段提示与字符数，原文不落地；
 *  - 支付/银行类包名整体拦截，避免把账户操作写进可复用流程；
 *  - 单次录制限制 60 步 / 10 分钟，防止长时间收集导致内存与上下文持续膨胀。
 */
object TeachEventSanitizer {

    /** 单次录制最多保留的步数。 */
    const val MAX_STEPS = 60

    /** 单次录制最长时长（毫秒）。 */
    const val MAX_DURATION_MS = 10 * 60 * 1000L

    /** 单步动作文本上限，避免超长控件文本撑大后续提示词。 */
    const val MAX_ACTION_LENGTH = 160

    /** 控件标签上限，保留可辨识的名称即可。 */
    private const val MAX_LABEL_LENGTH = 40

    /**
     * 与 Android 框架解耦的原始事件。
     *
     * 服务层负责把 [AccessibilityEvent] 转成该结构，脱敏规则因此可以纯 JVM 单测。
     */
    data class RawEvent(
        val eventType: Int,
        val packageName: String = "",
        val className: String = "",
        val viewId: String = "",
        val text: String? = null,
        val contentDescription: String? = null,
        val isPassword: Boolean = false,
        val timestamp: Long,
    )

    sealed interface Decision {
        data class Record(val action: String, val targetApp: String) : Decision

        data class Blocked(val reason: BlockReason) : Decision

        data object Ignored : Decision
    }

    enum class BlockReason {
        PASSWORD_FIELD,
        SENSITIVE_PACKAGE,
        STEP_LIMIT,
        TIME_LIMIT,
    }

    /**
     * 判断一条原始事件是否可以进入录制轨迹。
     *
     * @param recordedSteps 当前已记录的步数
     * @param startedAt 本次录制开始时间（wall clock，毫秒）；0 表示尚未开始
     * @param now 当前时间（wall clock，毫秒），默认取事件时间便于测试注入
     */
    fun decide(
        event: RawEvent,
        recordedSteps: Int,
        startedAt: Long,
        now: Long = event.timestamp,
    ): Decision {
        if (event.isPassword || isPasswordInput(event)) {
            return Decision.Blocked(BlockReason.PASSWORD_FIELD)
        }
        if (isSensitivePackage(event.packageName)) {
            return Decision.Blocked(BlockReason.SENSITIVE_PACKAGE)
        }
        if (startedAt > 0L && now - startedAt >= MAX_DURATION_MS) {
            return Decision.Blocked(BlockReason.TIME_LIMIT)
        }
        if (recordedSteps >= MAX_STEPS) {
            return Decision.Blocked(BlockReason.STEP_LIMIT)
        }
        return when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> clickDecision(event)
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> textDecision(event)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> windowDecision(event)
            else -> Decision.Ignored
        }
    }

    /** 支付、银行、钱包类包名一律视为高敏目标。 */
    fun isSensitivePackage(packageName: String): Boolean {
        val normalized = packageName.trim().lowercase()
        if (normalized.isBlank()) return false
        val matchedPrefix = SENSITIVE_PACKAGE_PREFIXES.any { prefix ->
            normalized == prefix || normalized.startsWith("$prefix.")
        }
        if (matchedPrefix) return true
        return normalized.split('.').any { segment -> segment in SENSITIVE_PACKAGE_SEGMENTS }
    }

    private fun clickDecision(event: RawEvent): Decision.Record {
        val label = firstNonBlank(
            event.contentDescription,
            event.text,
            event.viewId.substringAfterLast('/'),
            event.className.substringAfterLast('.'),
        ).take(MAX_LABEL_LENGTH)
        val action = if (label.isBlank()) "点击界面元素" else "点击「$label」"
        return Decision.Record(action.take(MAX_ACTION_LENGTH), event.packageName.trim())
    }

    private fun textDecision(event: RawEvent): Decision.Record {
        val field = firstNonBlank(
            event.contentDescription,
            event.viewId.substringAfterLast('/'),
            event.className.substringAfterLast('.'),
        ).take(MAX_LABEL_LENGTH).ifBlank { "输入框" }
        val length = event.text?.length ?: 0
        val detail = if (length > 0) "已脱敏，$length 字符" else "内容已脱敏"
        val action = "在「$field」输入文本（$detail）"
        return Decision.Record(action.take(MAX_ACTION_LENGTH), event.packageName.trim())
    }

    private fun windowDecision(event: RawEvent): Decision.Record {
        val page = firstNonBlank(
            event.className.substringAfterLast('.'),
            event.packageName,
        ).take(MAX_LABEL_LENGTH)
        val action = if (page.isBlank()) "切换页面" else "切换到「$page」"
        return Decision.Record(action.take(MAX_ACTION_LENGTH), event.packageName.trim())
    }

    /** 密码输入框的补充识别：部分系统只给 viewId/描述，不设 isPassword 标志。 */
    private fun isPasswordInput(event: RawEvent): Boolean {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return false
        val haystack = "${event.viewId} ${event.contentDescription.orEmpty()}".lowercase()
        return PASSWORD_FIELD_HINTS.any { hint -> haystack.contains(hint) }
    }

    private fun firstNonBlank(vararg values: String?): String {
        values.forEach { value ->
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isNotEmpty()) return trimmed
        }
        return ""
    }

    private val PASSWORD_FIELD_HINTS = listOf("password", "passwd", "pwd", "passcode", "密码")

    /** 已知的支付、银行、钱包应用包名前缀。 */
    private val SENSITIVE_PACKAGE_PREFIXES = listOf(
        "com.eg.android.alipaygphone",
        "com.alipay.android.app",
        "com.unionpay",
        "com.icbc",
        "com.ccb",
        "com.abchina",
        "com.chinamworld.bocmbci",
        "com.bankofchina",
        "com.cmbchina",
        "com.spdb",
        "com.ecitic",
        "com.pingan",
        "com.bankcomm",
        "com.yitong.mbank.psbc",
        "com.cib.xyk",
        "com.cmbc.cc.mbank",
        "com.cebbank.mobile.cemb",
        "com.cgbchina.xpt",
        "com.hxb.mobile.client",
        "com.paypal",
        "com.paytm",
        "com.venmo",
        "com.squareup.cash",
        "com.google.android.apps.walletnfcrel",
        "com.samsung.android.spay",
        "com.android.chase",
        "com.chase.sig.android",
        "com.wf.wellsfargomobile",
        "com.infonow.bofa",
        "com.citi.citimobile",
        "com.usbank.mobilebanking",
        "com.discoverfinancial.mobile",
        "com.schwab.mobile",
        "com.fidelity.android",
        "com.coinbase.android",
        "com.binance.dev",
    )

    /** 包名分段中出现的通用高敏词。 */
    private val SENSITIVE_PACKAGE_SEGMENTS = setOf(
        "bank",
        "banking",
        "pay",
        "payment",
        "payments",
        "wallet",
        "finance",
        "fintech",
        "creditcard",
    )
}
