package io.zer0.muse.context

/**
 * 手机上下文的**统一信封**（对比清单 P2-02）。
 *
 * 要解决的是"模型分不清'确实没有'和'拿不到'"：
 * 现在问它"今天有什么通知"，它要么看到空、要么看到报错，两种都只会说"没有"。
 * 而"没授权"和"真没有"对用户的下一步完全不同 —— 前者要去开权限，后者不需要。
 *
 * 所以每一项数据都必须带四件事：**来源、抓取时间、覆盖范围、权限状态**。
 * 这不是日志格式偏好，是让模型能给出正确建议的最小信息。
 */
object PhoneContext {

    /** 支持的数据源。新增来源时这里必须先加，否则工具无法表达它的状态。 */
    enum class Source(val wire: String, val label: String) {
        CALENDAR("calendar", "日历"),
        APP_USAGE("app_usage", "应用使用"),
        CONTACTS("contacts", "联系人"),
        NOTIFICATIONS("notifications", "通知"),
        ;

        companion object {
            fun parse(raw: String?): Source? = entries.firstOrNull { it.wire == raw?.trim()?.lowercase() }
        }
    }

    /** 权限/可用性状态。三者必须区分开，否则用户不知道该做什么。 */
    enum class Availability {
        /** 已授权且有数据通道。 */
        AVAILABLE,

        /** 权限没给：用户可以去开。 */
        DENIED,

        /** 系统不支持 / 本机没有这条通道：开权限也没用。 */
        UNAVAILABLE,
    }

    data class Envelope(
        val source: Source,
        val availability: Availability,
        val capturedAtMillis: Long,
        /** 覆盖的时间窗（天）；0 表示"当前时刻"。 */
        val windowDays: Int = 0,
        val itemCount: Int = 0,
        /** 是否因上限被截断。 */
        val truncated: Boolean = false,
        /** 给用户看的补充说明（例如"需要通知使用权"）。 */
        val note: String? = null,
    )

    /**
     * 渲染一段来源结果。
     *
     * 三种状态的话术刻意不同：
     *  - AVAILABLE 且无数据 → "确实没有"（用户不需要做任何事）
     *  - DENIED → "没有授权"（用户可以去开）
     *  - UNAVAILABLE → "这台设备上拿不到"（开权限也没用）
     */
    fun render(envelope: Envelope, items: List<String>): String {
        val header = buildString {
            append("--- ${envelope.source.label} | ")
            append(
                when (envelope.availability) {
                    Availability.AVAILABLE -> "可用"
                    Availability.DENIED -> "未授权"
                    Availability.UNAVAILABLE -> "不可用"
                },
            )
            append(" | 覆盖=").append(if (envelope.windowDays > 0) "最近 ${envelope.windowDays} 天" else "当前时刻")
            append(" | 抓取于=").append(envelope.capturedAtMillis)
            envelope.note?.takeIf { it.isNotBlank() }?.let { append(" | ").append(it) }
            append(" ---")
        }
        val body = when {
            envelope.availability == Availability.DENIED ->
                "没有这项权限，无法读取。需要用户在系统里授权后重试（不要把这当成\"没有数据\"）。"
            envelope.availability == Availability.UNAVAILABLE ->
                "这台设备上拿不到这项数据（开权限也没用）。可以换一个来源继续。"
            items.isEmpty() ->
                "已授权，这个范围内确实没有记录。"
            else -> items.joinToString("\n")
        }
        val footer = if (envelope.truncated) {
            "\n--- 结果已截断（共 ${envelope.itemCount} 条）：请缩小时间范围或换更具体的问题 ---"
        } else {
            ""
        }
        return header + "\n" + body + footer
    }
}
