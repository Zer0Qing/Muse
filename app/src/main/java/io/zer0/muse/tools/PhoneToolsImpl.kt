package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R

/**
 * P1-3b 拆域：手机端工具实现（从 ToolRegistry.kt 迁移，对象封装以持有 context）。
 * 由 PhoneToolsRegistrar 注册到 ToolRegistry。
 */
class PhoneToolsImpl(private val context: Context) {
// ── 手机端工具实现(7 个)──────────────────────────────────────────────

    /** 设置系统闹钟:通过 AlarmClock.ACTION_SET_ALARM 拉起系统时钟应用。无需运行时权限。 */
    suspend fun execSetAlarm(args: Map<String, String>): String {
        val hour = args["hour"]?.toIntOrNull()
            ?: return context.getString(R.string.tool_missing_param_hour)
        if (hour !in 0..23) return context.getString(R.string.tool_hour_range)
        val minute = args["minute"]?.toIntOrNull()
            ?: return context.getString(R.string.tool_missing_param_minute)
        if (minute !in 0..59) return context.getString(R.string.tool_minute_range)
        val label = args["label"]?.takeIf { it.isNotBlank() } ?: context.getString(R.string.tool_alarm_label_default)
        val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
            putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // days_of_week:每周重复,如 "MON,TUE,WED,THU,FRI"
        // v1.47: weekdays 快捷参数 — true=工作日(MON-FRI), weekends=true=周末(SAT,SUN)
        val weekdays = args["weekdays"]?.toBoolean() ?: false
        val weekends = args["weekends"]?.toBoolean() ?: false
        val daysStr = when {
            weekdays -> "MON,TUE,WED,THU,FRI"
            weekends -> "SAT,SUN"
            else -> args["days_of_week"]?.takeIf { it.isNotBlank() }
        }
        if (daysStr != null) {
            val dayMap = mapOf(
                "SUN" to java.util.Calendar.SUNDAY,
                "MON" to java.util.Calendar.MONDAY,
                "TUE" to java.util.Calendar.TUESDAY,
                "WED" to java.util.Calendar.WEDNESDAY,
                "THU" to java.util.Calendar.THURSDAY,
                "FRI" to java.util.Calendar.FRIDAY,
                "SAT" to java.util.Calendar.SATURDAY,
            )
            val days = daysStr.split(",").mapNotNull { dayMap[it.trim().uppercase()] }
            if (days.isNotEmpty()) {
                intent.putExtra(android.provider.AlarmClock.EXTRA_DAYS, ArrayList(days))
            }
        }
        // M-TR1: 改用 resultOf{}(正确重抛 CancellationException)
        return resultOf {
            context.startActivity(intent)
            val repeat = daysStr?.let { context.getString(R.string.tool_alarm_repeat, it) } ?: ""
            context.getString(R.string.tool_alarm_set, "%02d".format(hour), "%02d".format(minute), label, repeat)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "设置闹钟失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_alarm_set_failed)
    }

    /** 设置系统倒计时:通过 AlarmClock.ACTION_SET_TIMER 拉起系统时钟应用。无需运行时权限。 */
    suspend fun execSetTimer(args: Map<String, String>): String {
        val seconds = args["seconds"]?.toIntOrNull()
            ?: return context.getString(R.string.tool_missing_param_seconds)
        if (seconds <= 0) return context.getString(R.string.tool_seconds_positive)
        val label = args["label"]?.takeIf { it.isNotBlank() } ?: context.getString(R.string.tool_timer_label_default)
        val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // M-TR1: 改用 resultOf{}(正确重抛 CancellationException)
        return resultOf {
            context.startActivity(intent)
            context.getString(R.string.tool_timer_set, seconds, label)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "设置倒计时失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_timer_set_failed)
    }

    /** 打开应用:支持 Deep Link(data_uri)、自定义 action,或通过包名启动主界面。 */
    suspend fun execOpenApp(args: Map<String, String>): String {
        val dataUri = args["data_uri"]?.takeIf { it.isNotBlank() }
        val action = args["action"]?.takeIf { it.isNotBlank() }
        val packageName = args["packageName"]?.takeIf { it.isNotBlank() }
        // 三选一优先级:data_uri > action > packageName
        // M-TR1: 改用 resultOf{}(正确重抛 CancellationException)
        return resultOf {
            if (dataUri != null) {
                // Deep Link 跳转
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(dataUri)).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                context.getString(R.string.tool_deep_link_opened, dataUri)
            } else if (action != null) {
                // 自定义 action 启动
                val intent = android.content.Intent(action).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                context.getString(R.string.tool_action_started, action)
            } else {
                // 包名启动主界面
                if (packageName == null) return context.getString(R.string.tool_missing_param_package_or_action)
                val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                    ?: return context.getString(R.string.tool_app_not_found, packageName)
                launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                context.getString(R.string.tool_app_opened, packageName)
            }
        }.onError { msg, _ -> Logger.w("ToolRegistry", "打开应用失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_open_app_failed)
    }

    /** 分享文本:通过 ACTION_SEND + createChooser 弹出系统分享面板。 */
    suspend fun execShareText(args: Map<String, String>): String {
        val text = args["text"]?.trim()
            ?: return context.getString(R.string.tool_missing_param_text_share)
        if (text.isEmpty()) return context.getString(R.string.tool_text_empty)
        val mimeType = args["mime_type"]?.takeIf { it.isNotBlank() } ?: "text/plain"
        val title = args["title"]?.takeIf { it.isNotBlank() } ?: context.getString(R.string.tool_share_title_default)
        val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(android.content.Intent.EXTRA_TEXT, text)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = android.content.Intent.createChooser(sendIntent, title).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // M-TR1: 改用 resultOf{}(正确重抛 CancellationException)
        return resultOf {
            context.startActivity(chooser)
            context.getString(R.string.tool_text_shared, text.take(50) + if (text.length > 50) "..." else "")
        }.onError { msg, _ -> Logger.w("ToolRegistry", "分享失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_share_failed)
    }

    /**
     * 获取粗略位置:读取系统最后已知位置(不主动申请权限,不开启 GPS)。
     * 需 ACCESS_COARSE_LOCATION 运行时权限;未授权时返回提示,不崩溃。
     * provider 参数可选 network/gps(默认遍历所有 provider);timeout 参数预留(本期基于最后已知位置,不阻塞等待)。
     */
    suspend fun execGetLocation(args: Map<String, String>): String {
        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            return context.getString(R.string.tool_location_no_permission)
        }
        val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE)
            as android.location.LocationManager
        // provider 参数:优先用指定的(network/gps),否则遍历所有 provider 取最新
        val providerParam = args["provider"]?.trim()?.lowercase()
        // timeout 参数读取(预留接口,本期基于最后已知位置不阻塞等待)
        val location = if (providerParam != null &&
            lm.allProviders.any { it.equals(providerParam, ignoreCase = true) }) {
            // M-TR1: 改用 resultOf{}(正确重抛 CancellationException)
            resultOf { lm.getLastKnownLocation(providerParam) }.getOrNull()
                ?: lm.allProviders.mapNotNull { p ->
                    resultOf { lm.getLastKnownLocation(p) }.getOrNull()
                }.maxByOrNull { it.time }
        } else {
            lm.allProviders.mapNotNull { p ->
                resultOf { lm.getLastKnownLocation(p) }.getOrNull()
            }.maxByOrNull { it.time }
        } ?: return context.getString(R.string.tool_location_unavailable)
        return context.getString(
            R.string.tool_location_result,
            "%.4f".format(location.latitude),
            "%.4f".format(location.longitude),
            "%.0f".format(location.accuracy),
        )
    }

    /** 获取设备信息:品牌/型号/Android 版本/屏幕分辨率/电量。 */
    suspend fun execGetDeviceInfo(_args: Map<String, String>): String {
        // M-TR1: 改用 resultOf{}(正确重抛 CancellationException)
        val batteryLevel = resultOf {
            val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE)
                as android.os.BatteryManager
            "${bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)}%"
        }.getOrNull() ?: context.getString(R.string.tool_device_unknown)
        val dm = context.resources.displayMetrics
        return buildString {
            appendLine(context.getString(R.string.tool_device_brand, android.os.Build.BRAND))
            appendLine(context.getString(R.string.tool_device_model, android.os.Build.MODEL))
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine(context.getString(R.string.tool_device_screen, dm.widthPixels, dm.heightPixels))
            append(context.getString(R.string.tool_device_battery, batteryLevel))
        }
    }

    /**
     * v1.0.53: get_device_info 结构化版 — 附加 brand/model/androidVersion/sdkInt details。
     * 品牌/型号/版本均为 Build 常量,零额外 IO。
     */
    suspend fun execGetDeviceInfoOutcome(_args: Map<String, String>): ToolOutcome {
        val text = execGetDeviceInfo(_args)
        return ToolOutcome.ok(
            text,
            details = mapOf(
                "brand" to android.os.Build.BRAND,
                "model" to android.os.Build.MODEL,
                "androidVersion" to android.os.Build.VERSION.RELEASE,
                "sdkInt" to android.os.Build.VERSION.SDK_INT,
            ),
        )
    }

    /** 获取通讯录联系人数量:需 READ_CONTACTS 运行时权限。支持按名称 filter 过滤后计数。 */
    suspend fun execGetContactsCount(args: Map<String, String>): String {
        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_CONTACTS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            return context.getString(R.string.tool_contacts_no_permission)
        }
        val filter = args["filter"]?.takeIf { it.isNotBlank() }
        // M-TR3: 转义 LIKE 通配符(% _ \),加 ESCAPE '\' 子句,防止 filter 含 % _ 时误匹配
        val selection = if (filter != null)
            "${android.provider.ContactsContract.Contacts.DISPLAY_NAME} LIKE ? ESCAPE '\\'" else null
        val selectionArgs = if (filter != null) {
            val escaped = filter.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            arrayOf("%$escaped%")
        } else null
        // M-TR1: 改用 resultOf{}(正确重抛 CancellationException)
        return resultOf {
            val cursor = context.contentResolver.query(
                android.provider.ContactsContract.Contacts.CONTENT_URI,
                null, selection, selectionArgs, null,
            )
            val count = cursor?.use { it.count } ?: 0
            if (filter != null) context.getString(R.string.tool_contacts_count_filtered, filter, count)
            else context.getString(R.string.tool_contacts_count, count)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "读取联系人失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_contacts_read_failed)
    }

    /**
     * 获取联系人列表(增强版):每行返回 "name | phone"。
     * 需 READ_CONTACTS 运行时权限;支持按名称 filter 过滤、limit 限制返回数量。
     */
    suspend fun execGetContactsList(args: Map<String, String>): String {
        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_CONTACTS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            return context.getString(R.string.tool_contacts_no_permission)
        }
        val filter = args["filter"]?.takeIf { it.isNotBlank() }
        val limit = args["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: 20
        // M-TR1: 改用 resultOf{}(正确重抛 CancellationException)
        return resultOf {
            val projection = arrayOf(
                android.provider.ContactsContract.Contacts._ID,
                android.provider.ContactsContract.Contacts.DISPLAY_NAME,
                android.provider.ContactsContract.Contacts.HAS_PHONE_NUMBER,
            )
            // M-TR3: 转义 LIKE 通配符(% _ \),加 ESCAPE '\' 子句
            val selection = if (filter != null)
                "${android.provider.ContactsContract.Contacts.DISPLAY_NAME} LIKE ? ESCAPE '\\'" else null
            val selectionArgs = if (filter != null) {
                val escaped = filter.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                arrayOf("%$escaped%")
            } else null
            val sortOrder = "${android.provider.ContactsContract.Contacts.DISPLAY_NAME} ASC"
            val sb = StringBuilder()
            var count = 0
            context.contentResolver.query(
                android.provider.ContactsContract.Contacts.CONTENT_URI,
                projection, selection, selectionArgs, sortOrder,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(android.provider.ContactsContract.Contacts._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(android.provider.ContactsContract.Contacts.DISPLAY_NAME)
                val hasPhoneIdx = cursor.getColumnIndexOrThrow(android.provider.ContactsContract.Contacts.HAS_PHONE_NUMBER)
                while (cursor.moveToNext() && count < limit) {
                    val id = cursor.getLong(idIdx)
                    val name = cursor.getString(nameIdx) ?: context.getString(R.string.tool_no_name)
                    val phone = if (cursor.getInt(hasPhoneIdx) > 0) {
                        // 查询该联系人的电话号码(取第一个)
                        // M-TR1: 改用 resultOf{}(正确重抛 CancellationException)
                        resultOf {
                            context.contentResolver.query(
                                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                                "${android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                                arrayOf(id.toString()),
                                null,
                            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: ""
                        }.getOrNull() ?: ""
                    } else ""
                    sb.appendLine("$name | $phone")
                    count++
                }
            }
            if (count == 0) {
                if (filter != null) context.getString(R.string.tool_contacts_not_found_filtered, filter) else context.getString(R.string.tool_contacts_empty)
            } else {
                sb.toString().trimEnd()
            }
        }.onError { msg, _ -> Logger.w("ToolRegistry", "读取联系人列表失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_contacts_list_read_failed)
    }

    /**
     * 发送短信:有 SEND_SMS 权限且 body 非空时直接发送,否则打开系统短信应用预填。
     * slot 参数为双卡预留(本期不实现 SubscriptionManager 调度)。
     */
    suspend fun execSendSms(args: Map<String, String>): String {
        val phone = args["phone"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_missing_param_phone)
        val body = args["body"] ?: ""
        // slot 参数读取(预留,本期不实现双卡选择)
        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.SEND_SMS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasPermission && body.isNotEmpty()) {
            // M-TR1: 改用 resultOf{}(正确重抛 CancellationException)
            return resultOf {
                val sentIntent = android.app.PendingIntent.getBroadcast(
                    context, 0,
                    android.content.Intent("SMS_SENT"),
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                )
                // M-TR2: SmsManager.getDefault() 在 API 31+ 已废弃,改用 Context.getSystemService
                val smsManager = context.getSystemService(android.telephony.SmsManager::class.java)
                    ?: return@resultOf context.getString(R.string.tool_sms_send_failed_no_manager)
                smsManager.sendTextMessage(phone, null, body, sentIntent, null)
                context.getString(R.string.tool_sms_sent, phone)
            }.onError { msg, _ -> Logger.w("ToolRegistry", "发送短信失败: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_sms_send_failed)
        }
        // 无权限或无 body:打开系统短信应用预填
        // M-TR1: 改用 resultOf{}(正确重抛 CancellationException)
        return resultOf {
            val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                data = android.net.Uri.parse("smsto:$phone")
                putExtra("sms_body", body)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            context.getString(R.string.tool_sms_app_opened, phone)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "打开短信应用失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_sms_app_open_failed)
    }

    /**
     * 新建联系人:通过 Intent.ACTION_INSERT 打开系统新建联系人表单,
     * 预填姓名/电话/邮箱。无需运行时权限(由系统通讯录应用承接)。
     */
    suspend fun execAddContact(args: Map<String, String>): String {
        val name = args["name"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_missing_param_name)
        // M-TR1: 改用 resultOf{}(正确重抛 CancellationException)
        return resultOf {
            val intent = android.content.Intent(android.content.Intent.ACTION_INSERT).apply {
                type = android.provider.ContactsContract.Contacts.CONTENT_TYPE
                putExtra(android.provider.ContactsContract.Intents.Insert.NAME, name)
                args["phone"]?.takeIf { it.isNotBlank() }?.let {
                    putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, it)
                }
                args["email"]?.takeIf { it.isNotBlank() }?.let {
                    putExtra(android.provider.ContactsContract.Intents.Insert.EMAIL, it)
                }
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            context.getString(R.string.tool_contact_form_opened, name)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "打开新建联系人表单失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_contact_form_open_failed)
    }

/** 打开系统拨号界面并预填手机号。 */
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

}