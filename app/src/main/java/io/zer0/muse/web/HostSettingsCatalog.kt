package io.zer0.muse.web

import io.zer0.muse.feature.FeatureFlags

/**
 * 设置键名常量。
 *
 * 键名同时是 settings.snapshot 里 [HostSettingsEntry.toggleKey] / [HostSettingToggle.key] 的取值；
 * 可写键集合不在这里，而是由 [HostSettingDefinitions] 按同一份定义派生（值项与白名单同源）。
 */
internal object HostSettingKeys {
    const val PII_GUARD = "piiGuardEnabled"
    const val PROACTIVE_MESSAGE = "proactiveMessageEnabled"
    const val KEEP_ALIVE = "keepAliveEnabled"
    const val MINI_PHONE = "miniPhoneEnabled"
    const val MEMORY = "memoryEnabled"
    const val SCHEDULE_WORK = "scheduleWorkEnabled"
    const val PLUGINS = "pluginsGloballyEnabled"
    const val DYNAMIC_COLOR = "dynamicColor"
    const val HIGH_CONTRAST = "highContrast"
    const val TOKEN_ESTIMATE = "tokenEstimateEnabled"
    const val CHAT_ENTER_TO_SEND = "chatPreferences.enterToSend"
    const val CHAT_STREAM_RESPONSE = "chatPreferences.streamResponse"

    /** 常用开关:设置主页「常用开关」区读取的键,顺序即展示顺序。 */
    val readable: List<String> = listOf(
        PII_GUARD,
        PROACTIVE_MESSAGE,
        KEEP_ALIVE,
        MINI_PHONE,
        MEMORY,
        SCHEDULE_WORK,
        PLUGINS,
        CHAT_ENTER_TO_SEND,
        CHAT_STREAM_RESPONSE,
        DYNAMIC_COLOR,
        HIGH_CONTRAST,
        TOKEN_ESTIMATE,
    )
}

/**
 * 设置清单的分组与入口（协议侧）。
 *
 * 界面与搜索索引的顺序、标题都来自 [io.zer0.muse.ui.settings.SettingsCatalog]；
 * 本对象是同一份结构给 Web 端用的投影。两者的一致性由 `HostSettingsCatalogTest`
 * 逐组逐项比对 —— 任一侧改了分组、顺序或条目，测试即失败，Web 端不必（也不能）
 * 自己维护一份会与 App 漂移的清单。
 */
internal object HostSettingsCatalog {

