package io.zer0.muse.tools

import android.content.Context
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.ProviderError
import io.zer0.ai.core.ProviderException
import io.zer0.ai.core.providerError
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import android.annotation.SuppressLint
import io.zer0.common.Logger
import io.zer0.common.resultOf
import org.koin.core.context.GlobalContext
import io.zer0.muse.R
import io.zer0.muse.data.quicknote.QuickNoteDao
import io.zer0.muse.data.quicknote.QuickNoteEntity
import io.zer0.muse.data.schedule.ScheduledTaskDao
import io.zer0.muse.data.schedule.ScheduledTaskEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.notification.MuseNotificationListenerService
import io.zer0.muse.tools.reminder.ReminderAlarmReceiver
import io.zer0.muse.tools.reminder.ReminderStore
import io.zer0.muse.tools.resource.ResourceLibraryStore
import io.zer0.muse.transformer.stripThinkTags
import io.zer0.muse.util.MusePatterns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 5-H: 工具注册表(简化版 MCP 框架)。
 *
 * Phase 7 扩展:接入 LLM function calling —
 *  - [listToolsAsToolDefinitions] 生成 OpenAI 兼容的 ToolDefinition 列表
 *  - [executeFromJson] 从 LLM 返回的 arguments JSON 字符串执行工具
 *
 * Phase 8.8 扩展:
 *  - 改为动态注册(mutableMap + [register] API),支持运行时增删工具
 *  - 内置 7 个本地工具(本地工具 7 件套,无 QuickJS):
 *    1. get_current_time — 获取当前时间(支持时区参数)
 *    2. calculator — 简易计算器(四则运算)
 *    3. echo — 回显(测试用)
 *    4. clipboard_read — 读取系统剪贴板
 *    5. clipboard_write — 写入系统剪贴板
 *    6. screen_time — 获取今日屏幕使用时间统计(UsageStatsManager)
 *    7. calendar_today — 获取今日日历事件(CalendarContract)
 *  - 手机端工具(10 个,Android 系统 API):
 *    8. set_alarm — 设置闹钟(AlarmClock,支持每周重复)
 *    9. set_timer — 设置倒计时(AlarmClock)
 *    10. open_app — 打开应用(PackageManager,支持 Deep Link)
 *    11. share_text — 分享文本(ACTION_SEND)
 *    12. get_location — 获取粗略位置(LocationManager,需 ACCESS_COARSE_LOCATION)
 *    13. get_device_info — 获取设备信息(Build/BatteryManager)
 *    14. get_contacts_count — 联系人数量(ContactsContract,需 READ_CONTACTS)
 *    15. get_contacts_list — 联系人列表(增强版,需 READ_CONTACTS)
 *    16. send_sms — 发送短信(SmsManager,需 SEND_SMS)
 *    17. add_contact — 新建联系人(Intent.ACTION_INSERT)
 *  - 系统控制与邮件工具(5 个):
 *    18. open_system_setting — 打开系统设置页(支持 wifi/bluetooth/display 等分类)
 *    19. toggle_wifi — 开关 WiFi(Android 10+ 跳设置页,可读状态)
 *    20. toggle_bluetooth — 开关蓝牙(可读状态/关闭/跳设置页开启)
 *    21. send_email — 发送邮件(ACTION_SENDTO 打开邮件应用)
 *    22. get_battery_info — 获取电池信息(电量/充电状态)
 *  - [listToolsAsToolDefinitions] 支持 [enabledToolIds] 参数按 Assistant 过滤
 *
 * MCP 协议对标:
 *  - tools/list: [listTools] / [listToolsAsToolDefinitions]
 *  - tools/call: [execute] / [executeFromJson]
 *  - 后续扩展:从配置加载外部 MCP server(stdio/sse,Phase 9)
 *
 * @param context 应用 Context(用于需要系统服务的工具:Clipboard/UsageStats/Calendar)
 */
class ToolRegistry(private val context: Context) {

    /** 工具定义(UI 展示用,parameters 是参数名 → 描述)。
     *
     * v1.???: 新增 [parameterTypes] 支持为每个参数指定 JSON Schema type,解决
     * execute_javascript 的 timeout_ms 等数字/布尔参数被 LLM 误传为 string 的问题。
     * 未指定时默认 type=string,保持与旧 [parameters] 行为的兼容性。
     */
    data class ToolDef(
        val name: String,
        val description: String,
        val parameters: Map<String, String>, // 参数名 → 描述
        /** 是否必填。 */
        val required: Set<String> = emptySet(),
        /** 工具分类(用于 UI 分组展示):built-in / local / mcp。 */
        val category: String = "built-in",
        /** 参数名 → JSON Schema type(如 integer / boolean),未指定默认 string。 */
        val parameterTypes: Map<String, String> = emptyMap(),
        /** 工具风险等级,用于会话权限体系。 */
        val riskLevel: ToolRiskLevel = ToolRiskLevel.SAFE,
    )

    /**
     * 工具执行函数:参数 map → 结果字符串。
     *
     * v1.134 P0-3: 改为 suspend 函数类型,消除 McpRegistry 的 runBlocking 反模式。
     * 内置工具大多是同步 IO(ContentResolver 查询 / LocationManager 读取等),
     * 加 suspend 关键字不影响内部实现,只是让方法引用类型匹配;
     * MCP 远程调用可直接在协程上下文中执行,超时用 withTimeoutOrNull 替代线程池超时。
     */
    private typealias ToolFn = suspend (Map<String, String>) -> String

    /** v1.0.53: 结构化结果执行函数(返回 [ToolOutcome])。 */
    private typealias ToolOutcomeFn = suspend (Map<String, String>) -> ToolOutcome

    // M-TR2: 改用 ConcurrentHashMap,保证 register/unregister/execute 并发安全
    private val tools = ConcurrentHashMap<String, ToolFn>()
    // v1.0.53: 结构化结果工具通道(优先于 [tools] 查找)
    private val outcomeTools = ConcurrentHashMap<String, ToolOutcomeFn>()
    private val toolDefs = ConcurrentHashMap<String, ToolDef>()

    // v1.136: 定时提醒、资源库
    // v1.0.17: 快速记录改用 Room(MuseDb.get(context).quickNoteDao()),不再持有 QuickNoteStore
    private val reminderStore = ReminderStore(context)
    private val resourceLibrary = ResourceLibraryStore(context)

