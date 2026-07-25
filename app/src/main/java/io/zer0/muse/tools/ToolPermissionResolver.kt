package io.zer0.muse.tools

/**
 * 工具权限解析器。
 *
 * 综合三要素决定一次工具调用是否需要审批:
 *  1. 当前会话的 [SessionPermissionMode]
 *  2. 工具自身的 [ToolRiskLevel]
 *  3. 用户针对该工具单独设置的 [ToolApprovalPolicy]
 *
 * 优先级:单工具 ALWAYS_DENY > 会话 STRICT > 单工具 ALWAYS_ALLOW > 会话 TRUSTED/ASK。
 */
object ToolPermissionResolver {

    /**
     * 解析工具调用的初始审批状态。
     *
     * @param toolName 工具名
     * @param risk 工具风险等级(UNKNOWN 时使用 [fallbackRiskFor] 推断)
     * @param mode 当前会话权限模式
     * @param perToolPolicy 用户对该工具设置的持久化策略,未设置为 null
     */
    fun resolve(
        toolName: String,
        risk: ToolRiskLevel?,
        mode: SessionPermissionMode,
        perToolPolicy: ToolApprovalPolicy?,
    ): ToolApprovalState {
        val effectiveRisk = risk ?: fallbackRiskFor(toolName)
        // 1. 用户显式禁用某工具时,任何模式都拒绝
        if (perToolPolicy == ToolApprovalPolicy.ALWAYS_DENY) {
            return ToolApprovalState.Denied("工具 $toolName 已被用户禁用")
        }

        // 2. 严格模式:安全工具之外全部审批;安全工具中若涉及外部应用/网络也审批
        if (mode == SessionPermissionMode.STRICT) {
            return if (effectiveRisk == ToolRiskLevel.SAFE && toolName in STRICT_SAFE_ALLOWLIST) {
                ToolApprovalState.Auto
            } else {
                ToolApprovalState.Pending
            }
        }

        // 3. 用户显式允许某工具时,信任模式下自动执行;询问模式下仍需审批(除非为安全工具)
        if (perToolPolicy == ToolApprovalPolicy.ALWAYS_ALLOW) {
            return when (mode) {
                SessionPermissionMode.TRUSTED -> ToolApprovalState.Auto
                SessionPermissionMode.ASK -> if (effectiveRisk == ToolRiskLevel.SAFE) ToolApprovalState.Auto else ToolApprovalState.Pending
                SessionPermissionMode.STRICT -> ToolApprovalState.Pending // 上面已处理,不会到达
            }
        }

        // 4. 默认策略(按会话模式 + risk)
        return when (mode) {
            SessionPermissionMode.TRUSTED -> when (effectiveRisk) {
                ToolRiskLevel.SAFE -> ToolApprovalState.Auto
                ToolRiskLevel.NORMAL -> ToolApprovalState.Auto
                ToolRiskLevel.HIGH -> ToolApprovalState.Pending
            }
            SessionPermissionMode.ASK -> when (effectiveRisk) {
                ToolRiskLevel.SAFE -> ToolApprovalState.Auto
                ToolRiskLevel.NORMAL -> ToolApprovalState.Pending
                ToolRiskLevel.HIGH -> ToolApprovalState.Pending
            }
            SessionPermissionMode.STRICT -> ToolApprovalState.Pending // 上面已处理
        }
    }

