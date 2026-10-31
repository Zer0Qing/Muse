package io.zer0.muse.tools

/**
 * v2.6.7: 工具所需的设备通道。
 *
 * ## 为什么需要这张表
 *
 * 助手自审的核心抱怨是「工具 206 个，但我不知道哪个真能用」：
 * `ToolDef` 里只有 riskLevel（风险等级），没有任何字段声明「这个工具依赖哪条通道」。
 * 于是模型只能一个个试，撞墙了才知道没权限 —— 比"根本不提供"更伤体验。
 *
 * 这张表把「依赖关系」显式化，让 tool_status 能按当前权限过滤出真正可调用的清单。
 *
 * ## 维护纪律
 *
 * 新增需要设备通道的工具时**必须**在这里登记，否则 tool_status 会把它误报为可用。
 * 未登记的工具默认按 [Requirement.NONE] 处理（应用内工具，任何权限都不用）。
 */
object ToolRequirements {

    /** 工具依赖的通道类型。 */
    enum class Channel {
        /** 无障碍服务（读屏、手势、输入）。 */
        ACCESSIBILITY,

        /** Shizuku / adb shell。 */
        SHELL,

        /** Root。 */
        ROOT,

        /** 使用情况访问权限（UsageStats）。 */
        USAGE_STATS,

        /** 通知使用权。 */
        NOTIFICATION_LISTENER,

        /** 联系人读取。 */
        CONTACTS,

        /** 短信。 */
        SMS,

        /** 通话。 */
        PHONE_CALL,

        /** 日历。 */
        CALENDAR,

        /** 定位。 */
        LOCATION,

        /** 相机 / 麦克风等运行时权限。 */
        RUNTIME,
    }

    /**
     * 一个工具的依赖描述。
     *
     * @param anyOf 满足其中**任意一条**通道即可调用（如屏幕操作：无障碍或 Shell 都行）
     * @param allOf 必须**同时**满足（如静默安装需要 Shell，且要读包列表）
     * @param note 面向模型的补充说明（缺权限时怎么开）
     */
    data class Requirement(
        val anyOf: Set<Channel> = emptySet(),
        val allOf: Set<Channel> = emptySet(),
        val note: String = "",
    ) {
        val isUnconditional: Boolean get() = anyOf.isEmpty() && allOf.isEmpty()
    }

    /** 不需要任何设备通道 —— 应用内工具（记忆/卡片/会话/知识库等）。 */
    private val NONE = Requirement()

    /** 屏幕操作：无障碍或 Shell 任一可用即可。 */
    private val SCREEN = Requirement(
        anyOf = setOf(Channel.ACCESSIBILITY, Channel.SHELL),
        note = "需要无障碍服务，或 Shizuku/ADB；也可以在设置→无障碍里开启 Muse 服务",
    )

    /** Shell 级操作：必须是 Shell 或 Root。 */
    private val SHELL_ONLY = Requirement(
        anyOf = setOf(Channel.SHELL, Channel.ROOT),
        note = "需要 Shizuku 或 Root；Shizuku 需先安装并授权 Muse",
    )