    init {
        // 注册内置 7 件套
        registerBuiltIn("get_weather",
            "获取天气信息。通过城市名查询当前天气、温度、湿度、风速等。",
            mapOf("location" to "必填,城市名,支持中英文,如 Beijing/上海/London"),
            setOf("location"),
            ::execGetWeather,
        )
        registerBuiltIn("get_current_time",
            "获取当前时间。可指定时区(如 Asia/Shanghai、UTC、America/New_York),自动标注 DST 夏令时状态。",
            mapOf(
                "timezone" to "可选,IANA 时区标识(如 Asia/Shanghai/UTC/America/New_York),默认 Asia/Shanghai。传 UTC 可得协调世界时",
                "format" to "可选,自定义时间格式,默认 yyyy-MM-dd HH:mm:ss z",
            ),
            emptySet(),
            ::execGetCurrentTime,
        )
        registerBuiltIn("calculator",
            "简易计算器,支持加减乘除和括号。输入表达式字符串。",
            mapOf("expression" to "必填,数学表达式,如 1+2*3"),
            setOf("expression"),
            ::execCalculator,
        )
        registerBuiltIn("echo",
            "回显输入内容(测试用)。",
            mapOf("text" to "必填,要回显的文本"),
            setOf("text"),
            ::execEcho,
        )
        registerBuiltIn("clipboard_read",
            "读取系统剪贴板文本内容。",
            emptyMap(),
            emptySet(),
            ::execClipboardRead,
            riskLevel = ToolRiskLevel.NORMAL,
        )
        // v1.0.53: 结构化结果版(details: hasText/length)
        registerBuiltInOutcome("clipboard_read",
            "读取系统剪贴板文本内容。",
            emptyMap(),
            emptySet(),
            ::execClipboardReadOutcome,
            riskLevel = ToolRiskLevel.NORMAL,
        )
        registerBuiltIn("clipboard_write",
            "写入文本到系统剪贴板。",
            mapOf(
                "text" to "必填,要写入剪贴板的文本",
                "label" to "可选,剪贴板标签,如 muse_copy",
            ),
            setOf("text"),
            ::execClipboardWrite,
            riskLevel = ToolRiskLevel.NORMAL,
        )
        registerBuiltIn("screen_time",
            "获取今日各应用屏幕使用时间统计(前 10 名)。需要 PACKAGE_USAGE_STATS 权限。",
            emptyMap(),
            emptySet(),
            ::execScreenTime,
            riskLevel = ToolRiskLevel.NORMAL,
        )
        registerBuiltIn("calendar_today",
            "获取今日日历事件列表。需要 READ_CALENDAR 权限。",
            mapOf(
                "date" to "可选,指定日期 YYYY-MM-DD,默认今天",
                "days" to "可选,查询未来几天,默认 0=只今天",
            ),
            emptySet(),
            ::execCalendarToday,
            riskLevel = ToolRiskLevel.NORMAL,
        )
        registerBuiltIn("add_calendar_event",
            "向系统日历添加一个新事件。需要 WRITE_CALENDAR 权限。",
            mapOf(
                "title" to "必填,事件标题",
                "start_time" to "必填,开始时间 ISO 8601 或 yyyy-MM-dd HH:mm,如 2026-07-10 09:00",
                "end_time" to "可选,结束时间,默认开始时间+1小时",
                "description" to "可选,事件描述",
                "location" to "可选,地点",
                "all_day" to "可选,true/false,默认 false",
            ),
            setOf("title", "start_time"),
            ::execAddCalendarEvent,
            riskLevel = ToolRiskLevel.HIGH,
        )
        // 手机端工具(10 个):闹钟/倒计时/打开应用/分享/位置/设备信息/联系人
        registerBuiltIn("set_alarm",
            "设置系统闹钟(通过 AlarmClock.ACTION_SET_ALARM,系统时钟应用承接)。",
            mapOf(
                "hour" to "必填,闹钟小时(0-23)",
                "minute" to "必填,闹钟分钟(0-59)",
                "label" to "可选,闹钟标签,默认 'muse 闹钟'",
                "days_of_week" to "可选,每周重复,如 MON,TUE,WED,THU,FRI",
                "weekdays" to "可选,快捷设工作日重复(周一至周五),传 true 即展开为 MON-FRI,优先于 days_of_week",
                "weekends" to "可选,快捷设周末重复(周六周日),传 true 即展开为 SAT,SUN,优先于 days_of_week",
            ),
            setOf("hour", "minute"),
            ::execSetAlarm,
            riskLevel = ToolRiskLevel.NORMAL,
        )
        registerBuiltIn("set_timer",
            "设置系统倒计时(通过 AlarmClock.ACTION_SET_TIMER,系统时钟应用承接)。",
            mapOf(
                "seconds" to "必填,倒计时秒数",
                "label" to "可选,倒计时标签,默认 'muse 倒计时'",
            ),
            setOf("seconds"),
            ::execSetTimer,
            riskLevel = ToolRiskLevel.NORMAL,
        )
        registerBuiltIn("open_app",
            "打开指定应用(通过包名启动其主 Activity),或通过 Deep Link/自定义 action 跳转。",
            mapOf(
                "packageName" to "可选,应用包名,如 com.tencent.mm(微信)/com.tencent.mobileqq(QQ)",
                "action" to "可选,自定义 action(如 com.example.MY_ACTION)",
                "data_uri" to "可选,Deep Link URI,如 myapp://page/123",
            ),
            emptySet(),
            ::execOpenApp,
            riskLevel = ToolRiskLevel.NORMAL,
        )
        registerBuiltIn("share_text",
            "通过系统分享面板分享文本(弹出选择器)。",
            mapOf(
                "text" to "必填,要分享的文本",
                "mime_type" to "可选,MIME 类型,默认 text/plain",
                "title" to "可选,分享面板标题,默认 '分享'",
            ),
            setOf("text"),
            ::execShareText,
            riskLevel = ToolRiskLevel.NORMAL,
        )
        registerBuiltIn("get_location",
            "获取当前粗略位置(系统最后已知位置)。需要 ACCESS_COARSE_LOCATION 权限。",
            mapOf(
                "provider" to "可选,network 或 gps,默认 network",
                "timeout" to "可选,超时秒数,默认 10",
            ),
            emptySet(),
            ::execGetLocation,
            riskLevel = ToolRiskLevel.NORMAL,
        )
        registerBuiltIn("get_device_info",
            "获取设备信息:品牌/型号/Android 版本/屏幕分辨率/电量。",
            emptyMap(),
            emptySet(),
            ::execGetDeviceInfo,
        )
        // v1.0.53: 结构化结果版(details: brand/model/androidVersion/sdkInt)
        registerBuiltInOutcome("get_device_info",
            "获取设备信息:品牌/型号/Android 版本/屏幕分辨率/电量。",
            emptyMap(),
            emptySet(),
            ::execGetDeviceInfoOutcome,
        )
        registerBuiltIn("get_contacts_count",
            "获取通讯录联系人数量。需要 READ_CONTACTS 权限。",
            mapOf("filter" to "可选,按名称过滤后计数"),
            emptySet(),
            ::execGetContactsCount,
            riskLevel = ToolRiskLevel.HIGH,
        )
        // 新增手机端工具(3 个):联系人列表/发短信/新建联系人
        registerBuiltIn("get_contacts_list",
            "获取联系人列表(每行一个:name | phone)。需要 READ_CONTACTS 权限。",
            mapOf(
                "filter" to "可选,按名称过滤",
                "limit" to "可选,最多返回数量,默认 20",
            ),
            emptySet(),
            ::execGetContactsList,
            riskLevel = ToolRiskLevel.HIGH,
        )
        registerBuiltIn("send_sms",
            "发送短信。需要 SEND_SMS 权限(无权限或无 body 时打开系统短信应用预填)。",
            mapOf(
                "phone" to "必填,目标手机号",
                "body" to "可选,短信正文(纯文本)",
                "slot" to "可选,双卡 slot 0/1(预留,默认主卡)",
            ),
            setOf("phone"),
            ::execSendSms,
            riskLevel = ToolRiskLevel.HIGH,
        )
        registerBuiltIn("add_contact",
            "新建联系人(打开系统新建联系人表单,预填姓名/电话/邮箱)。",
            mapOf(
                "name" to "必填,联系人姓名",
                "phone" to "可选,电话号码",
                "email" to "可选,邮箱",
            ),
            setOf("name"),
            ::execAddContact,
            riskLevel = ToolRiskLevel.HIGH,
        )
        // 系统控制与邮件工具(5 个):系统设置/WiFi/蓝牙/邮件/电池
        registerBuiltIn("open_system_setting",
            "打开系统设置页(支持 wifi/bluetooth/display/sound/location 等分类)。",
            mapOf(
                "category" to "可选,设置分类:settings(默认)/wifi/bluetooth/display/sound/location/app_settings/battery/storage/security/date_time",
            ),
            emptySet(),
            ::execOpenSystemSetting,
            riskLevel = ToolRiskLevel.NORMAL,
        )
        registerBuiltIn("toggle_wifi",
            "开关 WiFi。action: on/off/status。Android 10+ 无法直接开关,on/off 跳设置页。",
            mapOf(
                "action" to "必填,on/off/status",
            ),
            setOf("action"),
            ::execToggleWifi,
            riskLevel = ToolRiskLevel.HIGH,
        )
        registerBuiltIn("toggle_bluetooth",
            "开关蓝牙。action: on/off/status。on 跳设置页,off 直接关闭(需 BLUETOOTH_ADMIN)。",
            mapOf(
                "action" to "必填,on/off/status",
            ),
            setOf("action"),
            ::execToggleBluetooth,
            riskLevel = ToolRiskLevel.HIGH,
        )
        registerBuiltIn("send_email",
            "发送邮件(通过 ACTION_SENDTO 打开邮件应用,预填收件人/主题/正文)。使用时机: 用户明确要求发邮件时调用。不要使用: 用户未要求发邮件时;发送前如正文含敏感信息,应提示用户确认。",
            mapOf(
                "to" to "必填,收件人邮箱,多个用逗号分隔",
                "subject" to "可选,邮件主题",
                "body" to "可选,邮件正文",
            ),
            setOf("to"),
            ::execSendEmail,
            riskLevel = ToolRiskLevel.NORMAL,
        )
        registerBuiltIn("get_battery_info",
            "获取电池信息(电量百分比/充电状态)。",
            emptyMap(),
            emptySet(),
            ::execGetBatteryInfo,
        )
        // P2: 通知监听工具(需用户在系统设置中授权通知使用权)
        registerBuiltIn("get_recent_notifications",
            "获取最近收到的应用通知(需用户在系统设置中授权通知使用权)。",
            mapOf(
                "limit" to "返回数量,默认 20",
                "package_name" to "按应用包名筛选(可选)",
            ),
            emptySet(),
            ::execGetRecentNotifications,
            riskLevel = ToolRiskLevel.NORMAL,
        )
        // 系统信息与应用工具
        registerBuiltIn("open_url",
            "打开指定的 URL(网页或 Deep Link)。仅支持 http:// 或 https:// 开头的链接,会用系统浏览器打开。使用时机: 用户要求打开网页/链接时。不要使用: 未知协议(file://、javascript: 等)或疑似钓鱼链接;优先使用 https。",
            mapOf("url" to "必填,要打开的 URL 链接,如 https://example.com"),
            setOf("url"),
            ::execOpenUrl,
            riskLevel = ToolRiskLevel.NORMAL,
        )
        registerBuiltIn("list_installed_apps",
            "列出设备上已安装的应用(应用名 | 包名)。可用于查询某个应用是否已安装。",
            mapOf(
                "filter" to "可选,按应用名或包名过滤(忽略大小写)",
                "limit" to "可选,最多返回数量,默认 20",
                "include_system" to "可选,true/false 是否包含系统应用,默认 false",
            ),
            emptySet(),
            ::execListInstalledApps,
            riskLevel = ToolRiskLevel.NORMAL,
            parameterTypes = mapOf("limit" to "integer", "include_system" to "boolean"),
        )
        registerBuiltIn("get_network_info",
            "获取当前网络连接信息:是否联网、网络类型(WiFi/蜂窝/VPN/以太网)、是否按量计费。",
            emptyMap(),
            emptySet(),
            ::execGetNetworkInfo,
            riskLevel = ToolRiskLevel.SAFE,
        )

        // 设备信息工具(5 个):存储/内存/屏幕/CPU/传感器
        registerBuiltIn("get_storage_info",
            "获取内部存储空间信息:总容量、已用、可用。",
            emptyMap(),
            emptySet(),
            ::execGetStorageInfo,
            riskLevel = ToolRiskLevel.SAFE,
        )
        registerBuiltIn("get_memory_info",
            "获取设备内存(RAM)信息:总内存、可用内存、是否低内存。",
            emptyMap(),
            emptySet(),
            ::execGetMemoryInfo,
            riskLevel = ToolRiskLevel.SAFE,
        )
        registerBuiltIn("get_display_info",
            "获取屏幕信息:分辨率、屏幕密度、刷新率。",
            emptyMap(),
            emptySet(),
            ::execGetDisplayInfo,
            riskLevel = ToolRiskLevel.SAFE,
        )
        registerBuiltIn("get_cpu_info",
            "获取 CPU 信息:型号、核心数。",
            emptyMap(),
            emptySet(),
            ::execGetCpuInfo,
            riskLevel = ToolRiskLevel.SAFE,
        )
        registerBuiltIn("get_sensors_list",
            "获取设备传感器列表(名称、厂商、版本)。",
            mapOf("limit" to "可选,最多返回数量,默认 30"),
            emptySet(),
            ::execGetSensorsList,
            riskLevel = ToolRiskLevel.SAFE,
            parameterTypes = mapOf("limit" to "integer"),
        )

        // 系统控制工具(6 个):亮度/音量/手电筒/振动
        registerBuiltIn("get_brightness",
            "获取当前屏幕亮度(0-255)和模式(自动/手动)。",
            emptyMap(),
            emptySet(),
            ::execGetBrightness,
            riskLevel = ToolRiskLevel.SAFE,
        )
        registerBuiltIn("set_brightness",
            "设置系统屏幕亮度,值范围 0-255。需要 WRITE_SETTINGS 权限。",
            mapOf("value" to "必填,亮度值 0-255"),
            setOf("value"),
            ::execSetBrightness,
            riskLevel = ToolRiskLevel.HIGH,
            parameterTypes = mapOf("value" to "integer"),
        )
        registerBuiltIn("get_volume",
            "获取指定音频流的当前音量和最大音量(如 music/ring/alarm/notification/call)。",
            mapOf("stream" to "可选,音频流类型,默认 music"),
            emptySet(),
            ::execGetVolume,
            riskLevel = ToolRiskLevel.SAFE,
        )
        registerBuiltIn("set_volume",
            "设置指定音频流的音量。可传绝对值或百分比(percent=true)。",
            mapOf(
                "stream" to "可选,音频流类型,默认 music",
                "value" to "必填,目标音量值或百分比(0-100)",
                "percent" to "可选,true/false 是否按百分比设置,默认 false",
            ),
            setOf("value"),
            ::execSetVolume,
            riskLevel = ToolRiskLevel.HIGH,
            parameterTypes = mapOf("value" to "integer", "percent" to "boolean"),
        )
        registerBuiltIn("toggle_flashlight",
            "开关设备手电筒。action: on/off/status。",
            mapOf("action" to "必填,on/off/status"),
            setOf("action"),
            ::execToggleFlashlight,
            riskLevel = ToolRiskLevel.HIGH,
        )
        registerBuiltIn("vibrate",
            "控制设备振动指定毫秒数。",
            mapOf("duration_ms" to "可选,振动时长(毫秒),默认 300,最大 3000"),
            emptySet(),
            ::execVibrate,
            riskLevel = ToolRiskLevel.NORMAL,
            parameterTypes = mapOf("duration_ms" to "integer"),
        )

        // 应用与连接工具(5 个):前台应用/WiFi/蓝牙/拨号/地图
        registerBuiltIn("get_foreground_app",
            "获取当前或最近的前台应用包名(需要 PACKAGE_USAGE_STATS 权限)。",
            emptyMap(),
            emptySet(),
            ::execGetForegroundApp,
            riskLevel = ToolRiskLevel.NORMAL,
        )
        registerBuiltIn("get_wifi_info",
            "获取当前连接的 WiFi 信息:SSID、BSSID、信号强度、IP(需要位置权限)。",
            emptyMap(),
            emptySet(),
            ::execGetWifiInfo,
            riskLevel = ToolRiskLevel.NORMAL,
        )
        registerBuiltIn("get_bluetooth_devices",
            "获取已配对的蓝牙设备列表和蓝牙开关状态。",
            emptyMap(),
            emptySet(),
            ::execGetBluetoothDevices,
            riskLevel = ToolRiskLevel.NORMAL,
        )
        registerBuiltIn("make_phone_call",
            "打开系统拨号界面并预填手机号(不会直接拨出)。",
            mapOf("phone" to "必填,目标手机号"),
            setOf("phone"),
            ::execMakePhoneCall,
            riskLevel = ToolRiskLevel.NORMAL,
        )
        registerBuiltIn("open_maps",
            "打开地图应用搜索地点或导航到指定经纬度。",
            mapOf(
                "query" to "可选,搜索关键词,如 故宫博物院",
                "lat" to "可选,纬度",
                "lng" to "可选,经度",
            ),
            emptySet(),
            ::execOpenMaps,
            riskLevel = ToolRiskLevel.NORMAL,
        )

        // 文本/编码工具(5 个):URL/Base64/哈希/UUID/随机数
        registerBuiltIn("url_encode",
            "对文本进行 URL 编码。",
            mapOf("text" to "必填,要编码的文本"),
            setOf("text"),
            ::execUrlEncode,
            riskLevel = ToolRiskLevel.SAFE,
        )
        registerBuiltIn("url_decode",
            "对 URL 编码文本进行解码。",
            mapOf("text" to "必填,要解码的文本"),
            setOf("text"),
            ::execUrlDecode,
            riskLevel = ToolRiskLevel.SAFE,
        )
        registerBuiltIn("base64_encode",
            "对文本进行 Base64 编码。",
            mapOf("text" to "必填,要编码的文本"),
            setOf("text"),
            ::execBase64Encode,
            riskLevel = ToolRiskLevel.SAFE,
        )
        registerBuiltIn("base64_decode",
            "对 Base64 文本进行解码。",
            mapOf("text" to "必填,要解码的 Base64 文本"),
            setOf("text"),
            ::execBase64Decode,
            riskLevel = ToolRiskLevel.SAFE,
        )
        registerBuiltIn("hash_text",
            "计算文本哈希值。支持 MD5、SHA-1、SHA-256,默认 SHA-256。",
            mapOf(
                "text" to "必填,要哈希的文本",
                "algorithm" to "可选,MD5/SHA-1/SHA-256,默认 SHA-256",
            ),
            setOf("text"),
            ::execHashText,
            riskLevel = ToolRiskLevel.SAFE,
        )
        registerBuiltIn("generate_uuid",
            "生成一个随机的 UUID(通用唯一识别码)。",
            emptyMap(),
            emptySet(),
            ::execGenerateUuid,
            riskLevel = ToolRiskLevel.SAFE,
        )
        registerBuiltIn("random_number",
            "生成指定范围内的随机整数(包含边界)。",
            mapOf(
                "min" to "可选,最小值,默认 0",
                "max" to "可选,最大值,默认 100",
            ),
            emptySet(),
            ::execRandomNumber,
            riskLevel = ToolRiskLevel.SAFE,
            parameterTypes = mapOf("min" to "integer", "max" to "integer"),
        )

        // v1.136: 定时提醒工具(3 个)
        registerBuiltIn("schedule_reminder",
            "创建一个本地定时提醒,到指定时间会弹出通知。时间支持 ISO 8601 或 yyyy-MM-dd HH:mm。",
            mapOf(
                "title" to "必填,提醒标题",
                "message" to "必填,提醒正文",
                "time" to "必填,触发时间,如 2026-07-21 15:30 或 2026-07-21T15:30:00",
            ),
            setOf("title", "message", "time"),
            ::execScheduleReminder,
            riskLevel = ToolRiskLevel.NORMAL,
        )
        registerBuiltIn("cancel_reminder",
            "取消一个已创建的定时提醒。",
            mapOf("id" to "必填,提醒 id(由 schedule_reminder 返回)"),
            setOf("id"),
            ::execCancelReminder,
            riskLevel = ToolRiskLevel.NORMAL,
        )
        registerBuiltIn("list_reminders",
            "列出当前所有未触发的定时提醒。",
            mapOf("limit" to "可选,最多返回数量,默认 20"),
            emptySet(),
            ::execListReminders,
            riskLevel = ToolRiskLevel.SAFE,
            parameterTypes = mapOf("limit" to "integer"),
        )

        // v1.136: 资源库工具(5 个)
        registerBuiltIn("resource_add",
            "向资源库添加一条资源(笔记/提示词/参考内容等)。",
            mapOf(
                "title" to "必填,资源标题",
                "content" to "必填,资源正文",
                "tags" to "可选,标签,多个用逗号分隔",
            ),
            setOf("title", "content"),
            ::execResourceAdd,
            riskLevel = ToolRiskLevel.NORMAL,
        )
        registerBuiltIn("resource_list",
            "列出资源库中的资源,可按关键字过滤。",
            mapOf(
                "keyword" to "可选,标题/正文/标签过滤关键字",
                "limit" to "可选,最多返回数量,默认 20",
            ),
            emptySet(),
            ::execResourceList,
            riskLevel = ToolRiskLevel.SAFE,
            parameterTypes = mapOf("limit" to "integer"),
        )
        registerBuiltIn("resource_search",
            "搜索资源库(与 resource_list 关键字过滤行为一致)。",
            mapOf(
                "keyword" to "可选,搜索关键字",
                "limit" to "可选,最多返回数量,默认 20",
            ),
            emptySet(),
            ::execResourceSearch,
            riskLevel = ToolRiskLevel.SAFE,
            parameterTypes = mapOf("limit" to "integer"),
        )
        registerBuiltIn("resource_get",
            "根据 id 获取资源库中的单条资源。",
            mapOf("id" to "必填,资源 id"),
            setOf("id"),
            ::execResourceGet,
            riskLevel = ToolRiskLevel.SAFE,
        )
        registerBuiltIn("resource_delete",
            "根据 id 删除资源库中的资源。",
            mapOf("id" to "必填,资源 id"),
            setOf("id"),
            ::execResourceDelete,
            riskLevel = ToolRiskLevel.HIGH,
        )

        // v1.136: 快速记录工具(6 个)
        registerBuiltIn("quick_note_add",
            "添加一条快速记录(轻量笔记)。模型可在对话中帮用户记下待办/灵感/参考内容。",
            mapOf(
                "title" to "必填,记录标题",
                "content" to "可选,记录正文",
                "tags" to "可选,标签,多个用逗号分隔",
            ),
            setOf("title"),
            ::execQuickNoteAdd,
            riskLevel = ToolRiskLevel.NORMAL,
        )
        registerBuiltIn("quick_note_list",
            "列出快速记录,支持按关键字/标签过滤。",
            mapOf(
                "keyword" to "可选,标题/正文/标签过滤关键字",
                "tag" to "可选,按标签精确过滤",
                "limit" to "可选,最多返回数量,默认 20",
            ),
            emptySet(),
            ::execQuickNoteList,
            riskLevel = ToolRiskLevel.SAFE,
            parameterTypes = mapOf("limit" to "integer"),
        )
        registerBuiltIn("quick_note_search",
            "搜索快速记录(与 quick_note_list 关键字过滤一致)。",
            mapOf(
                "keyword" to "可选,搜索关键字",
                "limit" to "可选,最多返回数量,默认 20",
            ),
            emptySet(),
            ::execQuickNoteSearch,
            riskLevel = ToolRiskLevel.SAFE,
            parameterTypes = mapOf("limit" to "integer"),
        )
        registerBuiltIn("quick_note_get",
            "根据 id 获取单条快速记录。",
            mapOf("id" to "必填,记录 id"),
            setOf("id"),
            ::execQuickNoteGet,
            riskLevel = ToolRiskLevel.SAFE,
        )
        registerBuiltIn("quick_note_update",
            "更新指定快速记录。",
            mapOf(
                "id" to "必填,记录 id",
                "title" to "可选,新标题",
                "content" to "可选,新正文",
                "tags" to "可选,新标签,多个用逗号分隔",
            ),
            setOf("id"),
            ::execQuickNoteUpdate,
            riskLevel = ToolRiskLevel.NORMAL,
        )
        registerBuiltIn("quick_note_delete",
            "删除指定快速记录(移入回收站,可在回收站恢复)。",
            mapOf("id" to "必填,记录 id"),
            setOf("id"),
            ::execQuickNoteDelete,
            riskLevel = ToolRiskLevel.NORMAL,
        )
        registerBuiltIn("quick_note_pin",
            "置顶/取消置顶某条快速记录。",
            mapOf(
                "id" to "必填,记录 id",
                "pinned" to "必填,true/false",
            ),
            setOf("id", "pinned"),
            ::execQuickNotePin,
            riskLevel = ToolRiskLevel.NORMAL,
            parameterTypes = mapOf("pinned" to "boolean"),
        )

        // v1.0.17: 定时任务工具(6 个,助手可创建/管理定时任务)
        registerBuiltIn("scheduled_task_create",
            "创建定时任务。助手可帮用户设定定时执行的自动化任务,如每日提醒、定时总结等。",
            mapOf(
                "name" to "必填,任务名称",
                "prompt" to "必填,任务执行的 prompt 内容(如:总结今天的待办事项)",
                "interval" to "可选,触发方式:once/hourly/daily/weekly/cron,默认 daily",
                "cron_expr" to "可选,Cron 表达式(5字段: 分 时 日 月 周),仅 interval=cron 时需要",
                "assistant_id" to "可选,关联的助手 ID,默认 default",
                "action_type" to "可选,动作类型:ai_prompt/create_quick_note/call_tool/notify,默认 ai_prompt",
                "condition_type" to "可选,条件类型:always/network_available/time_range/contains/quick_note_exists,默认 always",
            ),
            setOf("name", "prompt"),
            ::execScheduledTaskCreate,
            riskLevel = ToolRiskLevel.NORMAL,
        )
        registerBuiltIn("scheduled_task_list",
            "列出定时任务。可按启用状态过滤,返回任务名称、间隔、下次执行时间和状态。",
            mapOf(
                "enabled" to "可选,true 仅列出启用的任务,false 仅列出禁用的任务,不传则全部",
                "limit" to "可选,最多返回数量,默认 20",
            ),
            emptySet(),
            ::execScheduledTaskList,
            riskLevel = ToolRiskLevel.SAFE,
            parameterTypes = mapOf("limit" to "integer"),
        )
        registerBuiltIn("scheduled_task_update",
            "更新指定定时任务的字段。仅传需要修改的字段即可。",
            mapOf(
                "id" to "必填,任务 id",
                "name" to "可选,新任务名称",
                "prompt" to "可选,新 prompt 内容",
                "interval" to "可选,新触发方式:once/hourly/daily/weekly/cron",
                "cron_expr" to "可选,新 Cron 表达式,仅 interval=cron 时需要",
                "enabled" to "可选,true/false 启用或禁用任务",
            ),
            setOf("id"),
            ::execScheduledTaskUpdate,
            riskLevel = ToolRiskLevel.NORMAL,
            parameterTypes = mapOf("enabled" to "boolean"),
        )
        registerBuiltIn("scheduled_task_delete",
            "删除指定定时任务。删除后不可恢复,请谨慎操作。",
            mapOf("id" to "必填,任务 id"),
            setOf("id"),
            ::execScheduledTaskDelete,
            riskLevel = ToolRiskLevel.HIGH,
        )
        registerBuiltIn("scheduled_task_execute",
            "立即触发一次指定定时任务的执行(在下一轮轮询中执行,最多 60 秒内)。",
            mapOf("id" to "必填,任务 id"),
            setOf("id"),
            ::execScheduledTaskExecute,
            riskLevel = ToolRiskLevel.NORMAL,
        )
        registerBuiltIn("scheduled_task_get_history",
            "查询指定定时任务的最近执行历史记录(含状态、摘要、错误信息)。",
            mapOf(
                "id" to "必填,任务 id",
                "limit" to "可选,最多返回数量,默认 10",
            ),
            setOf("id"),
            ::execScheduledTaskGetHistory,
            riskLevel = ToolRiskLevel.SAFE,
            parameterTypes = mapOf("limit" to "integer"),
        )

        // v1.0.17: 翻译工具 — 与 SkillExecutor 的 translate skill 对齐,统一走 ToolRegistry 体系
        registerBuiltIn("translate",
            "将文本翻译为指定语言。支持自动检测源语言和多种翻译风格。",
            mapOf(
                "text" to "必填,要翻译的文本",
                "target_language" to "必填,目标语言(如:中文/English/日本語/한국어/Français/Deutsch/Español/Русский/العربية/Português)",
                "source_language" to "可选,源语言(如:中文/English),不填则自动检测",
                "style" to "可选,翻译风格:通用/学术/商务/口语化/润色/简洁,默认通用",
            ),
            setOf("text", "target_language"),
            ::execTranslate,
            riskLevel = ToolRiskLevel.SAFE,
        )

        // v1.136: 网络/编码/TTS 工具(6 个)
        registerBuiltIn("ping_host",
            "Ping 指定的域名或 IP,测试网络可达性。",
            mapOf(
                "host" to "必填,目标域名或 IP,如 baidu.com",
                "timeout_ms" to "可选,超时毫秒数,默认 3000",
            ),
            setOf("host"),
            ::execPingHost,
            riskLevel = ToolRiskLevel.SAFE,
            parameterTypes = mapOf("timeout_ms" to "integer"),
        )
        registerBuiltIn("dns_lookup",
            "解析域名对应的所有 IP 地址(A 记录)。",
            mapOf("host" to "必填,目标域名,如 google.com"),
            setOf("host"),
            ::execDnsLookup,
            riskLevel = ToolRiskLevel.SAFE,
        )
        registerBuiltIn("get_public_ip",
            "获取当前设备的公网 IP 地址。",
            emptyMap(),
            emptySet(),
            ::execGetPublicIp,
            riskLevel = ToolRiskLevel.SAFE,
        )
        registerBuiltIn("json_pretty",
            "将 JSON 字符串格式化为易读形式(美化/压缩)。",
            mapOf(
                "json" to "必填,要格式化的 JSON 字符串",
                "indent" to "可选,true/false 是否展开缩进,默认 true",
            ),
            setOf("json"),
            ::execJsonPretty,
            riskLevel = ToolRiskLevel.SAFE,
            parameterTypes = mapOf("indent" to "boolean"),
        )
        registerBuiltIn("generate_password",
            "生成随机密码。可指定长度和是否包含大小写字母、数字、符号。",
            mapOf(
                "length" to "可选,密码长度,默认 16,范围 4-64",
                "uppercase" to "可选,true/false 包含大写字母,默认 true",
                "lowercase" to "可选,true/false 包含小写字母,默认 true",
                "digits" to "可选,true/false 包含数字,默认 true",
                "symbols" to "可选,true/false 包含特殊符号,默认 true",
            ),
            emptySet(),
            ::execGeneratePassword,
            riskLevel = ToolRiskLevel.SAFE,
            parameterTypes = mapOf("length" to "integer"),
        )
        registerBuiltIn("speak_text",
            "使用系统 TTS 朗读指定文本。",
            mapOf(
                "text" to "必填,要朗读的文本",
                "language" to "可选,语言代码,如 zh-CN/en-US,默认跟随系统",
                "rate" to "可选,语速倍率,如 0.8/1.0/1.5,默认 1.0",
            ),
            setOf("text"),
            ::execSpeakText,
            riskLevel = ToolRiskLevel.NORMAL,
            parameterTypes = mapOf("rate" to "number"),
        )

        // JS 沙盒:让 AI 能执行 JavaScript 代码(数学计算/数据处理/简单算法)
        // 底层用 WebView 的 V8 引擎,不新增大型依赖(参考 rikkahub QuickJS 思路)
        register(CodeExecutionTool.toolDef()) { args ->
            CodeExecutionTool.executeFromArgs(args, context)
        }

        // P2-6: 浏览器自动化工具集(navigate/click/type/extract/scroll_bottom/get_html)
        // 内部创建独立的 BrowserManager 实例(headless WebView,与 Koin 注册的实例分离),
        // 保证 AI 工具调用与 UI 展示互不干扰(参考 BrowserManager.kt 设计说明)
        val browserManager = BrowserManager(context)
        BrowserAutomationTool.toolDefs().forEach { def ->
            register(def) { args ->
                BrowserAutomationTool.executeFromArgs(def.name, args, browserManager)
            }
        }
    }