    /**
     * 对未在 [ToolRegistry] 注册的工具(如 Skill 工具 / MCP 工具)做风险等级兜底。
     *
     * v1.0.20: 改为"显式映射 + 名称前缀推断"双层兜底,覆盖项目内大量按命名规范命名的工具。
     *
     * 优先级:
     *  1. 显式映射表 [EXPLICIT_RISK_OVERRIDES](个别语义与命名前缀不一致的工具,如 open_url 归 HIGH)
     *  2. 名称前缀推断(覆盖 read_/get_/list_/search_ 等只读族,send_/delete_ 等高危族)
     *  3. 默认 NORMAL(未知工具保守按普通风险处理,ASK 模式下会询问)
     *
     * 命名前缀归类依据:
     *  - SAFE:read_* / get_* / list_* / search_* / ping_* / dns_* / url_* / base64_* / hash_* /
     *          generate_* / calculator / echo — 纯查询/编码/计算,无副作用
     *  - NORMAL:set_* / update_* / create_* / add_* / toggle_* / open_* / share_* / speak_* /
     *          vibrate / scheduled_task_* / quick_note_* / resource_* / workspace_* / translate —
     *          本地副作用但可逆
     *  - HIGH:send_* / delete_* / make_phone_call / toggle_wifi / toggle_bluetooth /
     *          toggle_flashlight / set_brightness / set_volume / set_alarm / set_timer /
     *          open_url / open_maps / send_email / send_sms / add_contact / add_calendar_event /
     *          pin_memory / unpin_memory / subagent_task / browser_* / execute_javascript —
     *          不可逆/跨设备/资金或隐私影响
     */
    private fun fallbackRiskFor(toolName: String): ToolRiskLevel {
        // 1. 显式映射优先(覆盖语义与命名前缀不一致的工具)
        EXPLICIT_RISK_OVERRIDES[toolName]?.let { return it }

        // 2. 名称前缀推断(注意:HIGH 族中含 set_/open_/send_ 等更具体前缀,需先于 NORMAL 族匹配)
        return when {
            // ── HIGH 族(更具体前缀,优先于 NORMAL 族匹配)──
            toolName.startsWith("send_") -> ToolRiskLevel.HIGH
            toolName.startsWith("delete_") -> ToolRiskLevel.HIGH
            toolName.startsWith("browser_") -> ToolRiskLevel.HIGH
            // open_url / open_maps 是 HIGH,但 open_app / open_system_setting 归 NORMAL — 用显式映射区分
            // set_brightness / set_volume / set_alarm / set_timer 是 HIGH,但其他 set_* 归 NORMAL — 用显式映射区分
            // 高风险显式列表
            toolName in HIGH_RISK_EXPLICIT_SET -> ToolRiskLevel.HIGH

            // ── SAFE 族(纯查询/编码/计算)──
            toolName.startsWith("read_") -> ToolRiskLevel.SAFE
            toolName.startsWith("get_") -> ToolRiskLevel.SAFE
            toolName.startsWith("list_") -> ToolRiskLevel.SAFE
            toolName.startsWith("search_") -> ToolRiskLevel.SAFE
            toolName.startsWith("ping_") -> ToolRiskLevel.SAFE
            toolName.startsWith("dns_") -> ToolRiskLevel.SAFE
            toolName.startsWith("url_") -> ToolRiskLevel.SAFE
            toolName.startsWith("base64_") -> ToolRiskLevel.SAFE
            toolName.startsWith("hash_") -> ToolRiskLevel.SAFE
            toolName.startsWith("generate_") -> ToolRiskLevel.SAFE
            toolName == "calculator" || toolName == "echo" -> ToolRiskLevel.SAFE

            // ── NORMAL 族(本地副作用,可逆)──
            toolName.startsWith("set_") -> ToolRiskLevel.NORMAL
            toolName.startsWith("update_") -> ToolRiskLevel.NORMAL
            toolName.startsWith("create_") -> ToolRiskLevel.NORMAL
            toolName.startsWith("add_") -> ToolRiskLevel.NORMAL
            toolName.startsWith("toggle_") -> ToolRiskLevel.NORMAL
            toolName.startsWith("open_") -> ToolRiskLevel.NORMAL
            toolName.startsWith("share_") -> ToolRiskLevel.NORMAL
            toolName.startsWith("speak_") -> ToolRiskLevel.NORMAL
            toolName.startsWith("scheduled_task_") -> ToolRiskLevel.NORMAL
            toolName.startsWith("quick_note_") -> ToolRiskLevel.NORMAL
            toolName.startsWith("resource_") -> ToolRiskLevel.NORMAL
            toolName.startsWith("workspace_") -> ToolRiskLevel.NORMAL
            toolName == "vibrate" || toolName == "translate" -> ToolRiskLevel.NORMAL

            // 3. 未知工具保守按 NORMAL(ASK 模式会询问)
            else -> ToolRiskLevel.NORMAL
        }
    }

