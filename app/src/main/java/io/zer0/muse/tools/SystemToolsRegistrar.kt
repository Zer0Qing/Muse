package io.zer0.muse.tools

/**
 * P1-3b 拆域：系统/设备工具注册器。
 *
 * 注册 screen_time / open_system_setting / toggle_wifi / toggle_bluetooth /
 * send_email / get_battery_info / get_recent_notifications / open_url /
 * list_installed_apps / get_network_info / get_storage_info / get_memory_info /
 * get_display_info / get_cpu_info / get_sensors_list / get_brightness /
 * set_brightness / get_volume / set_volume / toggle_flashlight / vibrate /
 * get_foreground_app / get_wifi_info / get_bluetooth_devices。
 * 实现位于 [SystemToolsImpl.kt]。
 */
class SystemToolsRegistrar(
    private val context: android.content.Context,
    private val toolRegistry: ToolRegistry,
) {
    private val impl = SystemToolsImpl(context)

    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "screen_time",
                description = "获取今日各应用屏幕使用时间统计(前 10 名)。需要 PACKAGE_USAGE_STATS 权限。",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { impl.execScreenTime(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "open_system_setting",
                description = "打开系统设置页,支持分类跳转。",
                parameters = mapOf("category" to "可选,设置分类: wifi/bluetooth/display/sound/app_settings 等,默认 settings"),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execOpenSystemSetting(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "toggle_wifi",
                description = "切换 WiFi 开关。",
                parameters = mapOf("enabled" to "可选,true/false,默认切换"),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execToggleWifi(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "toggle_bluetooth",
                description = "切换蓝牙开关。",
                parameters = mapOf("enabled" to "可选,true/false,默认切换"),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execToggleBluetooth(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "send_email",
                description = "通过系统邮件应用发送邮件。",
                parameters = mapOf(
                    "to" to "必填,收件人邮箱,多个用逗号分隔",
                    "subject" to "可选,主题",
                    "body" to "可选,正文",
                ),
                required = setOf("to"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execSendEmail(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_battery_info",
                description = "获取电池电量与充电状态。",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetBatteryInfo(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_recent_notifications",
                description = "获取最近收到的应用通知。需要通知使用权。",
                parameters = mapOf(
                    "limit" to "可选,返回数量,默认 20",
                    "package_name" to "可选,按包名过滤",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execGetRecentNotifications(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "open_url",
                description = "在系统浏览器中打开 URL。",
                parameters = mapOf("url" to "必填,http/https 链接"),
                required = setOf("url"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execOpenUrl(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "list_installed_apps",
                description = "列出已安装应用,支持过滤与数量限制。",
                parameters = mapOf(
                    "filter" to "可选,名称过滤关键字",
                    "limit" to "可选,返回数量,默认 20",
                    "include_system" to "可选,是否包含系统应用",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.execListInstalledApps(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_network_info",
                description = "获取当前网络连接信息。",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetNetworkInfo(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_storage_info",
                description = "获取存储空间使用情况。",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetStorageInfo(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_memory_info",
                description = "获取内存使用情况。",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetMemoryInfo(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_display_info",
                description = "获取屏幕分辨率与刷新率等信息。",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetDisplayInfo(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_cpu_info",
                description = "获取 CPU 型号与核心数等信息。",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetCpuInfo(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_sensors_list",
                description = "列出设备可用传感器。",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.execGetSensorsList(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_brightness",
                description = "获取当前屏幕亮度。",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetBrightness(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "set_brightness",
                description = "设置屏幕亮度。需要系统设置写入权限。",
                parameters = mapOf(
                    "value" to "必填,亮度 0-255",
                    "auto" to "可选,是否自动亮度",
                ),
                required = setOf("value"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execSetBrightness(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_volume",
                description = "获取指定音频流音量。",
                parameters = mapOf("stream" to "可选,music/ring/alarm/notification/call/system"),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.execGetVolume(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "set_volume",
                description = "设置指定音频流音量。",
                parameters = mapOf(
                    "value" to "必填,音量 0-100",
                    "stream" to "可选,music/ring/alarm/notification/call/system",
                ),
                required = setOf("value"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execSetVolume(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "toggle_flashlight",
                description = "开关手电筒。需要相机权限。",
                parameters = mapOf("enabled" to "可选,true/false,默认切换"),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execToggleFlashlight(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "vibrate",
                description = "触发设备振动。",
                parameters = mapOf(
                    "duration_ms" to "可选,振动时长,默认 500",
                    "repeat" to "可选,是否重复",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execVibrate(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_foreground_app",
                description = "获取当前前台应用包名。需要使用统计权限。",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { impl.execGetForegroundApp(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_wifi_info",
                description = "获取当前 WiFi 连接信息。",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetWifiInfo(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_bluetooth_devices",
                description = "获取已配对蓝牙设备列表。",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetBluetoothDevices(emptyMap()) }
    }
}