    /** 映射表：工具名 → 依赖。未列出的视为 [NONE]。 */
    private val TABLE: Map<String, Requirement> = buildMap {
        // ── 屏幕操作（无障碍 / Shell 任一）──
        listOf(
            "screen_read", "screen_tap", "screen_tap_text", "screen_swipe", "screen_swipe_path",
            "screen_pinch", "screen_wait", "screen_back", "screen_home", "screen_current_app",
            "screen_input", "screen_launch_app", "screen_open_notifications",
            "ui_agent", "input_inject", "virtual_screen", "virtual_screen_input",
            "automation_workflow",
        ).forEach { put(it, SCREEN) }

        // ── Shell 级 ──
        listOf(
            "device_shell", "am_start", "app_force_stop", "app_clear_data", "app_uninstall",
            "list_packages", "list_installed_apps", "network_toggle", "toggle_wifi",
            "toggle_bluetooth", "logcat_tail", "set_brightness", "set_volume",
            "open_system_setting", "app_settings",
        ).forEach { put(it, SHELL_ONLY) }

        // ── 单项运行时权限 ──
        put("screen_time", Requirement(anyOf = setOf(Channel.USAGE_STATS), note = "需要「使用情况访问」权限"))
        put("get_foreground_app", Requirement(anyOf = setOf(Channel.USAGE_STATS), note = "需要「使用情况访问」权限"))
        put(
            "get_recent_notifications",
            Requirement(anyOf = setOf(Channel.NOTIFICATION_LISTENER), note = "需要「通知使用权」"),
        )
        listOf("get_contacts_list", "get_contacts_count", "add_contact").forEach {
            put(it, Requirement(anyOf = setOf(Channel.CONTACTS), note = "需要「联系人」权限"))
        }
        put("send_sms", Requirement(allOf = setOf(Channel.SMS), note = "需要「短信」权限"))
        put("make_phone_call", Requirement(allOf = setOf(Channel.PHONE_CALL), note = "需要「电话」权限"))
        listOf("add_calendar_event", "calendar_today", "list_reminders").forEach {
            put(it, Requirement(anyOf = setOf(Channel.CALENDAR), note = "需要「日历」权限"))
        }
        listOf("get_location").forEach {
            put(it, Requirement(anyOf = setOf(Channel.LOCATION), note = "需要「定位」权限"))
        }

        // ── 明确的 NONE（应用内，写出来是为了让 reader 知道这些已核对过）──
        listOf(
            "show_card", "update_card_data", "render_data", "notify", "todo_write",
            "save_memory", "pin_memory", "delete_memory", "unpin_memory", "search_memory",
            "recall_experience", "record_experience", "search_conversation",
            "current_status", "runtime_selfcheck", "platform_selfcheck", "find_tools",
            // v2.x: token 用量自查（全应用内数据，无系统权限）
            "get_token_usage",
            "calculator", "base64_encode", "base64_decode", "url_encode", "url_decode",
            "hash_text", "generate_uuid", "generate_password", "json_pretty", "random_number",
            "get_current_time", "translate", "speak_text", "share_text", "vibrate",
            "schedule_reminder", "cancel_reminder", "set_alarm", "set_timer",
            "scheduled_task_create", "scheduled_task_update", "scheduled_task_delete",
            "scheduled_task_list", "scheduled_task_get_history", "scheduled_task_execute",
            "session_list", "session_read", "session_rename", "session_delete", "session_archive",
            "session_move_folder", "channel_reply", "channel_pass", "channel_read_context",
            "subagent_task", "subagent_run", "subagent_close", "delegate_agent",
            "generate_image", "generate_video", "generate_qr_code",
            // v2.x: 语音消息——本地 TTS 合成 + 挂在自己的消息上,不碰系统权限
            "send_voice",
        ).forEach { put(it, NONE) }
    }

    /** 查询某工具依赖。未登记 = NONE（应用内工具）。 */
    fun of(toolName: String): Requirement = TABLE[toolName] ?: NONE

    /** 已登记的工具名（供自检/测试核对覆盖度）。 */
    fun registeredNames(): Set<String> = TABLE.keys

    /**
     * 按当前可用通道判断工具是否真的可调用。
     *
     * @param availableChannels 当前已就绪的通道集合
     */
    fun isUsable(toolName: String, availableChannels: Set<Channel>): Boolean {
        val req = of(toolName)
        // 单表达式避免多点 return（ReturnCount）：
        // 必须同时满足 allOf；anyOf 非空时至少要命中一个；两者皆空则可无条件使用。
        val allSatisfied = req.allOf.all { it in availableChannels }
        val anySatisfied = req.anyOf.isEmpty() || req.anyOf.any { it in availableChannels }
        return allSatisfied && anySatisfied
    }
}