    /**
     * 显式风险等级映射 — 用于覆盖命名前缀推断的特例。
     *
     * 例如:
     *  - open_url / open_maps 应归 HIGH(打开外部链接/地图,潜在隐私风险),
     *    但 open_app / open_system_setting 归 NORMAL — 同为 open_ 前缀,语义不同,
     *    必须显式区分
     *  - set_brightness / set_volume / set_alarm / set_timer 应归 HIGH(不可逆系统状态),
     *    但其他 set_* 归 NORMAL — 同为 set_ 前缀
     *  - pin_memory / unpin_memory 归 HIGH(影响长期记忆,隐私敏感),
     *    与 [io.zer0.muse.data.audit.AuditLogger] 的审计分类一致
     *  - web_search / web_fetch / read_file 等查询工具归 SAFE
     *  - write_file / pin_memory 等本地副作用工具归 NORMAL 或 HIGH
     */
    private val EXPLICIT_RISK_OVERRIDES: Map<String, ToolRiskLevel> = mapOf(
        // SAFE 族显式(纯查询/读取)
        "web_search" to ToolRiskLevel.SAFE,
        "web_fetch" to ToolRiskLevel.SAFE,
        "knowledge_search" to ToolRiskLevel.SAFE,
        "arxiv_search" to ToolRiskLevel.SAFE,
        "read_file" to ToolRiskLevel.SAFE,
        "http_get" to ToolRiskLevel.SAFE,
        "current_status" to ToolRiskLevel.SAFE,
        "recall_experience" to ToolRiskLevel.SAFE,

        // NORMAL 族显式(本地副作用,可逆)
        "write_file" to ToolRiskLevel.NORMAL,
        "http_post" to ToolRiskLevel.NORMAL,
        "todo_write" to ToolRiskLevel.NORMAL,
        "show_card" to ToolRiskLevel.NORMAL,
        "notify" to ToolRiskLevel.NORMAL,
        "record_experience" to ToolRiskLevel.NORMAL,
        "channel_reply" to ToolRiskLevel.NORMAL,
        "channel_pass" to ToolRiskLevel.NORMAL,
        "channel_read_context" to ToolRiskLevel.NORMAL,

        // HIGH 族显式(不可逆/跨设备/隐私)
        "install_skill" to ToolRiskLevel.HIGH,
        "delegate_agent" to ToolRiskLevel.HIGH,
        "execute_javascript" to ToolRiskLevel.HIGH,
        "workspace_write" to ToolRiskLevel.HIGH,
        "workspace_delete" to ToolRiskLevel.HIGH,
        "workspace_mkdir" to ToolRiskLevel.HIGH,
        "workspace_move" to ToolRiskLevel.HIGH,
        // open_ 前缀中归 HIGH 的特例外链
        "open_url" to ToolRiskLevel.HIGH,
        "open_maps" to ToolRiskLevel.HIGH,
        // set_ 前缀中归 HIGH 的系统状态变更
        "set_brightness" to ToolRiskLevel.HIGH,
        "set_volume" to ToolRiskLevel.HIGH,
        "set_alarm" to ToolRiskLevel.HIGH,
        "set_timer" to ToolRiskLevel.HIGH,
        // 长期记忆 / 跨助手 / 通讯 归 HIGH
        "pin_memory" to ToolRiskLevel.HIGH,
        "unpin_memory" to ToolRiskLevel.HIGH,
        "subagent_task" to ToolRiskLevel.HIGH,
        "make_phone_call" to ToolRiskLevel.HIGH,
        "toggle_wifi" to ToolRiskLevel.HIGH,
        "toggle_bluetooth" to ToolRiskLevel.HIGH,
        "toggle_flashlight" to ToolRiskLevel.HIGH,
    )

