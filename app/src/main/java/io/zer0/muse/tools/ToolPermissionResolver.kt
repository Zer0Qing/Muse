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
 *
 * v1.0.52: 新增规则型硬边界防线([isUnsafeCommand]),作为 shell 类工具的第一道防线。
 * 即使会话处于 TRUSTED 模式(全部放权),黑名单内的命令也永远不会执行。
 */
object ToolPermissionResolver {

    init {
        // v1.0.53: 注册内置参数化权限策略(open_url / execute_javascript)
        ParamPolicies.registerBuiltIn()
    }

    /**
     * v1.0.52: 危险可执行文件黑名单(规则型硬边界防线)。
     *
     * 无论会话权限模式如何(TRUSTED/ASK/STRICT),只要命令中出现这些可执行文件名,
     * [isUnsafeCommand] 立即返回 true,调用方必须拒绝执行。
     *
     * 收录依据(参考 openhanako 行动纪律 + 安全最佳实践):
     *  - 文件删除/破坏:rm, rmdir, shred, mkfs
     *  - 权限提升:sudo, su, doas
     *  - 版本控制破坏:git(可 push --force / reset --hard 破坏仓库)
     *  - 网络下载执行:curl, wget(可下载并执行恶意脚本)
     *  - 包管理:apt, pip, npm, yarn, pnpm(可安装恶意包)
     *  - 解释器/shell:bash, sh, zsh, fish, dash(可执行任意脚本)
     *  - 进程管理:kill, killall, pkill(可终止关键进程)
     *  - 权限变更:chmod, chown, chgrp(可破坏文件权限)
     *  - 挂载/系统:mount, umount, reboot, shutdown, poweroff
     *  - 调试/注入:strace, ltrace, ptrace, gdb(可窃取运行时数据)
     */
    private val BLOCKED_EXECUTABLES: Set<String> = setOf(
        // 文件删除/破坏
        "rm", "rmdir", "shred", "mkfs",
        // 权限提升
        "sudo", "su", "doas",
        // 版本控制
        "git",
        // 网络下载
        "curl", "wget",
        // 包管理
        "apt", "apt-get", "pip", "pip3", "npm", "yarn", "pnpm", "gem", "cargo",
        // 解释器/shell
        "bash", "sh", "zsh", "fish", "dash", "python", "python3", "perl", "ruby", "node",
        // 进程管理
        "kill", "killall", "pkill",
        // 权限变更
        "chmod", "chown", "chgrp",
        // 挂载/系统
        "mount", "umount", "reboot", "shutdown", "poweroff",
        // 调试/注入
        "strace", "ltrace", "ptrace", "gdb",
        // 重定向到执行
        "exec", "eval",
    )

    /**
     * v1.0.52: 不安全的 Shell 语法正则(规则型硬边界防线)。
     *
     * 检测命令字符串中的危险语法字符,防止命令注入。与 [ShellSandboxTool.FORBIDDEN_CHARS]
     * 互补(后者是逐字符集合,本正则覆盖更多场景):
     *  - `\r` `\n`:换行注入(在第一条命令后注入第二条命令)
     *  - `` ` ``:反引号命令替换
     *  - `$`:变量展开/命令替换 `$(...)`
     *  - `|`:管道(可管道到危险命令如 `| sh`)
     *  - `;`:命令分隔符
     *  - `&`:后台执行/命令分隔符 `&&`
     *  - `<` `>`:重定向(可覆盖文件)
     *  - `*` `?`:通配符(可意外匹配大量文件)
     *  - `{` `}`:花括号展开(可构造复杂参数)
     *
     * 注意:本正则与 [ShellSandboxTool.FORBIDDEN_CHARS] 有重叠,这是故意的——
     * 多层防御(defense in depth),任一层有 bug 时另一层仍能拦截。
     */
    private val UNSAFE_SHELL_SYNTAX: Regex = Regex("[\r\n`$|;&<>*?{}]")

    /**
     * v1.0.52: 规则型硬边界防线 — 检测命令是否含危险可执行文件或不安全语法。
     *
     * 作为 shell 类工具(如 [ShellSandboxTool])的第一道防线,在白名单校验之前执行。
     * 返回 true 时调用方必须立即拒绝执行,不进入后续权限/白名单流程。
     *
     * 设计原则(参考 openhanako 行动纪律):
     *  - 黑名单优先于白名单:先确认不是已知危险命令,再检查是否在白名单
     *  - 即使会话处于 TRUSTED 模式(全部放权),黑名单仍然生效——这是"硬边界"
     *  - 与 [ShellSandboxTool] 的白名单 + [FORBIDDEN_CHARS] 互补,形成多层防御
     *
     * @param command 待检查的命令字符串
     * @return true 表示命令不安全,必须拒绝;false 表示通过硬边界,可进入后续校验
     */
    fun isUnsafeCommand(command: String): Boolean {
        if (command.isBlank()) return false
        // 1. 不安全语法字符检查(换行注入/命令替换/管道/重定向/通配符等)
        if (UNSAFE_SHELL_SYNTAX.containsMatchIn(command)) return true
        // 2. 危险可执行文件检查:按空白分割后检查每个 token 的裸命令名
        //    裸命令名 = 去掉路径前缀(/usr/bin/rm → rm)后的部分
        val tokens = command.trim().split(Regex("\\s+"))
        for (token in tokens) {
            // 取最后一段作为命令名(处理 /path/to/cmd 形式)
            val bareName = token.substringAfterLast('/').lowercase()
            if (bareName in BLOCKED_EXECUTABLES) return true
        }
        return false
    }

    /**
     * 解析工具调用的初始审批状态。
     *
     * @param toolName 工具名
     * @param risk 工具风险等级(UNKNOWN 时使用 [fallbackRiskFor] 推断)
     * @param mode 当前会话权限模式
     * @param perToolPolicy 用户对该工具设置的持久化策略,未设置为 null
     * @param args v1.0.53: 工具参数,供参数化策略([ParamPolicies])判定
     */
    fun resolve(
        toolName: String,
        risk: ToolRiskLevel?,
        mode: SessionPermissionMode,
        perToolPolicy: ToolApprovalPolicy?,
        args: Map<String, Any?> = emptyMap(),
    ): ToolApprovalState {
        val effectiveRisk = risk ?: fallbackRiskFor(toolName)
        // 1. 用户显式禁用某工具时,任何模式都拒绝
        if (perToolPolicy == ToolApprovalPolicy.ALWAYS_DENY) {
            return ToolApprovalState.Denied("工具 $toolName 已被用户禁用")
        }

        // v1.0.53: 参数化策略(返回非 null 时采用,覆盖静态风险判定)
        ParamPolicies.evaluate(toolName, args)?.let { return it }

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
            // v1.0.48: TRUSTED = 完全放权,所有风险等级都自动执行,不再对 HIGH 工具弹审批
            //   与 SettingsRepository 中"完全放权,所有工具直接调用,不需批准"的注释语义对齐
            SessionPermissionMode.TRUSTED -> ToolApprovalState.Auto
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
            // P3-3: UI 自动化工具(操控设备屏幕/读取 UI/截图),隐私与安全敏感,统一 HIGH
            toolName.startsWith("ui_") -> ToolRiskLevel.HIGH
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
        // v1.0.52 P2-1: subagent_run 同步阻塞式独立子 agent,可调用多个工具,潜在副作用大
        "subagent_run" to ToolRiskLevel.HIGH,
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
        // v1.0.52 P2-1: 同步阻塞式独立子 agent,与 subagent_task 同列 HIGH
        "subagent_run",
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
