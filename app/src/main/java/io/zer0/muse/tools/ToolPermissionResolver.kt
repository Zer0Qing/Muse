package io.zer0.muse.tools

/**
 * 工具权限解析器。
 *
 * 综合三要素决定一次工具调用是否需要审批:
 *  1. 当前会话的 [SessionPermissionMode]
 *  2. 工具自身的 [ToolRiskLevel]
 *  3. 用户针对该工具单独设置的 [ToolApprovalPolicy]
 *
 * 优先级:单工具 ALWAYS_DENY / 参数硬拒绝 > 会话 TRUSTED > 会话 STRICT >
 * 单工具 ALWAYS_ALLOW > 会话 ASK 默认风险策略。
 *
 * v1.0.52: ShellSandboxTool 保留独立规则型硬边界([isUnsafeCommand])。
 * 会话 TRUSTED 模式只跳过逐次审批,不绕过工具自身的沙箱、参数校验或执行器权限。
 */
object ToolPermissionResolver {

    init {
        // v1.0.53: 注册内置参数化权限策略(open_url / execute_javascript)
        ParamPolicies.registerBuiltIn()
    }

    /**
     * v1.0.52: 危险可执行文件黑名单(规则型硬边界防线)。
     *
     * execute_shell 调用中只要出现这些可执行文件名,[isUnsafeCommand] 即拒绝执行。
     * 该只读 Shell 沙箱限制独立于用户选择的审批模式。
     *
     * 收录依据(行动纪律 + 安全最佳实践):
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
     * 设计原则(遵循行动纪律):
     *  - 黑名单优先于白名单:先确认不是已知危险命令,再检查是否在白名单
     *  - TRUSTED 模式允许工具直接执行,但 execute_shell 仍受本硬边界保护
     *  - 与 [ShellSandboxTool] 的白名单 + [FORBIDDEN_CHARS] 互补,形成多层防御
     *
     * v2.x: 第 0 步先跑 [ShellCommandBoundary.forbidReason] 的第一档 —— 通用 shell 的「绕不过的最小集」。
     * 它补的是上面两个判据都看不到、但破坏力已足够拦下的那类命令(例如 `dd of=/dev/block/sda`
     * 既不含禁用的语法字符,`dd` 也不在黑名单里)。这一步只增加拒绝,不放宽已有判据,所以
     * execute_shell 现有行为不变。通用终端工具 [TerminalExecTool] 只用第一档,不用本函数 ——
     * 否则管道、重定向、环境变量这些正常工作会被误杀。
     *
     * @param command 待检查的命令字符串
     * @return true 表示命令不安全,必须拒绝;false 表示通过硬边界,可进入后续校验
     */
    fun isUnsafeCommand(command: String): Boolean {
        if (command.isBlank()) return false
        // 0. 通用 shell 硬边界第一档:按真实破坏力判,不看字符串形状
        if (ShellCommandBoundary.forbidReason(command) != null) return true
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
        // 第三方动态工具不接受「台账里写 SAFE」这一条:注册台账的缺省值就是 SAFE,
        // 第三方工具一旦漏声明就会被当成免审工具,所以 SAFE 一律回落 NORMAL。
        // 显式声明 HIGH 仍照旧生效 —— 这里只收紧,不降级。
        val effectiveRisk = conservativeRisk(toolName, risk ?: fallbackRiskFor(toolName))
        // 1. 用户显式禁用某工具时,任何模式都拒绝
        if (perToolPolicy == ToolApprovalPolicy.ALWAYS_DENY) {
            return ToolApprovalState.Denied("工具 $toolName 已被用户禁用")
        }

        // 参数级硬拒绝始终生效(例如 file:// URL / 危险 JS);TRUSTED 只绕过确认,
        // 不绕过明确非法或危险参数的拒绝。
        val paramState = ParamPolicies.evaluate(toolName, args)
        if (paramState is ToolApprovalState.Denied) {
            return paramState
        }

        // v1.x: TRUSTED = 用户明确选择所有工具免逐次确认。
        // 保留上方显式禁用与参数硬拒绝；执行器自己的沙箱和权限检查不受影响。
        if (mode == SessionPermissionMode.TRUSTED) {
            return ToolApprovalState.Auto
        }

        // v1.0.53: 参数化策略(返回非 null 时采用,覆盖静态风险判定)
        // P0-8: 优先级修正 — 会话模式(STRICT)优先于"参数化 Auto":STRICT 下参数策略
        // 只能收紧(Denied),不能放宽(Auto)。否则 open_url 的 http/https → Auto 会先于
        // 模式判定返回,旁路 STRICT 的"外链一律审批"。参数化 Denied 仍最优先(收紧)。
        // 优先级总序:ALWAYS_DENY > 参数化 Denied > STRICT 模式 > 参数化 Auto > 其他。
        if (paramState != null && !(mode == SessionPermissionMode.STRICT && paramState is ToolApprovalState.Auto)) {
            return paramState
        }

        // 2. 严格模式:安全工具之外全部审批;安全工具中若涉及外部应用/网络也审批
        //    注:显式 ALWAYS_ALLOW 在下方优先于 STRICT 判定(用户显式放行的工具不再询问)
        if (mode == SessionPermissionMode.STRICT && perToolPolicy != ToolApprovalPolicy.ALWAYS_ALLOW) {
            return if (effectiveRisk == ToolRiskLevel.SAFE && toolName in STRICT_SAFE_ALLOWLIST) {
                ToolApprovalState.Auto
            } else {
                ToolApprovalState.Pending
            }
        }

        // 3. 用户显式允许某工具时,任何模式下都直接放行。
        //    v1.x 修复:原实现 ASK/STRICT 模式无视 ALWAYS_ALLOW,导致"始终允许工具"
        //    勾选/按钮后下次调用仍弹审批卡(用户反馈不生效)。
        //    显式策略是最强信号,优先级高于会话模式。
        if (perToolPolicy == ToolApprovalPolicy.ALWAYS_ALLOW) {
            return ToolApprovalState.Auto
        }

        // 4. 默认策略(按会话模式 + risk)
        return when (mode) {
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
     * P0-3: 单一风险真源 — 显式表 [EXPLICIT_RISK_OVERRIDES] + 名称前缀推断的公开入口。
     *
     * 子代理 / 定时任务等无会话审批链路的过滤**必须**走本函数判定风险等级,
     * 不得直接比较注册台账(ToolDef.riskLevel)的值 —— 注册值与显式表不一致时
     * 以本函数为准,避免注册台账降级导致 HIGH 工具被免审批执行。
     *
     * 第三方动态工具(插件 / MCP)不参与名字推断,直接 NORMAL,见 [fallbackRiskFor]。
     */
    fun riskLevelFor(toolName: String): ToolRiskLevel = fallbackRiskFor(toolName)

    /**
     * 第三方动态注册工具的名字形态。
     *
     *  - 插件工具:`plugin_<len>_<pluginId>_<toolName>`(PluginManager.skillId)
     *  - MCP 工具:`mcp_<serverId>__<toolName>`(McpRegistry.registerTools)
     *
     * 两段的**末段**都由第三方自取:`get_diagnostics` 这种「听起来只读」的名字不代表
     * 无副作用,`send_` / `delete_` 这种「听起来危险」的名字也不代表真危险。前缀由宿主
     * 固定,所以前缀只是**来源标记**,不能当风险判据用。
     *
     * 内置的 `plugin_market_*` / `mcp_mgmt_*` 管理工具不符合这两种形态(前者第二段不是
     * 长度数字,后者没有 `__` 分隔),仍按各自声明的等级走。
     */
    private val THIRD_PARTY_TOOL_NAME: Regex = Regex("^plugin_[0-9]+_.+|^mcp_.+__.+")

    /** 该工具名是否来自第三方动态注册(插件 / MCP server)。 */
    internal fun isThirdPartyToolName(toolName: String): Boolean = THIRD_PARTY_TOOL_NAME.matches(toolName)

    /** 第三方动态工具的 SAFE 判定一律收敛为 NORMAL;其余等级原样返回。
     *
     * 口径分界（故意不一致,不是遗漏）：内置的 `mcp_mgmt_*` 不是第三方工具
     * （[THIRD_PARTY_TOOL_NAME] 不匹配它),他们继续沿用注册台账里声明的等级;
     * 对端的 `mcp_<server>__<tool>` 则一律强制 NORMAL。同理 `plugin_market_*` 走台账,
     * `plugin_<len>_<id>_<tool>` 走 NORMAL。
     */
    private fun conservativeRisk(toolName: String, declared: ToolRiskLevel): ToolRiskLevel =
        if (declared == ToolRiskLevel.SAFE && isThirdPartyToolName(toolName)) ToolRiskLevel.NORMAL else declared

    /**
     * P0-3: 显式风险表只读访问(供一致性测试校验注册台账,单一真源)。
     */
    internal fun explicitRiskTable(): Map<String, ToolRiskLevel> = EXPLICIT_RISK_OVERRIDES

    /**
     * 对未在 [ToolRegistry] 注册的工具(如 Skill 工具 / MCP 工具)做风险等级兜底。
     *
     * v1.0.20: 改为"显式映射 + 名称前缀推断"双层兜底,覆盖项目内大量按命名规范命名的工具。
     *
     * 优先级:
     *  0. 第三方动态工具(插件 / MCP):不做名字推断,直接 NORMAL
     *  1. 显式映射表 [EXPLICIT_RISK_OVERRIDES](个别语义与命名前缀不一致的工具,如 open_url 归 HIGH)
     *  2. 名称前缀推断(覆盖 read_/get_/list_/search_ 等只读族,send_/delete_ 等高危族)
     *  3. 默认 NORMAL(未知工具保守按普通风险处理,ASK 模式下会询问)
     *
     * 名字推断只对**内置**工具有效:那些名字写死在代码里,工具作者没有控制权。
     * 插件与 MCP 工具的名字由第三方自取,一旦参与推断就能自己把外发调用命名成
     * `get_diagnostics` 拿到 SAFE、在 ASK 模式下免审,所以它们的名字一律不参与推断。
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
        // 0. 第三方动态工具(插件 / MCP)不做任何名字推断,一律保守 NORMAL。
        //    它们的名字由第三方决定,`get_*` 可能是外发调用,`send_*` 也可能只是本地计算;
        //    按名字给 SAFE 等于把审批权交给工具作者。判定必须在名字之前拦下。
        if (isThirdPartyToolName(toolName)) return ToolRiskLevel.NORMAL

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
            // L1-9: 原此处有 `toolName in HIGH_RISK_EXPLICIT_SET` 分支,但函数开头已
            // `EXPLICIT_RISK_OVERRIDES[toolName]?.let { return it }`,走到这里必定不在表中,
            // 而该 SET 全部条目均来自表 —— 分支永不成立,属死代码,连同 SET 一并删除。

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
        "get_token_usage" to ToolRiskLevel.SAFE,
        "recall_experience" to ToolRiskLevel.SAFE,
        // v2.0.1: 插件市场检索是只读查询
        "plugin_market_search" to ToolRiskLevel.SAFE,

        // NORMAL 族显式(本地副作用,可逆)
        "write_file" to ToolRiskLevel.NORMAL,
        "http_post" to ToolRiskLevel.NORMAL,
        // v2.6.10: 待办清单是 agent 自己的计划板 —— 只画在界面上,不写文件、
        // 不碰设备。曾经归 NORMAL,ASK 模式下每次更新计划都要点一次同意,
        // 30 秒不点就自动拒绝,整个任务编排被卡住。与 show_card 同口径归 SAFE。
        "todo_write" to ToolRiskLevel.SAFE,
        // v2.6.10: 卡片是纯展示动作 —— 内容渲染在沙盒 WebView 里,不写文件、
        // 不触外部应用、不跨设备。曾经归 NORMAL,后果是 ASK 模式下每张卡都要用户点
        // 一次同意,30 秒不点就自动拒绝、卡片直接丢掉。用户看到的就是"卡片生不出来"。
        // 与 render_data 同类同口径,归 SAFE。
        "show_card" to ToolRiskLevel.SAFE,
        "render_data" to ToolRiskLevel.SAFE,
        "notify" to ToolRiskLevel.NORMAL,
        "record_experience" to ToolRiskLevel.NORMAL,
        "channel_reply" to ToolRiskLevel.NORMAL,
        "channel_pass" to ToolRiskLevel.NORMAL,
        "channel_read_context" to ToolRiskLevel.NORMAL,
        // enable_skill 只改变本地启用位、可用 disable_skill 回滚,因此标记为 NORMAL。
        "enable_skill" to ToolRiskLevel.NORMAL,
        // v2.x: 发语音消息 —— 名字是 send_ 前缀(否则会被推成 HIGH),实际只是本地 TTS 合成
        // 然后挂到自己消息上:不外发、不写用户目录。给 HIGH 会让每次发语音都弹审批卡。
        "send_voice" to ToolRiskLevel.NORMAL,

        // HIGH 族显式(不可逆/跨设备/隐私)
        "install_skill" to ToolRiskLevel.HIGH,
        // v2.0.1: 安装市场插件 = 引入外部可执行代码,与 install_skill 同级
        "plugin_market_install" to ToolRiskLevel.HIGH,
        // v2.x: 插件生命周期变更(卸载/启停) — 与安装同口径,归 HIGH
        "plugin_market_uninstall" to ToolRiskLevel.HIGH,
        "plugin_market_set_enabled" to ToolRiskLevel.HIGH,
        // v2.x Agent 手机工具:分步控制、虚拟屏与跨应用隐私读取均为 HIGH。
        "automation_workflow" to ToolRiskLevel.HIGH,
        "virtual_screen" to ToolRiskLevel.HIGH,
        "virtual_screen_input" to ToolRiskLevel.HIGH,
        "screen_read" to ToolRiskLevel.HIGH,
        "screen_current_app" to ToolRiskLevel.HIGH,
        "screen_tap" to ToolRiskLevel.HIGH,
        "screen_tap_text" to ToolRiskLevel.HIGH,
        "screen_swipe" to ToolRiskLevel.HIGH,
        "screen_pinch" to ToolRiskLevel.HIGH,
        "screen_swipe_path" to ToolRiskLevel.HIGH,
        "screen_input" to ToolRiskLevel.HIGH,
        "screen_launch_app" to ToolRiskLevel.HIGH,
        "app_force_stop" to ToolRiskLevel.HIGH,
        "app_clear_data" to ToolRiskLevel.HIGH,
        "app_uninstall" to ToolRiskLevel.HIGH,
        // Root/Shizuku 设备操作与敏感读取必须走统一风险真源。
        "settings_get" to ToolRiskLevel.HIGH,
        "settings_put" to ToolRiskLevel.HIGH,
        "network_toggle" to ToolRiskLevel.HIGH,
        "am_start" to ToolRiskLevel.HIGH,
        "list_packages" to ToolRiskLevel.HIGH,
        "logcat_tail" to ToolRiskLevel.HIGH,
        "input_inject" to ToolRiskLevel.HIGH,
        // v2.x 自动化一期:设备命令行通道 — 可 tap/启动/改设置,归 HIGH
        "device_shell" to ToolRiskLevel.HIGH,
        // v2.x 终端一期:App 自身权限的沙盒终端 — 可增删应用文件,归 HIGH
        "terminal_exec" to ToolRiskLevel.HIGH,
        // v2.2.1: Termux 通道 — 完整 Linux 环境可执行任意命令,归 HIGH
        "termux_exec" to ToolRiskLevel.HIGH,
        // v2.2.1: GUI Agent 环 — 可连续多步操作任意 App,归 HIGH
        "ui_agent" to ToolRiskLevel.HIGH,
        // v1.x: update_skill 与 install_skill 同级 — 二者都能改写助手后续要执行的 skill 定义;
        // 内容仍经 SkillImporter 白名单/注入黑名单校验,且只允许修改用户自建 skill
        "update_skill" to ToolRiskLevel.HIGH,
        // 助手自写插件:产出会被用户签名的代码,属自我扩展的高风险动作
        "author_plugin" to ToolRiskLevel.HIGH,
        "delegate_agent" to ToolRiskLevel.HIGH,
        // v1.0.52 P2-1: subagent_run 同步阻塞式独立子 agent,可调用多个工具,潜在副作用大
        "subagent_run" to ToolRiskLevel.HIGH,
        "execute_javascript" to ToolRiskLevel.HIGH,
        "execute_node_script" to ToolRiskLevel.HIGH,
        "workspace_write" to ToolRiskLevel.HIGH,
        // v2.x: 局部替换同样是写盘，和 workspace_write 同级
        "workspace_edit" to ToolRiskLevel.HIGH,
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
        "save_memory" to ToolRiskLevel.HIGH,
        "delete_memory" to ToolRiskLevel.HIGH,
        "subagent_task" to ToolRiskLevel.HIGH,
        "make_phone_call" to ToolRiskLevel.HIGH,
        "toggle_wifi" to ToolRiskLevel.HIGH,
        "toggle_bluetooth" to ToolRiskLevel.HIGH,
        "toggle_flashlight" to ToolRiskLevel.HIGH,
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
     *            translate / quick_note_get / resource_get / workspace_list / workspace_grep
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
        "workspace_grep",
        "memory_config_get",
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
        // v2.6.10: 卡片只是展示,与 render_data 同为无副作用渲染,严格模式下也不该弹审批
        "show_card",
        // v2.6.10: 待办清单同样只是展示层,不产生任何设备副作用
        "todo_write",
        "scheduled_task_list",
        "scheduled_task_get_history",
        "translate",
        "quick_note_get",
        "resource_get",
        "workspace_list",
    )
}

/**
 * Merge the per-session "allow this tool" cache with the persisted policy without allowing
 * a temporary allow to override an explicit deny.
 */
internal fun effectivePerToolPolicy(configuredPolicy: ToolApprovalPolicy?, allowedThisSession: Boolean): ToolApprovalPolicy? =
    configuredPolicy ?: if (allowedThisSession) ToolApprovalPolicy.ALWAYS_ALLOW else null
