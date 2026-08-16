package io.zer0.muse.tools

import android.annotation.SuppressLint
import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.notification.MuseNotificationListenerService
import io.zer0.muse.util.MusePatterns
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P1-3b 拆域：系统/设备工具实现（从 ToolRegistry.kt 迁移）。
 * 由 SystemToolsRegistrar 注册到 ToolRegistry。
 */
class SystemToolsImpl(private val context: Context) {
    private val FMT_TIME_MIN = ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    // ── 系统控制与邮件工具实现(5 个)──────────────────────────────────────

    /**
     * 打开系统设置页:支持通过 category 跳转到具体设置项(wifi/bluetooth/display 等)。
     * app_settings 分类会附带本应用包名,直达应用详情页。无需运行时权限。
     */
    suspend fun execOpenSystemSetting(args: Map<String, String>): String {
        val category = args["category"] ?: "settings"
        val intent = android.content.Intent().apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            action = android.provider.Settings.ACTION_SETTINGS
            when (category) {
                "wifi" -> action = android.provider.Settings.ACTION_WIFI_SETTINGS
                "bluetooth" -> action = android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
                "display" -> action = android.provider.Settings.ACTION_DISPLAY_SETTINGS
                "sound" -> action = android.provider.Settings.ACTION_SOUND_SETTINGS
                "location" -> action = android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
                "app_settings" -> action = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                "battery" -> action = android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS
                "storage" -> action = android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS
                "security" -> action = android.provider.Settings.ACTION_SECURITY_SETTINGS
                "date_time" -> action = android.provider.Settings.ACTION_DATE_SETTINGS
                // 默认打开设置主页
            }
            if (category == "app_settings") {
                data = android.net.Uri.fromParts("package", context.packageName, null)
            }
        }
        return try {
            context.startActivity(intent)
            context.getString(R.string.tool_setting_opened, category)
        } catch (e: Exception) {
            context.getString(R.string.tool_setting_open_failed, e.message ?: "")
        }
    }

    /**
     * A-02: toggle 类工具统一参数解析 — 兼容 action(on/off/status) 与 enabled(true/false)。
     *
     * 两者都未提供时返回 "status"(只读查询)。不做"翻转"——Android 10+ 无法程序化
     * 翻转 WiFi/蓝牙开关,如实返回状态引导,删除工具描述中的"翻转"承诺。
     */
    private fun resolveToggleAction(args: Map<String, String>): String {
        val action = args["action"]?.lowercase()
        if (action == "on" || action == "off" || action == "status") return action
        return when (args["enabled"]?.toBooleanStrictOrNull()) {
            true -> "on"
            false -> "off"
            null -> "status"
        }
    }

    /**
     * 开关 WiFi:Android 10+ 无法直接 toggle(WifiManager.setWifiEnabled 已废弃)。
     * action=status 读取当前状态;action=on/off 跳 WiFi 设置页让用户手动操作。
     * 需 ACCESS_WIFI_STATE(读状态,普通权限,无需运行时申请)。
     */
    suspend fun execToggleWifi(args: Map<String, String>): String {
        val action = resolveToggleAction(args)
        val wifiManager = context.getSystemService(android.content.Context.WIFI_SERVICE)
            as android.net.wifi.WifiManager
        return when (action) {
            "status" -> {
                val enabled = wifiManager.isWifiEnabled
                if (enabled) context.getString(R.string.tool_wifi_status_on) else context.getString(R.string.tool_wifi_status_off)
            }
            "on", "off" -> {
                // Android 10+ 无法直接开关,跳设置页
                val intent = android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                context.getString(R.string.tool_wifi_toggle_redirect)
            }
            else -> context.getString(R.string.tool_error_invalid_action)
        }
    }

    /**
     * 开关蓝牙:action=status 读状态;action=on 跳设置页(Android 10+ 无法直接开启);
     * action=off 直接调用 BluetoothAdapter.disable()(需 BLUETOOTH_ADMIN 权限)。
     *
     * M-TR4: Android 10+ 的 "off" 分支也跳转蓝牙设置页(与 wifi 行为对称),
     * 因为 BluetoothAdapter.disable() 在 Android 10+ 静默失败(不抛异常但不生效)。
     */
    @SuppressLint("MissingPermission")
    suspend fun execToggleBluetooth(args: Map<String, String>): String {
        val action = resolveToggleAction(args)
        val bluetoothManager = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE)
            as android.bluetooth.BluetoothManager
        val adapter = bluetoothManager.adapter
        return when (action) {
            "status" -> {
                val enabled = adapter?.isEnabled == true
                if (enabled) context.getString(R.string.tool_bluetooth_status_on) else context.getString(R.string.tool_bluetooth_status_off)
            }
            "on" -> {
                // Android 10+ 无法直接开启,跳设置页
                val intent = android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                context.getString(R.string.tool_bluetooth_on_redirect)
            }
            "off" -> {
                // M-TR4: Android 10+ 的 disable() 静默失败,跳设置页让用户手动关闭
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    context.getString(R.string.tool_bluetooth_off_redirect)
                } else {
                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
                        context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        adapter?.disable()
                    }
                    context.getString(R.string.tool_bluetooth_turning_off)
                }
            }
            else -> context.getString(R.string.tool_error_invalid_action)
        }
    }

    /**
     * 发送邮件:通过 ACTION_SENDTO + mailto: 打开邮件应用,预填收件人/主题/正文。
     * 多个收件人用逗号分隔。无邮件应用时返回错误提示。
     */
    suspend fun execSendEmail(args: Map<String, String>): String {
        val to = args["to"] ?: return context.getString(R.string.tool_error_missing_to)
        // L-TR9: 校验收件人邮箱格式(简单正则,多收件人逐个校验)
        val recipients = to.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (recipients.isEmpty()) return context.getString(R.string.tool_email_recipients_empty)
        val invalid = recipients.filter { !MusePatterns.EMAIL_REGEX.matches(it) }
        if (invalid.isNotEmpty()) {
            return context.getString(R.string.tool_email_invalid, invalid.joinToString(", "))
        }
        val subject = args["subject"] ?: ""
        val body = args["body"] ?: ""
        val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
            data = android.net.Uri.parse("mailto:")
            putExtra(android.content.Intent.EXTRA_EMAIL, recipients.toTypedArray())
            if (subject.isNotBlank()) putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
            if (body.isNotBlank()) putExtra(android.content.Intent.EXTRA_TEXT, body)
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            context.getString(R.string.tool_email_opened, to)
        } catch (e: Exception) {
            context.getString(R.string.tool_email_not_found, e.message ?: "")
        }
    }

    /**
     * 获取电池信息:电量百分比(BATTERY_PROPERTY_CAPACITY)+ 充电状态(isCharging)。
     * 无需运行时权限(BatteryManager 系统服务可直接读取)。
     */
    suspend fun execGetBatteryInfo(_args: Map<String, String>): String {
        val batteryManager = context.getSystemService(android.content.Context.BATTERY_SERVICE)
            as android.os.BatteryManager
        val level = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = batteryManager.isCharging
        return context.getString(R.string.tool_battery_info, level, if (charging) context.getString(R.string.tool_battery_charging) else context.getString(R.string.tool_battery_not_charging))
    }

    /** 查询天气:通过 wttr.in 免费 API 获取天气信息。 */
    suspend fun execGetWeather(args: Map<String, String>): String {
        val location = args["location"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_missing_param_location)
        return resultOf {
            val encoded = java.net.URLEncoder.encode(location, "UTF-8")
            val url = java.net.URL("https://wttr.in/$encoded?format=j1")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code != 200) return@resultOf context.getString(R.string.tool_weather_api_error, code)
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val root = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .parseToJsonElement(body).jsonObject
            val current = root["current_condition"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: return@resultOf context.getString(R.string.tool_weather_no_data, location)
            val temp = current["temp_C"]?.jsonPrimitive?.content ?: "?"
            val desc = current["weatherDesc"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("value")?.jsonPrimitive?.content ?: "?"
            val humidity = current["humidity"]?.jsonPrimitive?.content ?: "?"
            val windSpeed = current["windspeedKmph"]?.jsonPrimitive?.content ?: "?"
            val feelsLike = current["FeelsLikeC"]?.jsonPrimitive?.content ?: "?"
            val area = root["nearest_area"]?.jsonArray?.firstOrNull()?.jsonObject
            val areaName = area?.get("areaName")?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("value")?.jsonPrimitive?.content ?: location
            context.getString(R.string.tool_weather_result, areaName, desc, temp, feelsLike, humidity, windSpeed)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "查天气失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_weather_failed, location)
    }

    /**
     * P2: 获取最近收到的应用通知。
     * 依赖 MuseNotificationListenerService 静态采集;未授权时返回引导提示。
     */
    suspend fun execGetRecentNotifications(args: Map<String, String>): String {
        val limit = args["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: 20
        val pkg = args["package_name"]?.takeIf { it.isNotBlank() }
        val records = MuseNotificationListenerService.getRecent(limit, pkg)
        return if (records.isEmpty()) {
            if (MuseNotificationListenerService.isConnected()) {
                context.getString(R.string.tool_no_notifications)
            } else {
                context.getString(R.string.tool_notification_not_connected)
            }
        } else {
            val fmt = FMT_TIME_MIN.get() ?: SimpleDateFormat("HH:mm", Locale.getDefault())
            records.joinToString("\n") { r ->
                val time = fmt.format(Date(r.timestamp))
                "[${r.packageName}] $time ${r.title}: ${r.text}"
            }
        }
    }

    /** 打开 URL:校验 scheme 后用系统浏览器打开。 */
    suspend fun execOpenUrl(args: Map<String, String>): String {
        val url = args["url"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_missing_param_url)
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return context.getString(R.string.tool_open_url_invalid_scheme)
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return resultOf {
            context.startActivity(intent)
            context.getString(R.string.tool_url_opened, url)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "打开 URL 失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_open_url_failed, url)
    }

    /** 列出已安装应用,支持过滤、限制数量、是否包含系统应用。 */
    suspend fun execListInstalledApps(args: Map<String, String>): String {
        val filter = args["filter"]?.takeIf { it.isNotBlank() }?.lowercase()
        val limit = args["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: 20
        val includeSystem = args["include_system"]?.toBoolean() ?: false
        val pm = context.packageManager
        return resultOf {
            val apps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
            val sb = StringBuilder(context.getString(R.string.tool_installed_apps_header, apps.size))
            var count = 0
            for (app in apps) {
                if (!includeSystem && (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) continue
                val label = pm.getApplicationLabel(app).toString()
                val pkg = app.packageName
                if (filter != null && !label.lowercase().contains(filter) && !pkg.lowercase().contains(filter)) continue
                sb.appendLine(context.getString(R.string.tool_installed_apps_item, label, pkg))
                count++
                if (count >= limit) break
            }
            if (count == 0) context.getString(R.string.tool_installed_apps_empty) else sb.toString().trimEnd()
        }.onError { msg, _ -> Logger.w("ToolRegistry", "列出已安装应用失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_installed_apps_failed)
    }

    /** 获取当前网络连接信息。 */
    suspend fun execGetNetworkInfo(_args: Map<String, String>): String {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        val activeNetwork = cm.activeNetwork
        val caps = activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val connected = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val type = when {
            caps == null -> "无网络"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝数据"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            else -> "其他"
        }
        val metered = cm.isActiveNetworkMetered
        return context.getString(
            R.string.tool_network_info,
            if (connected) context.getString(R.string.tool_yes) else context.getString(R.string.tool_no),
            type,
            if (metered) context.getString(R.string.tool_yes) else context.getString(R.string.tool_no),
        )
    }

    // ── v1.136: 批量新增系统/设备/编码工具 ───────────────────────────────────

    /** 获取内部存储空间信息。 */
    suspend fun execGetStorageInfo(_args: Map<String, String>): String {
        return resultOf {
            val stat = android.os.StatFs(context.filesDir.path)
            val total = stat.totalBytes
            val free = stat.freeBytes
            context.getString(R.string.tool_storage_info, formatBytes(total), formatBytes(total - free), formatBytes(free))
        }.onError { msg, _ -> Logger.w("ToolRegistry", "获取存储信息失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_installed_apps_failed)
    }

    /** 获取内存(RAM)信息。 */
    suspend fun execGetMemoryInfo(_args: Map<String, String>): String {
        return resultOf {
            val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            context.getString(
                R.string.tool_memory_info,
                formatBytes(info.totalMem),
                formatBytes(info.availMem),
                formatBytes(info.threshold),
                if (info.lowMemory) context.getString(R.string.tool_yes) else context.getString(R.string.tool_no),
            )
        }.onError { msg, _ -> Logger.w("ToolRegistry", "获取内存信息失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_installed_apps_failed)
    }

    /** 获取屏幕分辨率、密度、刷新率。 */
    @Suppress("DEPRECATION")
    suspend fun execGetDisplayInfo(_args: Map<String, String>): String {
        return resultOf {
            val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
            val metrics = android.util.DisplayMetrics()
            wm.defaultDisplay.getMetrics(metrics)
            val refreshRate = wm.defaultDisplay.refreshRate
            context.getString(
                R.string.tool_display_info,
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                metrics.density,
                refreshRate,
            )
        }.onError { msg, _ -> Logger.w("ToolRegistry", "获取屏幕信息失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_installed_apps_failed)
    }

    /** 获取 CPU 型号与核心数。 */
    suspend fun execGetCpuInfo(_args: Map<String, String>): String {
        return resultOf {
            val processor = readProcCpuField("Hardware") ?: readProcCpuField("Processor") ?: "未知"
            val cores = Runtime.getRuntime().availableProcessors()
            context.getString(R.string.tool_cpu_info, processor, cores)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "获取 CPU 信息失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_installed_apps_failed)
    }

    /** 获取设备传感器列表。 */
    suspend fun execGetSensorsList(args: Map<String, String>): String {
        val limit = args["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: 30
        return resultOf {
            val sm = context.getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
            val sensors = sm.getSensorList(android.hardware.Sensor.TYPE_ALL)
            if (sensors.isNullOrEmpty()) return@resultOf context.getString(R.string.tool_sensors_empty)
            val sb = StringBuilder(context.getString(R.string.tool_sensors_header, sensors.size))
            sensors.take(limit).forEach {
                sb.appendLine(context.getString(R.string.tool_sensors_item, it.name, it.vendor, it.version))
            }
            sb.toString().trimEnd()
        }.onError { msg, _ -> Logger.w("ToolRegistry", "获取传感器列表失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_installed_apps_failed)
    }

    /** 获取当前屏幕亮度与模式。 */
    suspend fun execGetBrightness(_args: Map<String, String>): String {
        return resultOf {
            val cr = context.contentResolver
            val brightness = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, -1)
            val mode = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
            val modeLabel = if (mode == android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                context.getString(R.string.tool_brightness_auto)
            } else {
                context.getString(R.string.tool_brightness_manual)
            }
            context.getString(R.string.tool_brightness_info, brightness, modeLabel)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "获取亮度失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_brightness_failed, "")
    }

    /**
     * A-03: 设置系统屏幕亮度 — 支持 auto=true 启用自动亮度(忽略 value)。
     * auto 缺省时按 value(0-255)写手动亮度;均未提供时返回参数错误。
     */
    suspend fun execSetBrightness(args: Map<String, String>): String {
        if (args["auto"].equals("true", ignoreCase = true)) {
            return resultOf {
                android.provider.Settings.System.putInt(
                    context.contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
                )
                context.getString(R.string.tool_brightness_auto_set)
            }.onError { msg, _ -> Logger.w("ToolRegistry", "启用自动亮度失败: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_brightness_failed, "auto")
        }
        val value = args["value"]?.toIntOrNull()?.coerceIn(0, 255)
            ?: return context.getString(R.string.tool_brightness_missing)
        return resultOf {
            android.provider.Settings.System.putInt(
                context.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                value,
            )
            context.getString(R.string.tool_brightness_set, value)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "设置亮度失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_brightness_failed, value.toString())
    }

    /** 获取指定音频流音量。 */
    suspend fun execGetVolume(args: Map<String, String>): String {
        val (streamType, streamLabel) = resolveAudioStream(args["stream"])
        return resultOf {
            val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            context.getString(R.string.tool_volume_info, streamLabel, am.getStreamVolume(streamType), am.getStreamMaxVolume(streamType))
        }.onError { msg, _ -> Logger.w("ToolRegistry", "获取音量失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_installed_apps_failed)
    }

    /** 设置指定音频流音量。 */
    suspend fun execSetVolume(args: Map<String, String>): String {
        val (streamType, streamLabel) = resolveAudioStream(args["stream"])
        val rawValue = args["value"]?.toIntOrNull()
            ?: return context.getString(R.string.tool_volume_missing)
        val isPercent = args["percent"].equals("true", ignoreCase = true)
        return resultOf {
            val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            val max = am.getStreamMaxVolume(streamType)
            val index = if (isPercent) (rawValue * max / 100).coerceIn(0, max) else rawValue.coerceIn(0, max)
            am.setStreamVolume(streamType, index, android.media.AudioManager.FLAG_SHOW_UI)
            context.getString(R.string.tool_volume_set, streamLabel, index, max)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "设置音量失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_installed_apps_failed)
    }

    /** 开关手电筒。A-02: 支持 enabled 参数;status 返回本会话最后已知状态(如实,不再恒"是")。 */
    suspend fun execToggleFlashlight(args: Map<String, String>): String {
        val action = resolveToggleAction(args)
        return resultOf {
            val cm = context.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cm.cameraIdList.firstOrNull()
                ?: return@resultOf context.getString(R.string.tool_flashlight_no_camera)
            val hasFlash = cm.getCameraCharacteristics(cameraId)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (!hasFlash) return@resultOf context.getString(R.string.tool_flashlight_unavailable)
            when (action) {
                "on" -> {
                    cm.setTorchMode(cameraId, true)
                    lastFlashlightOn = true
                    context.getString(R.string.tool_flashlight_on)
                }
                "off" -> {
                    cm.setTorchMode(cameraId, false)
                    lastFlashlightOn = false
                    context.getString(R.string.tool_flashlight_off)
                }
                "status" -> when (lastFlashlightOn) {
                    true -> context.getString(R.string.tool_flashlight_state, context.getString(R.string.tool_yes))
                    false -> context.getString(R.string.tool_flashlight_state, context.getString(R.string.tool_no))
                    null -> context.getString(R.string.tool_flashlight_state_unknown)
                }
                else -> context.getString(R.string.tool_flashlight_invalid_action)
            }
        }.onError { msg, _ -> Logger.w("ToolRegistry", "手电筒操作失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_flashlight_unavailable)
    }

    companion object {
        /** A-02: 本进程内手电筒最后已知状态(null = 本会话尚未操作过,如实报告未知)。 */
        private var lastFlashlightOn: Boolean? = null
    }

    /** 控制设备振动。 */
    @Suppress("DEPRECATION")
    suspend fun execVibrate(args: Map<String, String>): String {
        val duration = args["duration_ms"]?.toLongOrNull()?.coerceIn(1, 3000) ?: 300
        return resultOf {
            val vibrator = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    android.os.VibrationEffect.createOneShot(duration, android.os.VibrationEffect.DEFAULT_AMPLITUDE),
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
            context.getString(R.string.tool_vibrate, duration)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "振动失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_installed_apps_failed)
    }

    /** 获取当前/最近前台应用包名。 */
    suspend fun execGetForegroundApp(_args: Map<String, String>): String {
        return resultOf {
            val usm = context.getSystemService(android.content.Context.USAGE_STATS_SERVICE)
                as android.app.usage.UsageStatsManager
            val end = System.currentTimeMillis()
            val start = end - 60_000
            val stats = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, start, end)
            val recent = stats?.maxByOrNull { it.lastTimeUsed }
            context.getString(R.string.tool_foreground_app, recent?.packageName ?: "未知")
        }.onError { msg, _ -> Logger.w("ToolRegistry", "获取前台应用失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_foreground_app_unknown)
    }

    /** 获取当前连接的 WiFi 信息。 */
    @Suppress("DEPRECATION")
    suspend fun execGetWifiInfo(_args: Map<String, String>): String {
        return resultOf {
            val wm = context.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            val ssid = info.ssid?.removeSurrounding("\"") ?: "未知"
            val bssid = info.bssid ?: "未知"
            val level = android.net.wifi.WifiManager.calculateSignalLevel(info.rssi, 5)
            @Suppress("DEPRECATION")
            val ip = android.text.format.Formatter.formatIpAddress(info.ipAddress)
            context.getString(R.string.tool_wifi_info, ssid, bssid, level, ip)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "获取 WiFi 信息失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_wifi_unavailable)
    }

    /** 获取已配对蓝牙设备列表。 */
    @Suppress("DEPRECATION")
    suspend fun execGetBluetoothDevices(_args: Map<String, String>): String {
        return resultOf {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                ?: return@resultOf context.getString(R.string.tool_bluetooth_unsupported)
            val stateLabel = if (adapter.isEnabled) {
                context.getString(R.string.tool_bluetooth_enabled)
            } else {
                context.getString(R.string.tool_bluetooth_disabled)
            }
            val devices = adapter.bondedDevices ?: emptySet()
            if (devices.isEmpty()) {
                "$stateLabel\n${context.getString(R.string.tool_bluetooth_empty)}"
            } else {
                val sb = StringBuilder(stateLabel)
                sb.appendLine()
                sb.appendLine(context.getString(R.string.tool_bluetooth_header, devices.size))
                devices.forEach {
                    sb.appendLine(context.getString(R.string.tool_bluetooth_item, it.name ?: "未知", it.address))
                }
                sb.toString().trimEnd()
            }
        }.onError { msg, _ -> Logger.w("ToolRegistry", "获取蓝牙设备失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_bluetooth_unsupported)
    }

    /** 打开拨号界面并预填手机号。 */
    suspend fun execMakePhoneCall(args: Map<String, String>): String {
        val phone = args["phone"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_phone_call_missing)
        val intent = android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:$phone")).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return resultOf {
            context.startActivity(intent)
            context.getString(R.string.tool_phone_call_opened, phone)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "打开拨号失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_open_url_failed, phone)
    }

    /** 打开地图应用。 */
    suspend fun execOpenMaps(args: Map<String, String>): String {
        val query = args["query"]
        val lat = args["lat"]
        val lng = args["lng"]
        val uri = if (!lat.isNullOrBlank() && !lng.isNullOrBlank()) {
            val label = if (!query.isNullOrBlank()) android.net.Uri.encode(query) else "$lat,$lng"
            "geo:$lat,$lng?q=$lat,$lng($label)"
        } else if (!query.isNullOrBlank()) {
            "geo:0,0?q=${android.net.Uri.encode(query)}"
        } else {
            return context.getString(R.string.tool_maps_missing)
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri)).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return resultOf {
            context.startActivity(intent)
            context.getString(R.string.tool_maps_opened, uri)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "打开地图失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_open_url_failed, uri)
    }

    /** URL 编码。 */
    suspend fun execUrlEncode(args: Map<String, String>): String {
        val text = args["text"] ?: return context.getString(R.string.tool_url_missing)
        return resultOf {
            context.getString(R.string.tool_url_encoded, java.net.URLEncoder.encode(text, "UTF-8"))
        }.onError { msg, _ -> Logger.w("ToolRegistry", "URL 编码失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_url_missing)
    }

    /** URL 解码。 */
    suspend fun execUrlDecode(args: Map<String, String>): String {
        val text = args["text"] ?: return context.getString(R.string.tool_url_missing)
        return resultOf {
            context.getString(R.string.tool_url_decoded, java.net.URLDecoder.decode(text, "UTF-8"))
        }.onError { msg, _ -> Logger.w("ToolRegistry", "URL 解码失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_url_missing)
    }

    /** Base64 编码。 */
    suspend fun execBase64Encode(args: Map<String, String>): String {
        val text = args["text"] ?: return context.getString(R.string.tool_url_missing)
        return resultOf {
            context.getString(R.string.tool_base64_encoded, android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))
        }.onError { msg, _ -> Logger.w("ToolRegistry", "Base64 编码失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_url_missing)
    }

    /** Base64 解码。 */
    suspend fun execBase64Decode(args: Map<String, String>): String {
        val text = args["text"] ?: return context.getString(R.string.tool_url_missing)
        return resultOf {
            val bytes = android.util.Base64.decode(text, android.util.Base64.DEFAULT)
            context.getString(R.string.tool_base64_decoded, String(bytes, Charsets.UTF_8))
        }.onError { msg, _ -> Logger.w("ToolRegistry", "Base64 解码失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_base64_failed)
    }

    /** 文本哈希。 */
    suspend fun execHashText(args: Map<String, String>): String {
        val text = args["text"] ?: return context.getString(R.string.tool_hash_missing)
        val algo = args["algorithm"]?.uppercase() ?: "SHA-256"
        if (algo !in setOf("MD5", "SHA-1", "SHA-256")) {
            return context.getString(R.string.tool_hash_unsupported, algo)
        }
        return resultOf {
            val digest = java.security.MessageDigest.getInstance(algo)
            val hash = digest.digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            context.getString(R.string.tool_hash_result, algo, hash)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "哈希计算失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_hash_missing)
    }

    /** 生成随机 UUID。 */
    suspend fun execGenerateUuid(_args: Map<String, String>): String {
        return context.getString(R.string.tool_uuid_result, java.util.UUID.randomUUID().toString())
    }

    /** 生成指定范围随机整数。 */
    suspend fun execRandomNumber(args: Map<String, String>): String {
        var min = args["min"]?.toIntOrNull() ?: 0
        var max = args["max"]?.toIntOrNull() ?: 100
        if (min > max) {
            min = max.also { max = min }
        }
        return context.getString(R.string.tool_random_number_result, kotlin.random.Random.nextInt(min, max + 1))
    }

    // ── 辅助函数 ─────────────────────────────────────────────────────────────

    /** 把字节数格式化为人类可读字符串。 */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.size - 1) {
            value /= 1024
            unitIndex++
        }
        return String.format(Locale.getDefault(), "%.2f %s", value, units[unitIndex])
    }

    /** 从 /proc/cpuinfo 读取指定字段。 */
    fun readProcCpuField(key: String): String? {
        return try {
            java.io.File("/proc/cpuinfo").useLines { lines ->
                lines.mapNotNull { line ->
                    val parts = line.split(":", limit = 2)
                    if (parts.size == 2 && parts[0].trim().equals(key, ignoreCase = true)) {
                        parts[1].trim()
                    } else null
                }.firstOrNull()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 解析音频流类型。 */
    fun resolveAudioStream(name: String?): Pair<Int, String> {
        return when (name?.lowercase()) {
            "ring", "ringtone" -> android.media.AudioManager.STREAM_RING to context.getString(R.string.tool_volume_stream_ring)
            "alarm" -> android.media.AudioManager.STREAM_ALARM to context.getString(R.string.tool_volume_stream_alarm)
            "notification" -> android.media.AudioManager.STREAM_NOTIFICATION to context.getString(R.string.tool_volume_stream_notification)
            "call", "voice_call" -> android.media.AudioManager.STREAM_VOICE_CALL to context.getString(R.string.tool_volume_stream_call)
            "system" -> android.media.AudioManager.STREAM_SYSTEM to context.getString(R.string.tool_volume_stream_system)
            else -> android.media.AudioManager.STREAM_MUSIC to context.getString(R.string.tool_volume_stream_music)
        }
    }


    suspend fun execScreenTime(_args: Map<String, String>): String {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
            as android.app.usage.UsageStatsManager
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        val end = System.currentTimeMillis()
        val stats = usm.queryAndAggregateUsageStats(start, end)
        if (stats.isNullOrEmpty()) {
            return context.getString(R.string.tool_screen_time_unavailable)
        }
        val sorted = stats.values.sortedByDescending { it.totalTimeInForeground }
        val sb = StringBuilder(context.getString(R.string.tool_screen_time_header))
        val pm = context.packageManager
        for (stat in sorted.take(10)) {
            // M-TR1: 改用 resultOf{}(正确重抛 CancellationException)
            val name = resultOf {
                pm.getApplicationLabel(pm.getApplicationInfo(stat.packageName, 0)).toString()
            }.getOrNull() ?: stat.packageName
            val minutes = stat.totalTimeInForeground / 60000
            if (minutes > 0) {
                sb.appendLine(context.getString(R.string.tool_screen_time_item, name, minutes))
            }
        }
        return sb.toString().trimEnd()
    }
}