    /**
     * Phase 8.8: 注册工具(动态扩展点,后续 MCP server 加载用)。
     * @param def 工具定义
     * @param fn 执行函数
     */
    fun register(def: ToolDef, fn: ToolFn) {
        tools[def.name] = fn
        outcomeTools.remove(def.name)
        toolDefs[def.name] = def
    }

    /**
     * v1.0.53: 注册结构化结果工具(返回 [ToolOutcome])。
     * 优先于旧 String 通道;同名的 String 注册会覆盖回旧通道。
     */
    fun registerOutcome(def: ToolDef, fn: ToolOutcomeFn) {
        outcomeTools[def.name] = fn
        tools.remove(def.name)
        toolDefs[def.name] = def
    }

    /** 注销工具。 */
    fun unregister(name: String) {
        tools.remove(name)
        outcomeTools.remove(name)
        toolDefs.remove(name)
    }

    private fun registerBuiltIn(
        name: String,
        description: String,
        parameters: Map<String, String>,
        required: Set<String>,
        fn: ToolFn,
        riskLevel: ToolRiskLevel = ToolRiskLevel.SAFE,
        parameterTypes: Map<String, String> = emptyMap(),
    ) {
        register(ToolDef(name, description, parameters, required, "built-in", parameterTypes, riskLevel), fn)
    }

