package io.zer0.muse.tools

/**
 * P1-3b 拆域：手机端工具注册器。
 *
 * 注册 set_alarm / set_timer / open_app / share_text / get_location /
 * get_device_info / get_contacts_count / get_contacts_list / send_sms /
 * add_contact / make_phone_call / open_maps。
 * 实现位于 [PhoneToolsImpl.kt]（原样迁移的顶层函数）。
 */
class PhoneToolsRegistrar(
    private val context: android.content.Context,
    private val toolRegistry: ToolRegistry,
) {
    private val impl = PhoneToolsImpl(context)

    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "set_alarm",
                description = "设置系统闹钟(通过 AlarmClock.ACTION_SET_ALARM,系统时钟应用承接)。",
                parameters = mapOf(
                    "hour" to "必填,闹钟小时(0-23)",
                    "minute" to "必填,闹钟分钟(0-59)",
                    "label" to "可选,闹钟标签,默认 'muse 闹钟'",
                    "days_of_week" to "可选,每周重复,如 MON,TUE,WED,THU,FRI",
                    "weekdays" to "可选,快捷设工作日重复(周一至周五),传 true 即展开为 MON-FRI,优先于 days_of_week",
                    "weekends" to "可选,快捷设周末重复(周六周日),传 true 即展开为 SAT,SUN,优先于 days_of_week",
                ),
                required = setOf("hour", "minute"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execSetAlarm(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "set_timer",
                description = "设置系统倒计时(通过 AlarmClock.ACTION_SET_TIMER,系统时钟应用承接)。",
                parameters = mapOf(
                    "seconds" to "必填,倒计时秒数",
                    "label" to "可选,倒计时标签,默认 'muse 倒计时'",
                ),
                required = setOf("seconds"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execSetTimer(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "open_app",
                description = "打开指定应用(通过包名启动其主 Activity),或通过 Deep Link/自定义 action 跳转。",
                parameters = mapOf(
                    "packageName" to "可选,应用包名,如 com.tencent.mm(微信)/com.tencent.mobileqq(QQ)",
                    "action" to "可选,自定义 action(如 com.example.MY_ACTION)",
                    "data_uri" to "可选,Deep Link URI,如 myapp://page/123",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execOpenApp(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "share_text",
                description = "通过系统分享面板分享文本(弹出选择器)。",
                parameters = mapOf(
                    "text" to "必填,要分享的文本",
                    "title" to "可选,分享面板标题",
                ),
                required = setOf("text"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execShareText(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_location",
                description = "获取设备当前位置(经纬度)。需要定位权限。",
                parameters = mapOf(
                    "provider" to "可选,gps/network/passive,默认自动",
                    "timeout" to "可选,超时毫秒数,默认 5000",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execGetLocation(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_device_info",
                description = "获取设备信息:品牌/型号/Android 版本/屏幕分辨率/电量。",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetDeviceInfo(emptyMap()) }

        toolRegistry.registerOutcome(
            ToolRegistry.ToolDef(
                name = "get_device_info",
                description = "获取设备信息(结构化版本)。",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetDeviceInfoOutcome(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_contacts_count",
                description = "获取通讯录联系人数量。需要 READ_CONTACTS 权限。",
                parameters = mapOf("filter" to "可选,按名称过滤"),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execGetContactsCount(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_contacts_list",
                description = "获取通讯录联系人列表。需要 READ_CONTACTS 权限。",
                parameters = mapOf(
                    "limit" to "可选,返回数量,默认 20",
                    "filter" to "可选,按名称过滤",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execGetContactsList(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "send_sms",
                description = "发送短信。需要 SEND_SMS 权限。",
                parameters = mapOf(
                    "phone" to "必填,目标手机号",
                    "message" to "必填,短信内容",
                ),
                required = setOf("phone", "message"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.execSendSms(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "add_contact",
                description = "添加一个联系人。需要 WRITE_CONTACTS 权限。",
                parameters = mapOf(
                    "name" to "必填,联系人姓名",
                    "phone" to "可选,手机号",
                    "email" to "可选,邮箱",
                ),
                required = setOf("name"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.execAddContact(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "make_phone_call",
                description = "打开系统拨号界面并预填手机号(不会直接拨出)。",
                parameters = mapOf("phone" to "必填,目标手机号"),
                required = setOf("phone"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execMakePhoneCall(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "open_maps",
                description = "打开地图应用搜索地点或导航到指定经纬度。",
                parameters = mapOf(
                    "query" to "可选,搜索关键词,如 故宫博物院",
                    "lat" to "可选,纬度",
                    "lng" to "可选,经度",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execOpenMaps(args) }
    }
}