    /**
     * 高风险显式工具名集合(用于 [fallbackRiskFor] 中以 `in` 判断的快速查找)。
     *
     * 与 [EXPLICIT_RISK_OVERRIDES] 中 HIGH 部分保持同步,单独抽出以便前缀推断分支
     * 直接用 `toolName in HIGH_RISK_EXPLICIT_SET` 一次匹配多个工具名,
     * 而不必每条都写 `toolName == "xxx"`。
     */
    private val HIGH_RISK_EXPLICIT_SET: Set<String> = setOf(
        "make_phone_call",
        "toggle_wifi",
        "toggle_bluetooth",
        "toggle_flashlight",
        "set_brightness",
        "set_volume",
        "set_alarm",
        "set_timer",
        "open_url",
        "open_maps",
        "send_email",
        "send_sms",
        "add_contact",
        "add_calendar_event",
        "pin_memory",
        "unpin_memory",
        "subagent_task",
    )

    /**
     * 严格模式下仍自动允许的安全工具白名单。
     *
     * v1.0.20: 从 3 个扩展到所有只读工具,让 STRICT 模式下用户日常查询类调用
     * 仍然顺畅(无需逐次审批),仅 NORMAL/HIGH 工具进入审批流程。
     *
     * 覆盖范围:
     *  - get_*(get_current_time / get_weather / get_device_info / get_battery_info /
     *          get_location / get_storage_info / get_memory_info / get_display_info /
     *          get_cpu_info / get_sensors_list / get_brightness / get_volume /
     *          get_foreground_app / get_wifi_info / get_bluetooth_devices /
     *          get_network_info / get_contacts_count 等)
     *  - list_*(list_installed_apps / list_reminders / list_stickers 等)
     *  - read_*(clipboard_read / workspace_read 等)
     *  - search_*(quick_note_search / resource_search 等)
     *  - 其他只读:calculator / echo / ping_host / dns_lookup / get_public_ip /
     *            url_encode / url_decode / base64_encode / base64_decode / hash_text /
     *            generate_uuid / random_number / json_pretty / generate_password /
     *            generate_qr_code / scheduled_task_list / scheduled_task_get_history /
     *            translate / quick_note_get / resource_get / workspace_list
     *
     * 注意:此处只放纯本地只读/查询/编码/计算工具。
     * 任何写入、网络 POST、外部应用跳转、跨助手、浏览器自动化均不在此列。
     */
    private val STRICT_SAFE_ALLOWLIST: Set<String> = setOf(
        // ── get_* 族(只读查询)──
        "get_current_time",
        "get_weather",
        "get_device_info",
        "get_battery_info",
        "get_location",
        "get_storage_info",
        "get_memory_info",
        "get_display_info",
        "get_cpu_info",
        "get_sensors_list",
        "get_brightness",
        "get_volume",
        "get_foreground_app",
        "get_wifi_info",
        "get_bluetooth_devices",
        "get_network_info",
        "get_contacts_count",
        "get_public_ip",
        // ── list_* 族(只读列举)──
        "list_installed_apps",
        "list_reminders",
        "list_stickers",
        // ── read_* 族(只读读取)──
        "clipboard_read",
        "workspace_read",
        // ── search_* 族(只读搜索)──
        "quick_note_search",
        "quick_note_list",
        "resource_search",
        "resource_list",
        // ── 其他只读/计算/编码/查询 ──
        "calculator",
        "echo",
        "ping_host",
        "dns_lookup",
        "url_encode",
        "url_decode",
        "base64_encode",
        "base64_decode",
        "hash_text",
        "generate_uuid",
        "random_number",
        "json_pretty",
        "generate_password",
        "generate_qr_code",
        "scheduled_task_list",
        "scheduled_task_get_history",
        "translate",
        "quick_note_get",
        "resource_get",
        "workspace_list",
    )
}