    /**
     * v1.0.53: 注册结构化结果内置工具(返回 [ToolOutcome])。
     * 覆盖同名 String 通道。
     */
    private fun registerBuiltInOutcome(
        name: String,
        description: String,
        parameters: Map<String, String>,
        required: Set<String>,
        fn: ToolOutcomeFn,
        riskLevel: ToolRiskLevel = ToolRiskLevel.SAFE,
        parameterTypes: Map<String, String> = emptyMap(),
    ) {
        registerOutcome(ToolDef(name, description, parameters, required, "built-in", parameterTypes, riskLevel), fn)
    }

    // v1.0.53: 工具分类注册表启动断言 — 每个内置工具必须有分类
    // 只检查 category="built-in"(MCP/插件工具豁免);debug 构建缺失时抛异常,release 仅记日志
    init {
        val builtInNames = toolDefs.values.filter { it.category == "built-in" }.map { it.name }.toSet()
        val uncovered = ToolCategories.assertCoverage(builtInNames)
        if (uncovered.isNotEmpty()) {
            val msg = "内置工具分类注册表缺失: ${uncovered.joinToString(", ")} (见 ToolCategories.kt)"
            if (io.zer0.muse.BuildConfig.DEBUG) {
                throw IllegalStateException(msg)
            } else {
                Logger.w("ToolRegistry", msg)
            }
        }
    }

    /** 列出所有可用工具(对标 MCP tools/list)。 */
    fun listTools(): List<ToolDef> = toolDefs.values.toList()

    /**
     * 获取指定工具的风险等级,未注册时返回 null。
     *
     * 供 [ToolPermissionResolver] 结合会话权限模式判断是否需要审批。
     */
    fun getToolRiskLevel(name: String): ToolRiskLevel? = toolDefs[name]?.riskLevel

    /**
     * Phase 8.8: 按工具 id 列表过滤(为 Assistant 绑定工具子集用)。
     * @param enabledToolIds 启用的工具 id 列表;null 或空列表表示全部启用
     */
    fun listTools(enabledToolIds: List<String>? = null): List<ToolDef> {
        val all = toolDefs.values.toList()
        if (enabledToolIds.isNullOrEmpty()) return all
        return all.filter { it.name in enabledToolIds }
    }