    /** 分组 key 是分组标题的字符串资源名,按界面从上到下排列。 */
    val groups: List<HostSettingsGroup> = listOf(
        group(
            "settings_group_assistants",
            link("user_profile", "settings_user_profile_title", "user"),
            link("assistants", "settings_screen_assistant", "atom"),
            link("assistant_resources", "settings_screen_assistant_resources", "stars"),
            link("agent", "chat_agent_tab_title", "users"),
            // 2026-11-22: CHAT / APPEARANCE 原本各自成组（各 1 条），并入助手组。
            link("chat", "settings_screen_chat", "chat"),
            link("appearance", "settings_screen_appearance_label", "palette"),
        ),
        group(
            "settings_group_models",
            link("provider", "common_provider", "provider"),
            link("task_routing", "settings_task_routing_title", "taskRouting"),
            link("provider_plugins", "provider_plugins_title", "affiliate"),
        ),
        group(
            "settings_group_tools",
            link("tools", "settings_screen_tools", "wrench"),
            link("permission_wizard", "permission_wizard_title", "shieldCheck"),
            link("web_search", "settings_screen_web_search", "globe"),
            // 与 App 侧 1:1 镜像：单工具审批策略页。Web 端还没有对应子页，
            // 因此列出但不进子页（enabled = false），不留一个点了没反应的假入口。
            link(
                "tool_approval",
                "settings_screen_entry_tool_approval",
                "shieldCheck",
                enabled = false,
            ),
        ),
        group(
            "settings_group_voice",
            link("tts", "settings_screen_media", "microphone"),
            link("asr", "settings_screen_asr", "microphone"),
            link("voice_cloning", "voice_cloning_title", "play"),
        ),
        group(
            "settings_group_image",
            link("image_video_gen", "video_gen_title", "video"),
            link("vision", "settings_vision_title", "image"),
        ),
        group(
            "settings_group_memory",
            link("memory", "settings_screen_memory_notification", "atom"),
            link("rag", "settings_screen_rag", "book"),
            // v2.7.x(E2): 术语页目前只有 App 侧子页(要读写本机 Room)。
            // 1:1 镜像要求这里同步有条目，但 Web 端补出对应页之前保持 enabled = false，
            // 不造一个点了没反应的假入口(S4 的 diagnostics 走的正是同一套做法)。
            link("session_terms", "settings_screen_session_terms", "book", enabled = false),
        ),
        group(
            "settings_group_connection",
            link("channel", "channel_page_title", "wifi"),
            // 内置浏览器设置只有 App 侧有页面（WebView 的引擎与 Cookies 行为没法从网页驱动），
            // 所以这里 enabled = false：Web 端列出但不进子页，不造一个点了没反应的假入口。
            link("browser", "settings_screen_browser", "globe", enabled = false),
            link("web_access", "settings_screen_web_access", "router"),
            link("mcp", "settings_screen_mcp", "plug"),
            // 代理条目的说明由运行时拼(未启用 / type host:port),没有固定资源。
            link("proxy", "proxy_title", "proxy"),
            // 2026-11-22: KEEP_ALIVE / MINI_PHONE 原本各自成组（各 1 条），并入连接组。
            link("keep_alive", "settings_keep_alive_title", "battery"),
            link(
                "miniphone",
                "settings_miniphone_title",
                "deviceMobile",
                enabled = FeatureFlags.MINI_PHONE),
        ),
        group(
            "settings_group_automation",
            link("proactive", "settings_agent_proactive_title", "bell"),
            // 用户约定只有 App 侧有页面（编辑与注入都在 App 内），Web 端列出但不进子页，
            // 不留一个点了没反应的死行；理由同步登记在 HostSettingsEntryAvailabilityTest。
            link(
                "agent_directives",
                "settings_screen_agent_directives",
                "bell",
                enabled = false),
            link(
                "notification_listener",
                "settings_screen_notification_listener",
                "bell"),
            link("ui_automation", "automation_settings_title", "computer"),
        ),
        group(
            "settings_group_data_storage",
            link("data_management", "data_management_entry", "database"),
            link("workspace", "workspace_title", "folder"),
            link("backup", "settings_screen_data_backup", "cloud"),
            link("data_import", "settings_screen_data_import", "cloudUpload"),
            link("archived_chats", "settings_screen_entry_archived", "archive"),
            // 2026-11-22: OFFLINE 原本独立成组（1 条），并入数据组。
            link("runtime", "offline_resources_entry_title", "download"),
        ),
        group(
            "settings_group_privacy_security",
            toggle(
                "pii_guard",
                "settings_screen_pii_guard",
                "shield",
                HostSettingKeys.PII_GUARD),
            link("share", "common_share", "lock"),
        ),
        group(
            "settings_group_about",
            link("tutorial", "settings_screen_tutorial", "school"),
            link("about", "settings_screen_about", "info"),
            action("check_update", "settings_screen_check_update", "refresh"),
        ),
        group(
            "settings_group_developer",
            // 「运行诊断」在 App 侧已移入开发者组，协议侧必须同步，
            // 否则 Web 设置页的分类归属与 App 不一致。
            // S5 已在 Web 端补出「运行」三 Tab 页(runtime.logs / runtime.diagnostics / runtime.stats)。
            link("diagnostics", "settings_screen_diagnostics", "bug"),
            link("stats", "settings_screen_stats", "chartBar"),
            link("experiments", "settings_screen_experiments", "flask"),
            link("debug_log", "settings_screen_debug_log", "bug"),
            link("crash_report", "settings_crash_page_title", "shieldCheck"),
            link("audit_log", "settings_audit_log", "history"),
        ),
    ).let(::visibleGroups)

    /** 全部入口的扁平视图,顺序与界面渲染一致。 */
    val entries: List<HostSettingsEntry> = groups.flatMap { it.entries }

    private fun group(key: String, vararg entries: HostSettingsEntry): HostSettingsGroup =
        HostSettingsGroup(key = key, entries = entries.map { it.copy(groupKey = key) })

    /**
     * 整组可见性过滤。
     *
     * 小手机在 2.6.27 整体收起：Android 侧入口已由编译期闸门关掉，
     * Web 设置页若还把「小手机」分类列出来（哪怕条目是 disabled），
     * 用户看到的就是一个点不动的死分类 —— 与「界面上不出现任何痕迹」不一致。
     * 这里按同一口径把整组摘掉。
     */
    private fun visibleGroups(all: List<HostSettingsGroup>): List<HostSettingsGroup> =
        all.filter { group -> group.key != "settings_group_miniphone" || FeatureFlags.MINI_PHONE }

    private fun link(id: String, titleKey: String, iconKey: String, enabled: Boolean = true): HostSettingsEntry =
        HostSettingsEntry(
            id = id,
            titleKey = titleKey,
            groupKey = "",
            iconKey = iconKey,
            kind = KIND_LINK,
            enabled = enabled,
        )

    private fun action(id: String, titleKey: String, iconKey: String): HostSettingsEntry = HostSettingsEntry(
        id = id,
        titleKey = titleKey,
        groupKey = "",
        iconKey = iconKey,
        kind = KIND_ACTION,
    )

    private fun toggle(id: String, titleKey: String, iconKey: String, settingKey: String): HostSettingsEntry =
        HostSettingsEntry(
            id = id,
            titleKey = titleKey,
            groupKey = "",
            iconKey = iconKey,
            kind = KIND_SWITCH,
            toggleKey = settingKey,
        )

    const val KIND_LINK = "link"
    const val KIND_SWITCH = "switch"
    const val KIND_ACTION = "action"
}