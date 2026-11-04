package io.zer0.muse.automation.core

import kotlinx.serialization.Serializable

@Serializable
enum class AutomationAction {
    READ_SCREEN,
    CLICK,
    SCROLL,
    INPUT,
}

@Serializable
data class AutonomousAccessibilityWindow(
    val enabled: Boolean = false,
    val startMinuteOfDay: Int = 14 * 60,
    val endMinuteOfDay: Int = 17 * 60,
    val allowedPackages: Set<String> = emptySet(),
    val allowedActions: Set<AutomationAction> = emptySet(),
    val maxRunsPerDay: Int = 0,
)

/**
 * Per-session task authorization. A confirmation is deliberately scoped to one
 * chat + task signature + package, so "yes" to a Xiaohongshu browse task cannot
 * silently authorize a later messaging or purchase task.
 */
@Serializable
data class AutonomousAccessibilityGrant(
    val sessionId: String,
    val packageName: String,
    val taskFingerprint: String,
    val confirmedAtMillis: Long,
)

data class AutonomousAccessibilityRequest(
    val minuteOfDay: Int,
    val packageName: String,
    val action: AutomationAction,
    val runCountToday: Int,
    val userConfirmed: Boolean,
)

data class AutonomousAccessibilityDecision(
    val allowed: Boolean,
    val reason: String,
)

object AutonomousAccessibilityPolicy {
    // 守卫链：每个提前返回都带一条**不同的**人类可读原因（策略/谓词的共同形状）。
    // 拧成单一返回会把原因塞进可空值再层层解包，读的人反而看不出谁在挡 ——
    // 这是规则的已知误报（阈值 4），逐点豁免并把理由留在这里。
    @Suppress("ReturnCount")
    fun evaluate(
        window: AutonomousAccessibilityWindow,
        request: AutonomousAccessibilityRequest,
    ): AutonomousAccessibilityDecision {
        if (!window.enabled) return denied("自主调用未开启")
        if (!request.userConfirmed) return denied("需要用户明确授权本次任务")
        if (!isWithinWindow(request.minuteOfDay, window.startMinuteOfDay, window.endMinuteOfDay)) {
            return denied("不在允许时段")
        }
        if (request.packageName !in window.allowedPackages) return denied("目标应用不在白名单")
        if (request.action !in window.allowedActions) return denied("动作不在白名单")
        if (request.runCountToday >= window.maxRunsPerDay.coerceAtLeast(0)) {
            return denied("已达到今日自主调用上限")
        }
        return AutonomousAccessibilityDecision(allowed = true, reason = "允许")
    }

    private fun isWithinWindow(minute: Int, start: Int, end: Int): Boolean {
        if (start == end) return false
        val normalizedMinute = minute.coerceIn(0, MINUTES_PER_DAY - 1)
        val normalizedStart = start.coerceIn(0, MINUTES_PER_DAY - 1)
        val normalizedEnd = end.coerceIn(0, MINUTES_PER_DAY - 1)
        return if (normalizedStart < normalizedEnd) {
            normalizedMinute >= normalizedStart && normalizedMinute < normalizedEnd
        } else {
            normalizedMinute >= normalizedStart || normalizedMinute < normalizedEnd
        }
    }

    private fun denied(reason: String) = AutonomousAccessibilityDecision(allowed = false, reason = reason)

    private const val MINUTES_PER_DAY = 24 * 60
}

object AutonomousAccessibilityTaskScope {
    private val automationKeywords =
        Regex(
            "(无障碍|自动化|操作|点击|滑动|滑一下|刷|浏览|打开|看看|搜索|查找|翻页|" +
                "screen|tap|swipe|scroll|browse|automation|accessibility|" +
                "\\b(use|control|navigate|operate|run)\\b)",
            RegexOption.IGNORE_CASE,
        )

    /**
     * A user request counts as task-level confirmation only when it asks for
     * device operation and names the same foreground package.
     */
    fun confirms(
        latestUserMessage: String?,
        packageName: String,
    ): Boolean {
        if (latestUserMessage.isNullOrBlank()) return false
        if (packageName.isBlank()) return false
        val appHint = packageLabel(packageName)
        val namesPackage =
            latestUserMessage.contains(packageName, ignoreCase = true) ||
                (appHint != null && latestUserMessage.contains(appHint, ignoreCase = true))
        return namesPackage && automationKeywords.containsMatchIn(latestUserMessage)
    }

    /** Stable, content-addressed scope; no mutable or monotonic clock involved. */
    fun fingerprint(latestUserMessage: String): String =
        sha256(latestUserMessage.trim().replace(Regex("\\s+"), " "), "task")

    /**
     * The raw text intentionally never reaches logs or audit rows.
     */
    fun sha256(
        text: String,
        prefix: String,
    ): String {
        val digest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
        return "$prefix:" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun packageLabel(packageName: String): String? = when (packageName) {
        "com.xingin.xhs" -> "小红书"
        else -> null
    }
}