    /**
     * Phase 7: 生成 OpenAI 兼容的 ToolDefinition 列表(供 ChatService.streamChat(tools=...) 使用)。
     * Phase 8.8: 支持 [enabledToolIds] 按 Assistant 过滤。
     * 把 [ToolDef.parameters](参数名 → 描述) 转换为 JSON Schema 字符串。
     *
     * @param enabledToolIds 启用的工具 id 列表;null 或空列表表示全部启用
     */
    fun listToolsAsToolDefinitions(enabledToolIds: List<String>? = null): List<ToolDefinition> =
        listTools(enabledToolIds).map { def ->
            val schema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    def.parameters.forEach { (name, desc) ->
                        put(name, buildJsonObject {
                            put("type", def.parameterTypes[name] ?: "string")
                            put("description", desc)
                        })
                    }
                })
                if (def.required.isNotEmpty()) {
                    put("required", kotlinx.serialization.json.JsonArray(
                        def.required.map { JsonPrimitive(it) }
                    ))
                }
            }
            ToolDefinition(
                name = def.name,
                description = def.description,
                parametersJsonSchema = AppJson.encodeToString(JsonObject.serializer(), schema),
            )
        // v1.0.4 修复 HTTP 400 "Tool names must be unique":
        // 防御性按 name 去重,即使 ToolRegistry 内部因多 Registrar 注册同名工具也能拦截。
        }.distinctBy { it.name }

    /**
     * 执行工具(对标 MCP tools/call)。
     *
     * v1.134 P0-3: 改为 suspend 函数,与 [ToolFn] 类型对齐。
     * 调用方无须再手动 withContext(Dispatchers.IO) 包裹(但保留也无害)。
     *
     * @param name 工具名
     * @param args 参数 map
     * @return 执行结果字符串;工具不存在或参数错误返回错误信息
     */
    suspend fun execute(
        name: String,
        args: Map<String, String>,
        cancellationToken: () -> Boolean = { false },
    ): ToolOutcome {
        // v1.0.53: 内容级安全规则(执行前硬边界,不受审批模式影响)
        val content = args.values.joinToString("\n")
        ContentSafetyRules.check(name, content)?.let { rule ->
            Logger.w("ToolRegistry", "内容安全规则命中: ${rule.ruleId} (tool=$name)")
            return ToolOutcome.error("${rule.reason}(${rule.ruleId})")
        }
        // v1.0.53: 取消令牌(停止生成时置 true,长工具尽快返回)
        if (cancellationToken()) {
            return ToolOutcome.error("工具调用已取消")
        }
        // v1.0.53: 优先结构化通道
        outcomeTools[name]?.let { fn ->
            return resultOf { fn(args) }
                .onError { msg, _ -> Logger.w("ToolRegistry", "工具 $name 执行异常: $msg") }
                .getOrNull() ?: ToolOutcome.error(context.getString(R.string.tool_exec_exception))
        }
        val fn = tools[name]
            ?: return ToolOutcome.error(context.getString(R.string.tool_not_found, name, tools.keys.joinToString(", ")))
        // M-TR1: 改用 resultOf{}(正确重抛 CancellationException)
        return resultOf { fn(args) }
            .onError { msg, _ -> Logger.w("ToolRegistry", "工具 $name 执行异常: $msg") }
            .getOrNull()?.let { ToolOutcome.ok(it) }
            ?: ToolOutcome.error(context.getString(R.string.tool_exec_exception))
    }

    /**
     * Phase 7: 从 LLM 返回的 arguments JSON 字符串执行工具。
     * @param name 工具名
     * @param argumentsJson LLM 返回的参数 JSON 字符串(如 {"expression":"1+2*3"})
     * @return 执行结果字符串
     */
    suspend fun executeFromJson(name: String, argumentsJson: String): String {
        // M-TR1: 改用 resultOf{}(正确重抛 CancellationException)
        val args = resultOf {
            val obj = AppJson.decodeFromString(JsonObject.serializer(), argumentsJson)
            obj.entries.associate { (k, v) -> k to v.toString().trim('"') }
        }.onError { msg, _ ->
            Logger.w("ToolRegistry", "executeFromJson 参数解析失败: $msg(原始: $argumentsJson)")
        }.getOrNull() ?: return context.getString(R.string.tool_param_parse_failed, argumentsJson)
        // v1.0.53: execute 返回 ToolOutcome,取 content 保持 String 语义
        return execute(name, args).content
    }

    // ── 内置工具实现 ──────────────────────────────────────────────────────────

    private suspend fun execGetCurrentTime(args: Map<String, String>): String {
        val tzId = args["timezone"]?.takeIf { it.isNotBlank() } ?: "Asia/Shanghai"
        // L-TR11: 用 TimeZone.getAvailableIDs().contains(id) 显式校验时区 id 是否有效,
        // 避免 TimeZone.getTimeZone 对未知 id 静默回退 GMT 的死代码兜底
        val tz = if (TimeZone.getAvailableIDs().contains(tzId)) {
            TimeZone.getTimeZone(tzId)
        } else {
            return context.getString(R.string.tool_unknown_timezone, tzId)
        }
        val pattern = args["format"]?.takeIf { it.isNotBlank() } ?: "yyyy-MM-dd HH:mm:ss z"
        // M-TR1: 改用 resultOf{}(正确重抛 CancellationException)
        val fmt = resultOf { SimpleDateFormat(pattern, Locale.getDefault()) }
            .onError { msg, _ -> Logger.w("ToolRegistry", "时间格式无效: $msg(pattern=$pattern)") }
            .getOrNull() ?: SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault())
        fmt.timeZone = tz
        // v1.47 修复:用查询的目标时区 tz 判断 DST,而非设备默认时区 TimeZone.getDefault(),
        // 否则查纽约时区的 DST 标签会反映设备时区(如 Asia/Shanghai 无 DST),结果错误。
        val dstLabel = if (tz.inDaylightTime(Date())) context.getString(R.string.tool_dst) else context.getString(R.string.tool_non_dst)
        return context.getString(R.string.tool_current_time, tzId, fmt.format(Date()), dstLabel)
    }

    /**
     * 简易计算器:支持 + - * / ( ) 和空格。
     * 不引入第三方表达式引擎,用递归下降解析。
     * 不用 javax.script(Nashorn 在 JDK 15+ 移除,Android 无)。
     */
    private suspend fun execCalculator(args: Map<String, String>): String {
        val expr = args["expression"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_missing_param_expression)
        // M-TR5: 检测除零导致的 Infinity/NaN,返回明确错误而非 Infinity
        val result = try {
            Calculator.eval(expr)
        } catch (e: ArithmeticException) {
            return context.getString(R.string.tool_calc_error, e.message ?: "")
        } catch (e: IllegalArgumentException) {
            return context.getString(R.string.tool_expr_invalid, e.message ?: "")
        }
        if (result.isInfinite() || result.isNaN()) {
            return context.getString(R.string.tool_calc_error_divzero, result.toString())
        }
        return "$expr = $result"
    }

    private suspend fun execEcho(args: Map<String, String>): String {
        return args["text"] ?: ""
    }

    /** Phase 8.8: 读取系统剪贴板文本。 */
    private suspend fun execClipboardRead(_args: Map<String, String>): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        val clip = cm.primaryClip ?: return context.getString(R.string.tool_clipboard_empty)
        if (clip.itemCount == 0) return context.getString(R.string.tool_clipboard_empty)
        val text = clip.getItemAt(0).coerceToText(context).toString()
        return if (text.isBlank()) context.getString(R.string.tool_clipboard_empty) else context.getString(R.string.tool_clipboard_content, text)
    }

    /**
     * v1.0.53: clipboard_read 结构化版 — 附加 hasText/length details。
     * 剪贴板读取是轻量操作,直接读一次取 details。
     */
    private suspend fun execClipboardReadOutcome(_args: Map<String, String>): ToolOutcome {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        val clip = cm.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        val content = if (text.isBlank()) context.getString(R.string.tool_clipboard_empty)
            else context.getString(R.string.tool_clipboard_content, text)
        return ToolOutcome.ok(
            content,
            details = mapOf(
                "hasText" to text.isNotBlank(),
                "length" to text.length,
            ),
        )
    }

    /** Phase 8.8: 写入系统剪贴板。 */
    private suspend fun execClipboardWrite(args: Map<String, String>): String {
        val text = args["text"] ?: return context.getString(R.string.tool_missing_param_text)
        // v1.47: label 加 100 字符上限,避免超长 label 导致部分系统剪贴板实现异常
        val label = (args["label"]?.takeIf { it.isNotBlank() } ?: "muse_tool").take(100)
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        return context.getString(R.string.tool_clipboard_written, text.length)
    }

    /**
     * Phase 8.8: 获取今日各应用屏幕使用时间(前 10 名)。
     * 需要 PACKAGE_USAGE_STATS 权限(特殊权限,需用户在设置中授予)。
     */
    private suspend fun execScreenTime(_args: Map<String, String>): String {
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

    /**
     * Phase 8.8: 获取今日日历事件列表。
     * 支持 date 参数指定日期(YYYY-MM-DD)、days 参数查询未来几天。
     * 需要 READ_CALENDAR 权限(运行时权限)。
     */
    private suspend fun execCalendarToday(args: Map<String, String>): String {
        val cr = context.contentResolver
        val cal = java.util.Calendar.getInstance()
        // 解析 date 参数(YYYY-MM-DD),默认今天
        val dateStr = args["date"]?.takeIf { it.isNotBlank() }
        if (dateStr != null) {
            val parts = dateStr.split("-")
            if (parts.size != 3) return context.getString(R.string.tool_date_format_error, dateStr)
            val y = parts[0].toIntOrNull() ?: return context.getString(R.string.tool_date_year_invalid, parts[0])
            val m = parts[1].toIntOrNull() ?: return context.getString(R.string.tool_date_month_invalid, parts[1])
            val d = parts[2].toIntOrNull() ?: return context.getString(R.string.tool_date_day_invalid, parts[2])
            // M-TR6: 用 java.time.LocalDate.of 严格校验日期合法性(如 2月30日会抛异常)
            try {
                java.time.LocalDate.of(y, m, d)
            } catch (e: java.time.DateTimeException) {
                return context.getString(R.string.tool_date_illegal, e.message ?: "", dateStr)
            }
            cal.set(y, m - 1, d)
        }
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val startDay = cal.timeInMillis
        // days 参数:查询未来几天,默认 0=只当天
        val days = args["days"]?.toIntOrNull() ?: 0
        if (days < 0) return context.getString(R.string.tool_days_negative)
        cal.add(java.util.Calendar.DAY_OF_MONTH, days + 1)
        val endDay = cal.timeInMillis
        val projection = arrayOf(
            android.provider.CalendarContract.Events.TITLE,
            android.provider.CalendarContract.Events.DTSTART,
            android.provider.CalendarContract.Events.DTEND,
            android.provider.CalendarContract.Events.EVENT_LOCATION,
        )
        // v1.52: 修正 selection — 用"时段交集"替代"DTSTART 落在当天",
        // 捕获跨天事件(昨天开始今天还在进行)和全天事件(DTEND 可能 IS NULL)。
        // 交集条件: DTSTART < endDay AND (DTEND > startDay OR DTEND IS NULL)
        val selection = "${android.provider.CalendarContract.Events.DTSTART} < ? AND " +
            "(${android.provider.CalendarContract.Events.DTEND} > ? OR " +
            "${android.provider.CalendarContract.Events.DTEND} IS NULL)"
        val selectionArgs = arrayOf(endDay.toString(), startDay.toString())
        val sortOrder = "${android.provider.CalendarContract.Events.DTSTART} ASC"
        val dateLabel = FMT_DATE.get()?.format(Date(startDay)) ?: startDay.toString()
        val sb = StringBuilder(context.getString(R.string.tool_calendar_header, dateLabel,
            if (days > 0) context.getString(R.string.tool_calendar_days_suffix, days + 1) else ""))
        var count = 0
        try {
            cr.query(
                android.provider.CalendarContract.Events.CONTENT_URI,
                projection, selection, selectionArgs, sortOrder,
            )?.use { cursor ->
                val titleIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Events.TITLE)
                val startIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Events.DTSTART)
                val endIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Events.DTEND)
                val locIdx = cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Events.EVENT_LOCATION)
                val fmt = FMT_DATETIME_MIN.get() ?: SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                while (cursor.moveToNext()) {
                    val title = cursor.getString(titleIdx) ?: context.getString(R.string.tool_no_title)
                    val startMs = cursor.getLong(startIdx)
                    val endMs = if (cursor.isNull(endIdx)) null else cursor.getLong(endIdx)
                    val loc = cursor.getString(locIdx)
                    val timeRange = if (endMs != null && endMs > startMs) {
                        "${fmt.format(Date(startMs))} ~ ${fmt.format(Date(endMs))}"
                    } else {
                        fmt.format(Date(startMs))
                    }
                    sb.appendLine("  - $timeRange $title${if (loc.isNullOrBlank()) "" else " @$loc"}")
                    count++
                }
            }
        } catch (e: SecurityException) {
            return context.getString(R.string.tool_calendar_no_permission)
        }
        return if (count == 0) context.getString(R.string.tool_no_calendar_events) else sb.toString().trimEnd()
    }

    /**
     * 向系统日历写入一个新事件。
     * 需要 WRITE_CALENDAR 权限,并依赖 READ_CALENDAR 查询可用日历账户。
     */
    private suspend fun execAddCalendarEvent(args: Map<String, String>): String {
        val cr = context.contentResolver
        val title = args["title"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_missing_param_title_event)

        // 解析开始时间
        val startTimeStr = args["start_time"] ?: return context.getString(R.string.tool_missing_param_start_time)
        val startMs = parseDateTime(startTimeStr)
            ?: return context.getString(R.string.tool_start_time_format_error)

        val allDay = args["all_day"].equals("true", ignoreCase = true)
        val endMs = if (allDay) {
            parseDateTime(args["end_time"] ?: "") ?: (startMs + 24 * 60 * 60 * 1000 - 1)
        } else {
            parseDateTime(args["end_time"] ?: "") ?: (startMs + 60 * 60 * 1000)
        }

        val values = android.content.ContentValues().apply {
            put(android.provider.CalendarContract.Events.TITLE, title)
            put(android.provider.CalendarContract.Events.DTSTART, startMs)
            put(android.provider.CalendarContract.Events.DTEND, endMs)
            put(android.provider.CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            args["description"]?.takeIf { it.isNotBlank() }?.let {
                put(android.provider.CalendarContract.Events.DESCRIPTION, it)
            }
            args["location"]?.takeIf { it.isNotBlank() }?.let {
                put(android.provider.CalendarContract.Events.EVENT_LOCATION, it)
            }
            // 默认使用第一个可用日历账户
            val calendarId = getDefaultCalendarId(cr)
                ?: return context.getString(R.string.tool_no_calendar_account)
            put(android.provider.CalendarContract.Events.CALENDAR_ID, calendarId)
            put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }

        // M-TR1: 改用 resultOf{}(正确重抛 CancellationException)
        return resultOf {
            val uri = cr.insert(android.provider.CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) {
                context.getString(R.string.tool_calendar_added, title)
            } else {
                context.getString(R.string.tool_calendar_add_failed_perm)
            }
        }.onError { msg, _ -> Logger.w("ToolRegistry", "添加日历事件失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_calendar_add_failed)
    }

    private fun parseDateTime(input: String): Long? {
        if (input.isBlank()) return null
        // v1.131: 复用 ThreadLocal 缓存的格式器列表,避免每次解析都新建 4 个 SimpleDateFormat。
        val formats = PARSE_FORMATS_TL.get() ?: return null
        for (fmt in formats) {
            // M-TR1: 改用 resultOf{}(正确重抛 CancellationException)
            val parsed = resultOf { fmt.parse(input) }.getOrNull()
            parsed?.time?.let { return it }
        }
        return null
    }

    private fun getDefaultCalendarId(cr: android.content.ContentResolver): Long? {
        // M-TR1: 改用 resultOf{}(正确重抛 CancellationException)
        return resultOf {
            cr.query(
                android.provider.CalendarContract.Calendars.CONTENT_URI,
                arrayOf(android.provider.CalendarContract.Calendars._ID),
                "${android.provider.CalendarContract.Calendars.VISIBLE} = ?",
                arrayOf("1"),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.CalendarContract.Calendars._ID))
                } else null
            }
        }.getOrNull()
    }

    // ── 手机端工具实现(7 个)──────────────────────────────────────────────

    /** 设置系统闹钟:通过 AlarmClock.ACTION_SET_ALARM 拉起系统时钟应用。无需运行时权限。 */
    private suspend fun execSetAlarm(args: Map<String, String>): String {
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
    private suspend fun execSetTimer(args: Map<String, String>): String {
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
    private suspend fun execOpenApp(args: Map<String, String>): String {
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
    private suspend fun execShareText(args: Map<String, String>): String {
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
    private suspend fun execGetLocation(args: Map<String, String>): String {
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
    private suspend fun execGetDeviceInfo(_args: Map<String, String>): String {
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
    private suspend fun execGetDeviceInfoOutcome(_args: Map<String, String>): ToolOutcome {
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
    private suspend fun execGetContactsCount(args: Map<String, String>): String {
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
    private suspend fun execGetContactsList(args: Map<String, String>): String {
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
    private suspend fun execSendSms(args: Map<String, String>): String {
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
    private suspend fun execAddContact(args: Map<String, String>): String {
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

    // ── 系统控制与邮件工具实现(5 个)──────────────────────────────────────

    /**
     * 打开系统设置页:支持通过 category 跳转到具体设置项(wifi/bluetooth/display 等)。
     * app_settings 分类会附带本应用包名,直达应用详情页。无需运行时权限。
     */
    private suspend fun execOpenSystemSetting(args: Map<String, String>): String {
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
     * 开关 WiFi:Android 10+ 无法直接 toggle(WifiManager.setWifiEnabled 已废弃)。
     * action=status 读取当前状态;action=on/off 跳 WiFi 设置页让用户手动操作。
     * 需 ACCESS_WIFI_STATE(读状态,普通权限,无需运行时申请)。
     */
    private suspend fun execToggleWifi(args: Map<String, String>): String {
        val action = args["action"] ?: return context.getString(R.string.tool_error_missing_action)
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
    private suspend fun execToggleBluetooth(args: Map<String, String>): String {
        val action = args["action"] ?: return context.getString(R.string.tool_error_missing_action)
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
    private suspend fun execSendEmail(args: Map<String, String>): String {
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
    private suspend fun execGetBatteryInfo(_args: Map<String, String>): String {
        val batteryManager = context.getSystemService(android.content.Context.BATTERY_SERVICE)
            as android.os.BatteryManager
        val level = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = batteryManager.isCharging
        return context.getString(R.string.tool_battery_info, level, if (charging) context.getString(R.string.tool_battery_charging) else context.getString(R.string.tool_battery_not_charging))
    }

    /** 查询天气:通过 wttr.in 免费 API 获取天气信息。 */
    private suspend fun execGetWeather(args: Map<String, String>): String {
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
    private suspend fun execGetRecentNotifications(args: Map<String, String>): String {
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
    private suspend fun execOpenUrl(args: Map<String, String>): String {
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
    private suspend fun execListInstalledApps(args: Map<String, String>): String {
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
    private suspend fun execGetNetworkInfo(_args: Map<String, String>): String {
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
    private suspend fun execGetStorageInfo(_args: Map<String, String>): String {
        return resultOf {
            val stat = android.os.StatFs(context.filesDir.path)
            val total = stat.totalBytes
            val free = stat.freeBytes
            context.getString(R.string.tool_storage_info, formatBytes(total), formatBytes(total - free), formatBytes(free))
        }.onError { msg, _ -> Logger.w("ToolRegistry", "获取存储信息失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_installed_apps_failed)
    }

    /** 获取内存(RAM)信息。 */
    private suspend fun execGetMemoryInfo(_args: Map<String, String>): String {
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
    private suspend fun execGetDisplayInfo(_args: Map<String, String>): String {
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
    private suspend fun execGetCpuInfo(_args: Map<String, String>): String {
        return resultOf {
            val processor = readProcCpuField("Hardware") ?: readProcCpuField("Processor") ?: "未知"
            val cores = Runtime.getRuntime().availableProcessors()
            context.getString(R.string.tool_cpu_info, processor, cores)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "获取 CPU 信息失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_installed_apps_failed)
    }

    /** 获取设备传感器列表。 */
    private suspend fun execGetSensorsList(args: Map<String, String>): String {
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
    private suspend fun execGetBrightness(_args: Map<String, String>): String {
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

    /** 设置系统屏幕亮度。 */
    private suspend fun execSetBrightness(args: Map<String, String>): String {
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
    private suspend fun execGetVolume(args: Map<String, String>): String {
        val (streamType, streamLabel) = resolveAudioStream(args["stream"])
        return resultOf {
            val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            context.getString(R.string.tool_volume_info, streamLabel, am.getStreamVolume(streamType), am.getStreamMaxVolume(streamType))
        }.onError { msg, _ -> Logger.w("ToolRegistry", "获取音量失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_installed_apps_failed)
    }

    /** 设置指定音频流音量。 */
    private suspend fun execSetVolume(args: Map<String, String>): String {
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

    /** 开关手电筒。 */
    private suspend fun execToggleFlashlight(args: Map<String, String>): String {
        val action = args["action"]?.lowercase() ?: return context.getString(R.string.tool_flashlight_invalid_action)
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
                    context.getString(R.string.tool_flashlight_on)
                }
                "off" -> {
                    cm.setTorchMode(cameraId, false)
                    context.getString(R.string.tool_flashlight_off)
                }
                "status" -> context.getString(R.string.tool_flashlight_status, context.getString(R.string.tool_yes))
                else -> context.getString(R.string.tool_flashlight_invalid_action)
            }
        }.onError { msg, _ -> Logger.w("ToolRegistry", "手电筒操作失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_flashlight_unavailable)
    }

    /** 控制设备振动。 */
    @Suppress("DEPRECATION")
    private suspend fun execVibrate(args: Map<String, String>): String {
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
    private suspend fun execGetForegroundApp(_args: Map<String, String>): String {
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
    private suspend fun execGetWifiInfo(_args: Map<String, String>): String {
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
    private suspend fun execGetBluetoothDevices(_args: Map<String, String>): String {
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
    private suspend fun execMakePhoneCall(args: Map<String, String>): String {
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
    private suspend fun execOpenMaps(args: Map<String, String>): String {
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
    private suspend fun execUrlEncode(args: Map<String, String>): String {
        val text = args["text"] ?: return context.getString(R.string.tool_url_missing)
        return resultOf {
            context.getString(R.string.tool_url_encoded, java.net.URLEncoder.encode(text, "UTF-8"))
        }.onError { msg, _ -> Logger.w("ToolRegistry", "URL 编码失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_url_missing)
    }

    /** URL 解码。 */
    private suspend fun execUrlDecode(args: Map<String, String>): String {
        val text = args["text"] ?: return context.getString(R.string.tool_url_missing)
        return resultOf {
            context.getString(R.string.tool_url_decoded, java.net.URLDecoder.decode(text, "UTF-8"))
        }.onError { msg, _ -> Logger.w("ToolRegistry", "URL 解码失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_url_missing)
    }

    /** Base64 编码。 */
    private suspend fun execBase64Encode(args: Map<String, String>): String {
        val text = args["text"] ?: return context.getString(R.string.tool_url_missing)
        return resultOf {
            context.getString(R.string.tool_base64_encoded, android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))
        }.onError { msg, _ -> Logger.w("ToolRegistry", "Base64 编码失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_url_missing)
    }

    /** Base64 解码。 */
    private suspend fun execBase64Decode(args: Map<String, String>): String {
        val text = args["text"] ?: return context.getString(R.string.tool_url_missing)
        return resultOf {
            val bytes = android.util.Base64.decode(text, android.util.Base64.DEFAULT)
            context.getString(R.string.tool_base64_decoded, String(bytes, Charsets.UTF_8))
        }.onError { msg, _ -> Logger.w("ToolRegistry", "Base64 解码失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_base64_failed)
    }

    /** 文本哈希。 */
    private suspend fun execHashText(args: Map<String, String>): String {
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
    private suspend fun execGenerateUuid(_args: Map<String, String>): String {
        return context.getString(R.string.tool_uuid_result, java.util.UUID.randomUUID().toString())
    }

    /** 生成指定范围随机整数。 */
    private suspend fun execRandomNumber(args: Map<String, String>): String {
        var min = args["min"]?.toIntOrNull() ?: 0
        var max = args["max"]?.toIntOrNull() ?: 100
        if (min > max) {
            min = max.also { max = min }
        }
        return context.getString(R.string.tool_random_number_result, kotlin.random.Random.nextInt(min, max + 1))
    }

    // ── 辅助函数 ─────────────────────────────────────────────────────────────

    /** 把字节数格式化为人类可读字符串。 */
    private fun formatBytes(bytes: Long): String {
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
    private fun readProcCpuField(key: String): String? {
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
    private fun resolveAudioStream(name: String?): Pair<Int, String> {
        return when (name?.lowercase()) {
            "ring", "ringtone" -> android.media.AudioManager.STREAM_RING to context.getString(R.string.tool_volume_stream_ring)
            "alarm" -> android.media.AudioManager.STREAM_ALARM to context.getString(R.string.tool_volume_stream_alarm)
            "notification" -> android.media.AudioManager.STREAM_NOTIFICATION to context.getString(R.string.tool_volume_stream_notification)
            "call", "voice_call" -> android.media.AudioManager.STREAM_VOICE_CALL to context.getString(R.string.tool_volume_stream_call)
            "system" -> android.media.AudioManager.STREAM_SYSTEM to context.getString(R.string.tool_volume_stream_system)
            else -> android.media.AudioManager.STREAM_MUSIC to context.getString(R.string.tool_volume_stream_music)
        }
    }

    // ── v1.136: 定时提醒工具 ─────────────────────────────────────────────────

    /** 创建定时提醒。 */
    private suspend fun execScheduleReminder(args: Map<String, String>): String {
        val title = args["title"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_reminder_missing_title)
        val message = args["message"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_reminder_missing_message)
        val timeStr = args["time"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_reminder_missing_time)
        val triggerAt = parseReminderTime(timeStr)
            ?: return context.getString(R.string.tool_reminder_invalid_time, timeStr)
        if (triggerAt <= System.currentTimeMillis()) {
            return context.getString(R.string.tool_reminder_past_time)
        }
        val id = reminderStore.add(title, message, triggerAt)
        val scheduled = scheduleAlarm(id, title, message, triggerAt)
        return if (scheduled) {
            context.getString(R.string.tool_reminder_scheduled, id, SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(triggerAt)))
        } else {
            reminderStore.remove(id)
            context.getString(R.string.tool_reminder_schedule_failed)
        }
    }

    /** 取消定时提醒。 */
    private suspend fun execCancelReminder(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_reminder_missing_id)
        cancelAlarm(id)
        return if (reminderStore.remove(id)) {
            context.getString(R.string.tool_reminder_cancelled, id)
        } else {
            context.getString(R.string.tool_reminder_not_found, id)
        }
    }

    /** 列出定时提醒。 */
    private suspend fun execListReminders(args: Map<String, String>): String {
        val limit = args["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: 20
        val list = reminderStore.list().sortedBy { it.triggerAtMillis }.take(limit)
        if (list.isEmpty()) return context.getString(R.string.tool_reminder_list_empty)
        val sb = StringBuilder(context.getString(R.string.tool_reminder_list_header, list.size))
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        list.forEach {
            sb.appendLine(context.getString(R.string.tool_reminder_list_item, it.id, sdf.format(Date(it.triggerAtMillis)), it.title))
        }
        return sb.toString().trimEnd()
    }

    /** 解析提醒时间。 */
    private fun parseReminderTime(input: String): Long? {
        val trimmed = input.trim()
        val patterns = listOf(
            "yyyy-MM-dd HH:mm" to SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()),
            "yyyy-MM-dd HH:mm:ss" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()),
            "yyyy/MM/dd HH:mm" to SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()),
        )
        patterns.forEach { (_, sdf) ->
            try { return sdf.parse(trimmed)?.time } catch (_: Exception) { /* ignore */ }
        }
        // 尝试 ISO 8601
        return try {
            java.time.Instant.parse(trimmed).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    /** 通过 AlarmManager 注册闹钟。 */
    private fun scheduleAlarm(id: String, title: String, message: String, triggerAtMillis: Long): Boolean {
        return resultOf {
            val am = context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = android.content.Intent(context, ReminderAlarmReceiver::class.java).apply {
                putExtra(ReminderAlarmReceiver.EXTRA_ID, id)
                putExtra(ReminderAlarmReceiver.EXTRA_TITLE, title)
                putExtra(ReminderAlarmReceiver.EXTRA_MESSAGE, message)
            }
            val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = android.app.PendingIntent.getBroadcast(context, id.hashCode(), intent, flags)
            // v1.0.14: Android 12+ 须先检查 canScheduleExactAlarms,否则 setExact* 抛 SecurityException。
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                Logger.w("ToolRegistry", "无 SCHEDULE_EXACT_ALARM 权限,无法设置精确提醒: id=$id")
                return@resultOf false
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } else {
                am.setExact(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
            true
        }.onError { msg, _ -> Logger.w("ToolRegistry", "scheduleAlarm failed: $msg") }
            .getOrNull() ?: false
    }

    /** 取消 AlarmManager 闹钟。 */
    private fun cancelAlarm(id: String) {
        resultOf {
            val am = context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = android.content.Intent(context, ReminderAlarmReceiver::class.java)
            val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = android.app.PendingIntent.getBroadcast(context, id.hashCode(), intent, flags)
            am.cancel(pi)
            pi.cancel()
        }.onError { msg, _ -> Logger.w("ToolRegistry", "cancelAlarm failed: $msg") }
    }

    // ── v1.136: 资源库工具 ───────────────────────────────────────────────────

    /** 向资源库添加资源。 */
    private suspend fun execResourceAdd(args: Map<String, String>): String {
        val title = args["title"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_resource_missing_title)
        val content = args["content"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_resource_missing_content)
        val tags = args["tags"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        val id = resourceLibrary.add(title, content, tags)
        return context.getString(R.string.tool_resource_added, id, title)
    }

    /** 列出资源库资源。 */
    private suspend fun execResourceList(args: Map<String, String>): String {
        val keyword = args["keyword"]
        val limit = args["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: 20
        return formatResourceList(resourceLibrary.list(keyword, limit))
    }

    /** 搜索资源库。 */
    private suspend fun execResourceSearch(args: Map<String, String>): String {
        val keyword = args["keyword"]
        val limit = args["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: 20
        return formatResourceList(resourceLibrary.list(keyword, limit))
    }

    /** 根据 id 获取资源。 */
    private suspend fun execResourceGet(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_resource_missing_id)
        val item = resourceLibrary.get(id)
            ?: return context.getString(R.string.tool_resource_not_found, id)
        return formatResourceItem(item)
    }

    /** 根据 id 删除资源。 */
    private suspend fun execResourceDelete(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_resource_missing_id)
        return if (resourceLibrary.remove(id)) {
            context.getString(R.string.tool_resource_deleted, id)
        } else {
            context.getString(R.string.tool_resource_not_found, id)
        }
    }

    /** 格式化资源列表。 */
    private fun formatResourceList(list: List<io.zer0.muse.tools.resource.ResourceItem>): String {
        if (list.isEmpty()) return context.getString(R.string.tool_resource_list_empty)
        val sb = StringBuilder(context.getString(R.string.tool_resource_list_header, list.size))
        list.forEach {
            sb.appendLine(context.getString(R.string.tool_resource_list_item, it.id, it.title, it.tags.joinToString(",")))
        }
        return sb.toString().trimEnd()
    }

    /** 格式化单条资源。 */
    private fun formatResourceItem(item: io.zer0.muse.tools.resource.ResourceItem): String {
        return context.getString(
            R.string.tool_resource_item_detail,
            item.id,
            item.title,
            item.tags.joinToString(","),
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.createdAtMillis)),
            item.content,
        )
    }

    // ── v1.136: 快速记录工具(v1.0.17 改用 Room 持久化) ────────────────────

    /** 添加快速记录。 */
    private suspend fun execQuickNoteAdd(args: Map<String, String>): String {
        val title = args["title"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_quick_note_missing_title)
        val content = args["content"] ?: ""
        val tags = args["tags"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        val id = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        MuseDb.get(context).quickNoteDao().upsert(
            QuickNoteEntity(
                id = id,
                title = title,
                content = content,
                tags = tags,
                pinned = false,
                deleted = false,
                deletedAt = 0,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return context.getString(R.string.tool_quick_note_added, id, title)
    }

    /** 列出快速记录。 */
    private suspend fun execQuickNoteList(args: Map<String, String>): String {
        val keyword = args["keyword"]
        val tag = args["tag"]
        val limit = args["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: 20
        // DAO search 已排除 deleted=1,keyword/tag 为 null 时不过滤
        val list = MuseDb.get(context).quickNoteDao().search(keyword, tag, limit)
        return formatQuickNoteList(list)
    }

    /** 搜索快速记录。 */
    private suspend fun execQuickNoteSearch(args: Map<String, String>): String {
        val keyword = args["keyword"]
        val limit = args["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: 20
        val list = MuseDb.get(context).quickNoteDao().search(keyword, null, limit)
        return formatQuickNoteList(list)
    }

    /** 获取单条快速记录。 */
    private suspend fun execQuickNoteGet(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_quick_note_missing_id)
        val note = MuseDb.get(context).quickNoteDao().getById(id)
            ?: return context.getString(R.string.tool_quick_note_not_found, id)
        return formatQuickNote(note)
    }

    /** 更新快速记录。 */
    private suspend fun execQuickNoteUpdate(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_quick_note_missing_id)
        val dao = MuseDb.get(context).quickNoteDao()
        val existing = dao.getById(id)
            ?: return context.getString(R.string.tool_quick_note_not_found, id)
        val title = args["title"]
        val content = args["content"]
        val tags = args["tags"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
        dao.upsert(
            existing.copy(
                title = title ?: existing.title,
                content = content ?: existing.content,
                tags = tags ?: existing.tags,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return context.getString(R.string.tool_quick_note_updated, id)
    }

    /** 删除快速记录(soft delete,移入回收站)。 */
    private suspend fun execQuickNoteDelete(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_quick_note_missing_id)
        val dao = MuseDb.get(context).quickNoteDao()
        // 先校验记录存在(含已删除),不存在则返回 not_found
        val existing = dao.getById(id)
            ?: return context.getString(R.string.tool_quick_note_not_found, id)
        // 已在回收站则直接返回成功(幂等),否则移入回收站
        if (!existing.deleted) {
            dao.moveToTrash(id)
        }
        return context.getString(R.string.tool_quick_note_deleted, id)
    }

    /** 置顶/取消置顶快速记录。 */
    private suspend fun execQuickNotePin(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_quick_note_missing_id)
        val dao = MuseDb.get(context).quickNoteDao()
        // 先校验记录存在,不存在则返回 not_found
        dao.getById(id)
            ?: return context.getString(R.string.tool_quick_note_not_found, id)
        val pinned = args["pinned"]?.equals("true", ignoreCase = true) ?: false
        dao.setPinned(id, pinned)
        return context.getString(R.string.tool_quick_note_pinned, id, if (pinned) context.getString(R.string.tool_yes) else context.getString(R.string.tool_no))
    }

    /** 格式化快速记录列表。 */
    private fun formatQuickNoteList(list: List<QuickNoteEntity>): String {
        if (list.isEmpty()) return context.getString(R.string.tool_quick_note_list_empty)
        val sb = StringBuilder(context.getString(R.string.tool_quick_note_list_header, list.size))
        list.forEach {
            sb.appendLine(context.getString(R.string.tool_quick_note_list_item, it.id, if (it.pinned) "[顶]" else "", it.title, it.tags.joinToString(",")))
        }
        return sb.toString().trimEnd()
    }

    /** 格式化单条快速记录。 */
    private fun formatQuickNote(note: QuickNoteEntity): String {
        return context.getString(
            R.string.tool_quick_note_item_detail,
            note.id,
            note.title,
            if (note.pinned) context.getString(R.string.tool_yes) else context.getString(R.string.tool_no),
            note.tags.joinToString(","),
            note.content,
        )
    }

    // ── v1.0.17: 定时任务工具 ──────────────────────────────────────────

    /**
     * 计算下次执行时间(简化版,参考 ScheduledTaskRunner.computeNextRun)。
     * - once: 立即(返回 now,由下一轮轮询执行)
     * - hourly/daily/weekly: now + 固定间隔
     * - cron: CronExpression 解析;空串或解析失败返回 0
     * - 未知: 默认按 daily
     */
    private fun computeNextRun(interval: String, cronExpr: String, now: Long): Long {
        return when (interval) {
            "once" -> now
            "hourly" -> now + 3_600_000L
            "daily" -> now + 86_400_000L
            "weekly" -> now + 604_800_000L
            "cron" -> {
                if (cronExpr.isBlank()) return 0
                try {
                    io.zer0.muse.schedule.CronExpression.parse(cronExpr).nextRunAfter(now)
                } catch (e: Exception) {
                    Logger.w("ToolRegistry", "Invalid cron expr '$cronExpr': ${e.message}")
                    0
                }
            }
            else -> now + 86_400_000L
        }
    }

    /** 格式化时间戳为可读字符串(<=0 视为已禁用)。 */
    private fun formatTimestamp(ts: Long): String {
        return if (ts > 0) FMT_DATETIME_MIN.get()?.format(Date(ts)) ?: "未知" else "已禁用"
    }

    /** 格式化定时任务为单条摘要。 */
    private fun formatScheduledTask(t: ScheduledTaskEntity): String {
        val intervalDesc = when (t.interval) {
            "once" -> "单次"
            "hourly" -> "每小时"
            "daily" -> "每天"
            "weekly" -> "每周"
            "cron" -> "Cron: ${t.cronExpr}"
            else -> t.interval
        }
        return "• ${t.name}[id=${t.id}]\n  间隔: $intervalDesc | 下次: ${formatTimestamp(t.nextRunAt)} | ${if (t.enabled) "启用" else "禁用"}"
    }

    /** 创建定时任务。 */
    private suspend fun execScheduledTaskCreate(args: Map<String, String>): String {
        val name = args["name"]?.takeIf { it.isNotBlank() }
            ?: return "缺少必填参数: name"
        val prompt = args["prompt"]?.takeIf { it.isNotBlank() }
            ?: return "缺少必填参数: prompt"
        val interval = args["interval"]?.takeIf { it.isNotBlank() } ?: "daily"
        val cronExpr = args["cron_expr"] ?: ""
        val assistantId = args["assistant_id"]?.takeIf { it.isNotBlank() } ?: "default"
        val actionType = args["action_type"]?.takeIf { it.isNotBlank() } ?: "ai_prompt"
        val conditionType = args["condition_type"]?.takeIf { it.isNotBlank() } ?: "always"

        if (interval == "cron" && cronExpr.isBlank()) {
            return "interval=cron 时必须提供 cron_expr"
        }

        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val task = ScheduledTaskEntity(
            id = id,
            name = name,
            prompt = prompt,
            assistantId = assistantId,
            interval = interval,
            cronExpr = cronExpr,
            enabled = true,
            nextRunAt = computeNextRun(interval, cronExpr, now),
            createdAt = now,
            updatedAt = now,
            actionType = actionType,
            // always 用空串保持与旧版兼容,其他类型写入最小 JSON 供 Runner 解析
            conditionJson = if (conditionType == "always") "" else """{"type":"$conditionType"}""",
            createdBy = "assistant",
        )
        MuseDb.get(context).scheduledTaskDao().upsert(task)
        return "已创建定时任务: ${task.name}[id=$id] (间隔=$interval, 下次执行=${formatTimestamp(task.nextRunAt)})"
    }

    /** 列出定时任务。 */
    private suspend fun execScheduledTaskList(args: Map<String, String>): String {
        val enabledOnly = args["enabled"]?.let { it.equals("true", ignoreCase = true) }
        val limit = args["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: 20
        val all = MuseDb.get(context).scheduledTaskDao().getAll()
        val filtered = when (enabledOnly) {
            true -> all.filter { it.enabled }
            false -> all.filter { !it.enabled }
            null -> all
        }.take(limit)
        if (filtered.isEmpty()) return "暂无定时任务"
        val sb = StringBuilder("定时任务 (${filtered.size}):\n")
        filtered.forEach { sb.appendLine(formatScheduledTask(it)) }
        return sb.toString().trimEnd()
    }

    /** 更新定时任务。仅传需要修改的字段,未传字段保留原值。 */
    private suspend fun execScheduledTaskUpdate(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return "缺少必填参数: id"
        val dao = MuseDb.get(context).scheduledTaskDao()
        val existing = dao.getById(id) ?: return "未找到任务: $id"
        val name = args["name"] ?: existing.name
        val prompt = args["prompt"] ?: existing.prompt
        val interval = args["interval"] ?: existing.interval
        val cronExpr = args["cron_expr"] ?: existing.cronExpr
        val enabled = args["enabled"]?.let { it.equals("true", ignoreCase = true) } ?: existing.enabled

        if (interval == "cron" && cronExpr.isBlank()) {
            return "interval=cron 时必须提供 cron_expr"
        }

        val now = System.currentTimeMillis()
        // 间隔或 cron 表达式变化时重新计算下次执行时间;仅切换 enabled 不重算
        val nextRunAt = if (interval != existing.interval || cronExpr != existing.cronExpr) {
            computeNextRun(interval, cronExpr, now)
        } else {
            existing.nextRunAt
        }
        val updated = existing.copy(
            name = name,
            prompt = prompt,
            interval = interval,
            cronExpr = cronExpr,
            enabled = enabled,
            nextRunAt = nextRunAt,
            updatedAt = now,
        )
        dao.upsert(updated)
        return "已更新定时任务: ${updated.name}[id=$id]"
    }

    /** 删除定时任务。 */
    private suspend fun execScheduledTaskDelete(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return "缺少必填参数: id"
        val dao = MuseDb.get(context).scheduledTaskDao()
        val existing = dao.getById(id) ?: return "未找到任务: $id"
        dao.delete(id)
        return "已删除定时任务: ${existing.name}[id=$id]"
    }

    /** 立即执行一次定时任务(把 next_run_at 设为现在,下一轮轮询执行)。 */
    private suspend fun execScheduledTaskExecute(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return "缺少必填参数: id"
        val dao = MuseDb.get(context).scheduledTaskDao()
        val existing = dao.getById(id) ?: return "未找到任务: $id"
        if (!existing.enabled) return "任务已禁用,请先启用后再执行: $id"
        // 触发下一轮轮询执行(最多 60 秒内由 ScheduledTaskRunner 拾取)
        dao.triggerNextTasks(listOf(id))
        return "已触发任务执行: ${existing.name}[id=$id],将在下一轮轮询中执行"
    }

    /** 查询定时任务执行历史。 */
    private suspend fun execScheduledTaskGetHistory(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return "缺少必填参数: id"
        val limit = args["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: 10
        val dao = MuseDb.get(context).scheduledTaskDao()
        val existing = dao.getById(id) ?: return "未找到任务: $id"
        val records = MuseDb.get(context).scheduledTaskExecutionDao().queryByTaskId(id)
        if (records.isEmpty()) return "任务 ${existing.name}[id=$id] 暂无执行历史"
        val sb = StringBuilder("执行历史 (任务=${existing.name}):\n")
        records.take(limit).forEach { e ->
            val time = FMT_DATETIME_MIN.get()?.format(Date(e.executedAt)) ?: "未知"
            val detail = when (e.status) {
                "success" -> "成功: ${e.replySummary.take(80)}"
                "failed" -> "失败: ${e.errorMessage}"
                "skipped" -> "跳过(条件不满足)"
                else -> e.status
            }
            sb.appendLine("• [$time] $detail")
        }
        return sb.toString().trimEnd()
    }

    // ── v1.0.17: 翻译工具(translate)──────────────────────────────────────────

    /**
     * translate — 调用 ChatService 翻译文本,与 SkillExecutor 的 translate skill 对齐。
     *
     * 策略:completeText 优先(一次性,速度快)→ streamChat 降级(Provider 未实现 completeText 时)。
     * 推理模型可能内嵌 <think> 标签,统一用 [stripThinkTags] 剥离,只返回纯净译文。
     *
     * 参数:
     *  - text: 必填,要翻译的原文
     *  - target_language: 必填,目标语言(中文/English/日本語 等)
     *  - source_language: 可选,源语言,不填则自动检测
     *  - style: 可选,翻译风格(通用/学术/商务/口语化/润色/简洁),默认通用
     */
    private suspend fun execTranslate(args: Map<String, String>): String {
        val text = args["text"]?.takeIf { it.isNotBlank() }
            ?: return "缺少必填参数: text"
        val targetLanguage = args["target_language"]?.takeIf { it.isNotBlank() }
            ?: return "缺少必填参数: target_language"
        val sourceLanguage = args["source_language"]?.takeIf { it.isNotBlank() }
        val style = args["style"]?.takeIf { it.isNotBlank() } ?: "通用"

        val styleInstruction = when (style) {
            "学术" -> "使用学术风格,用词正式严谨。"
            "商务" -> "使用商务风格,用词专业得体。"
            "口语化" -> "使用口语化风格,自然易懂。"
            "润色" -> "在翻译基础上润色,使译文更流畅优美。"
            "简洁" -> "使用简洁风格,用词精炼。"
            else -> "" // 通用,无额外指令
        }

        // 构建 system prompt(参考 TranslateViewModel.buildTranslationPrompt)
        val systemPrompt = buildString {
            if (sourceLanguage != null) {
                append("你是一个专业翻译助手。请将下面的文本从$sourceLanguage 翻译为$targetLanguage。")
            } else {
                append("你是一个专业翻译助手。请自动识别下面文本的语言,并将其翻译为$targetLanguage。")
            }
            append("要求:只输出译文,保留原文格式,原文已是目标语言则原样输出。")
            append(styleInstruction)
        }
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = systemPrompt),
            UIMessage(role = MessageRole.USER, content = text),
        )

        // ChatService 通过 Koin 获取(Safe Mode 下 Koin 可能未初始化,走 resultOf 兜底)
        val koin = resultOf { GlobalContext.get() }.getOrNull()
            ?: return "翻译服务不可用(Koin 未初始化)"
        val chatService = resultOf { koin.get<ChatService>() }.getOrNull()
            ?: return "翻译服务不可用(ChatService 未注册)"

        return resultOf {
            // 优先 completeText(一次性返回,速度快)
            val translated: String = try {
                val completion = chatService.completeText(messages = messages)
                stripThinkTags(completion.text).trim()
            } catch (e: UnsupportedOperationException) {
                // Provider 未实现 completeText,降级流式
                collectTranslateStream(chatService, messages)
            } catch (e: Exception) {
                // 其他错误也降级流式(网络抖动等)
                Logger.w("ToolRegistry", "translate completeText 失败,降级 streamChat", e)
                collectTranslateStream(chatService, messages)
            }
            if (translated.isEmpty()) "翻译结果为空,请检查原文或目标语言。" else translated
        }.onError { msg, _ -> Logger.w("ToolRegistry", "translate 失败: $msg") }
            .getOrNull() ?: "翻译失败,请稍后重试。"
    }

    /** 流式收集翻译结果(completeText 不可用时的降级路径)。 */
    private suspend fun collectTranslateStream(
        chatService: ChatService,
        messages: List<UIMessage>,
    ): String {
        val sb = StringBuilder()
        chatService.streamChat(messages = messages).collect { event ->
            when (event) {
                is ChatStreamEvent.ContentDelta -> sb.append(event.delta)
                is ChatStreamEvent.Error -> throw ProviderException(
                    providerError = event.providerError ?: ProviderError.Unknown(displayMessage = event.message),
                    cause = event.throwable,
                )
                else -> { /* 忽略 ReasoningDelta / ToolCallDelta / ImageDelta / Done 等 */ }
            }
        }
        return stripThinkTags(sb.toString()).trim()
    }

    // ── v1.136: 网络/编码/TTS 工具 ──────────────────────────────────────────

    /** Ping 指定的域名或 IP。 */
    private suspend fun execPingHost(args: Map<String, String>): String {
        val host = args["host"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_url_missing)
        val timeout = args["timeout_ms"]?.toIntOrNull()?.coerceAtLeast(100) ?: 3000
        return resultOf {
            withContext(Dispatchers.IO) {
                val address = java.net.InetAddress.getByName(host)
                val reachable = address.isReachable(timeout)
                context.getString(
                    R.string.tool_ping_host_result,
                    host,
                    address.hostAddress ?: "unknown",
                    if (reachable) context.getString(R.string.tool_yes) else context.getString(R.string.tool_no),
                    "$timeout",
                )
            }
        }.onError { msg, _ -> Logger.w("ToolRegistry", "Ping 失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_ping_host_failed, host)
    }

    /** DNS 解析域名。 */
    private suspend fun execDnsLookup(args: Map<String, String>): String {
        val host = args["host"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_url_missing)
        return resultOf {
            withContext(Dispatchers.IO) {
                val addresses = java.net.InetAddress.getAllByName(host)
                val ips = addresses.mapNotNull { it.hostAddress }
                if (ips.isEmpty()) {
                    context.getString(R.string.tool_dns_lookup_empty, host)
                } else {
                    context.getString(R.string.tool_dns_lookup_result, host, ips.size, ips.joinToString("\n"))
                }
            }
        }.onError { msg, _ -> Logger.w("ToolRegistry", "DNS 解析失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_dns_lookup_failed, host)
    }

    /** 获取公网 IP。 */
    private suspend fun execGetPublicIp(_args: Map<String, String>): String {
        return resultOf {
            withContext(Dispatchers.IO) {
                val endpoints = listOf(
                    "https://api.ipify.org",
                    "https://checkip.amazonaws.com",
                )
                for (endpoint in endpoints) {
                    try {
                        val url = java.net.URL(endpoint)
                        val ip = url.openStream().bufferedReader(Charsets.UTF_8).use { it.readLine()?.trim() }
                        if (!ip.isNullOrBlank()) {
                            return@withContext context.getString(R.string.tool_public_ip_result, ip)
                        }
                    } catch (_: Exception) { /* try next */ }
                }
                context.getString(R.string.tool_public_ip_failed)
            }
        }.onError { msg, _ -> Logger.w("ToolRegistry", "获取公网 IP 失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_public_ip_failed)
    }

    /** JSON 美化/压缩。 */
    private suspend fun execJsonPretty(args: Map<String, String>): String {
        val input = args["json"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_url_missing)
        val indent = args["indent"]?.equals("true", ignoreCase = true) ?: true
        return resultOf {
            val prettyJson = Json {
                prettyPrint = indent
                ignoreUnknownKeys = true
            }
            val element = Json.parseToJsonElement(input)
            prettyJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), element)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "JSON 格式化失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_json_pretty_failed)
    }

    /** 生成随机密码。 */
    private suspend fun execGeneratePassword(args: Map<String, String>): String {
        val length = args["length"]?.toIntOrNull()?.coerceIn(4, 64) ?: 16
        val useUpper = args["uppercase"]?.equals("false", ignoreCase = true) != true
        val useLower = args["lowercase"]?.equals("false", ignoreCase = true) != true
        val useDigits = args["digits"]?.equals("false", ignoreCase = true) != true
        val useSymbols = args["symbols"]?.equals("false", ignoreCase = true) != true
        val pool = buildString {
            if (useUpper) append("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
            if (useLower) append("abcdefghijklmnopqrstuvwxyz")
            if (useDigits) append("0123456789")
            if (useSymbols) append("!@#$%^&*()-_=+[]{}|;:,.<>?")
        }
        if (pool.isEmpty()) return context.getString(R.string.tool_password_empty_pool)
        val random = java.security.SecureRandom()
        val password = CharArray(length) { pool[random.nextInt(pool.length)] }.concatToString()
        return context.getString(R.string.tool_password_result, length, password)
    }

    /** 使用系统 TTS 朗读文本。 */
    private suspend fun execSpeakText(args: Map<String, String>): String {
        val text = args["text"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_url_missing)
        val language = args["language"]
        val rate = args["rate"]?.toFloatOrNull()?.coerceIn(0.25f, 4.0f) ?: 1.0f
        return resultOf {
            val result = speakWithTts(text, language, rate)
            if (result) context.getString(R.string.tool_speak_text_success, text.take(50))
            else context.getString(R.string.tool_speak_text_failed)
        }.onError { msg, _ -> Logger.w("ToolRegistry", "TTS 失败: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_speak_text_failed)
    }

    /** TTS 朗读实现(挂起直到初始化完成并加入队列)。 */
    private suspend fun speakWithTts(text: String, language: String?, rate: Float): Boolean {
        return suspendCancellableCoroutine { cont ->
            var tts: android.speech.tts.TextToSpeech? = null
            tts = android.speech.tts.TextToSpeech(context) { status ->
                if (status != android.speech.tts.TextToSpeech.SUCCESS) {
                    tts?.shutdown()
                    cont.resume(false) { _, _, _ -> }
                    return@TextToSpeech
                }
                try {
                    tts?.setSpeechRate(rate)
                    if (!language.isNullOrBlank()) {
                        val locale = java.util.Locale.forLanguageTag(language)
                        val langResult = tts?.setLanguage(locale)
                        if (langResult == android.speech.tts.TextToSpeech.LANG_MISSING_DATA ||
                            langResult == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED
                        ) {
                            tts?.shutdown()
                            cont.resume(false) { _, _, _ -> }
                            return@TextToSpeech
                        }
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        val params = android.os.Bundle()
                        tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, params, null)
                    } else {
                        @Suppress("DEPRECATION")
                        tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null)
                    }
                    // 朗读已加入队列即视为成功,不等待整段读完
                    cont.resume(true) { _, _, _ -> }
                } catch (e: Exception) {
                    Logger.w("ToolRegistry", "TTS 朗读异常: ${e.message}")
                    cont.resume(false) { _, _, _ -> }
                } finally {
                    // 不立即 shutdown,让系统自行完成朗读
                }
            }
            cont.invokeOnCancellation {
                tts.shutdown()
            }
        }
    }

    companion object {
        // v1.95: 所有内置 tool id 列表(与 init 块注册的工具一一对应)
        // 供 AssistantRepository.ensureDefaultExists 静态读取,无需 ToolRegistry 实例
        val BUILT_IN_TOOL_IDS: List<String> = listOf(
            "get_weather", "get_current_time", "calculator", "echo", "clipboard_read", "clipboard_write",
            "screen_time", "calendar_today", "add_calendar_event",
            "set_alarm", "set_timer", "open_app", "share_text", "get_location",
            "get_device_info", "get_contacts_count", "get_contacts_list",
            "send_sms", "add_contact",
            "open_system_setting", "toggle_wifi", "toggle_bluetooth", "send_email",
            "get_battery_info", "get_recent_notifications",
            "open_url", "list_installed_apps", "get_network_info",
            // v1.136: 新增系统/设备/编码工具
            "get_storage_info", "get_memory_info", "get_display_info", "get_cpu_info", "get_sensors_list",
            "get_brightness", "set_brightness", "get_volume", "set_volume", "toggle_flashlight", "vibrate",
            "get_foreground_app", "get_wifi_info", "get_bluetooth_devices", "make_phone_call", "open_maps",
            "url_encode", "url_decode", "base64_encode", "base64_decode", "hash_text", "generate_uuid", "random_number",
            // v1.136: 定时提醒与资源库工具
            "schedule_reminder", "cancel_reminder", "list_reminders",
            "resource_add", "resource_list", "resource_search", "resource_get", "resource_delete",
            // v1.136: 快速记录工具
            "quick_note_add", "quick_note_list", "quick_note_search", "quick_note_get",
            "quick_note_update", "quick_note_delete", "quick_note_pin",
            // v1.136: 网络/编码/TTS 工具
            "ping_host", "dns_lookup", "get_public_ip", "json_pretty", "generate_password", "speak_text",
            // v1.135: 媒体生成工具(ChatViewModel 注册,此处登记用于默认助手启用)
            "generate_image", "generate_video", "generate_qr_code",
            // v1.95: 表情包库工具(SkillExecutor 实现,此处登记便于统一识别)
            "list_stickers", "send_sticker",
            // HanaAgent port: additional tools
            "pin_memory", "unpin_memory",
            "recall_experience", "record_experience",
            "todo_write", "show_card", "notify", "current_status",
            "subagent_task",
            // JS 沙盒工具(WebView evaluateJavascript,CodeExecutionTool 实现)
            "execute_javascript",
            // P2-6: 浏览器自动化工具(BrowserAutomationTool 实现,headless WebView)
            "browser_navigate", "browser_click", "browser_type",
            "browser_extract", "browser_scroll_bottom", "browser_get_html",
            // P2-7: 工作区文件管理工具(WorkspaceToolsRegistrar 注册)
            "workspace_list", "workspace_read", "workspace_write",
            "workspace_delete", "workspace_mkdir", "workspace_move",
            // v1.0.17: 定时任务工具(助手可创建/管理定时任务)
            "scheduled_task_create", "scheduled_task_list", "scheduled_task_update",
            "scheduled_task_delete", "scheduled_task_execute", "scheduled_task_get_history",
            // v1.0.17: 翻译工具(与 SkillExecutor translate skill 对齐,统一走 ToolRegistry)
            "translate",
        )

        /**
         * v1.131: Tool 内常用日期格式器 — ThreadLocal 缓存,避免每次 LLM 工具调用都新建 SimpleDateFormat。
         * LLM 高频调用工具(getCalendarEvents / parseDateTime / getNotifications 等),
         * 旧实现每次都 `SimpleDateFormat(pattern, Locale).format(...)` 造成 GC 压力。
         * SimpleDateFormat 非线程安全,用 ThreadLocal 保证每线程独立实例。
         */
        private val FMT_DATE = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
        private val FMT_DATETIME_MIN = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
        private val FMT_TIME_MIN = ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.getDefault()) }

        /** parseDateTime 尝试的所有格式(线程安全列表,内部 SimpleDateFormat 通过 ThreadLocal 隔离)。 */
        private val PARSE_FORMATS_PATTERNS = listOf(
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd",
        )
        private val PARSE_FORMATS_TL = ThreadLocal.withInitial {
            PARSE_FORMATS_PATTERNS.map { SimpleDateFormat(it, Locale.getDefault()) }
        }
    }
}

/**
 * 简易四则运算计算器(递归下降解析)。
 *
 * 文法:
 *   expr   = term (('+' | '-') term)*
 *   term   = factor (('*' | '/') factor)*
 *   factor = number | '(' expr ')' | '-' factor
 *
 * 不依赖 javax.script(Nashorn 在 Android 不可用)。
 */
private object Calculator {
    // 本 Calculator 为纯解析器,异常消息使用英文(通过 execCalculator 的 context.getString 本地化)
    fun eval(input: String): Double {
        val chars = input.filterNot { it.isWhitespace() }.toCharArray()
        val pos = intArrayOf(0)
        val result = parseExpr(chars, pos)
        if (pos[0] != chars.size) throw IllegalArgumentException("cannot parse: ${input.substring(pos[0])}")
        return result
    }

    private fun parseExpr(chars: CharArray, pos: IntArray): Double {
        var v = parseTerm(chars, pos)
        while (pos[0] < chars.size) {
            when (chars[pos[0]]) {
                '+' -> { pos[0]++; v += parseTerm(chars, pos) }
                '-' -> { pos[0]++; v -= parseTerm(chars, pos) }
                else -> break
            }
        }
        return v
    }

    private fun parseTerm(chars: CharArray, pos: IntArray): Double {
        var v = parseFactor(chars, pos)
        while (pos[0] < chars.size) {
            when (chars[pos[0]]) {
                '*' -> { pos[0]++; v *= parseFactor(chars, pos) }
                '/' -> { pos[0]++; v /= parseFactor(chars, pos) }
                else -> break
            }
        }
        return v
    }

    private fun parseFactor(chars: CharArray, pos: IntArray): Double {
        if (pos[0] >= chars.size) throw IllegalArgumentException("unexpected end of expression")
        if (chars[pos[0]] == '-') {
            pos[0]++
            return -parseFactor(chars, pos)
        }
        if (chars[pos[0]] == '(') {
            pos[0]++
            val v = parseExpr(chars, pos)
            if (pos[0] >= chars.size || chars[pos[0]] != ')') throw IllegalArgumentException("missing closing parenthesis")
            pos[0]++
            return v
        }
        val start = pos[0]
        while (pos[0] < chars.size && (chars[pos[0]].isDigit() || chars[pos[0]] == '.')) pos[0]++
        if (start == pos[0]) throw IllegalArgumentException("expected number, got: ${chars[pos[0]]}")
        val len = pos[0] - start
        return String(chars, start, len).toDouble()
    }
}
