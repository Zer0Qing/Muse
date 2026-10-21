package io.zer0.muse.automation.capability

import android.os.Build
import io.zer0.muse.automation.core.PermissionLevel

/**
 * 设备能力档案 —— 「这台手机上 Agent 到底能做到什么」的唯一事实来源。
 *
 * ## 为什么需要它
 *
 * 三层控制通道（无障碍 / Shizuku·adb / Root）各自只覆盖一部分能力，且高度依赖用户
 * 授权状态。此前模型只能靠试错：先调一次工具、失败了再换一个，既浪费轮次，也容易把
 * "没有权限"误报成"功能不存在"。
 *
 * 档案把可用通道 + 关键能力边界一次性交给模型，让它在第一步就能选出可行方案。
 *
 * ## 能力边界（与实现对齐，勿凭印象改）
 *
 * - **无障碍**：读控件树 + dispatchGesture 手势注入 + `inputText`/`pressKey`。
 *   **不能**截屏，**不能**操作系统界面（状态栏/通知栏/关机菜单等 system uid 窗口），
 *   **不能**安装应用或改系统设置。
 * - **Shizuku / adb shell**：`input`、`screencap`、`pm`/`am`、`settings`、`content` 等
 *   shell 命令；可截屏、可操作系统界面、可静默安装。
 * - **Root**：以上全部 + 直接读写系统分区/数据库。
 * - **虚拟屏**：把应用启动到独立 display 上，不干扰用户当前前台（多步骤自动化首选）。
 * - **内置 Node**：数据处理/批处理/脚本/MCP stdio server，不消耗模型 token。
 *
 * @property accessibility 无障碍服务是否已连接
 * @property shizuku 是否已获得 shell 级（Shizuku/adb）授权
 * @property root 是否已获得 su
 * @property virtualDisplay 虚拟屏服务端是否可用
 * @property nodeRuntime 内置 Node 运行时是否就绪
 * @property sandboxAvailable 预留：应用内沙盒文件执行是否可用
 * @property details 面向日志/设置页的可读明细（不直接进提示词）
 */
data class DeviceCapabilityProfile(
    val accessibility: Boolean = false,
    val shizuku: Boolean = false,
    val root: Boolean = false,
    val virtualDisplay: Boolean = false,
    val nodeRuntime: Boolean = false,
    val sandboxAvailable: Boolean = false,
    val details: Map<String, String> = emptyMap(),
) {
    /** 最高可用层级（无任何通道时为 [PermissionLevel.NONE]）。 */
    val highestLevel: PermissionLevel
        get() = when {
            root -> PermissionLevel.ROOT
            shizuku -> PermissionLevel.SHELL
            accessibility -> PermissionLevel.ACCESSIBILITY
            else -> PermissionLevel.NONE
        }

    /** 是否存在任何能"操作手机"的通道（Node/虚拟屏单独不算控制通道）。 */
    val hasControlChannel: Boolean
        get() = accessibility || shizuku || root

    /** Android 14+ 无障碍服务可直接调用 AccessibilityService.takeScreenshot。 */
    val accessibilityScreenshot: Boolean
        get() = accessibility && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** 能否截屏（Shell/Root 或 Android 14+ 无障碍均可）。 */
    val canScreenshot: Boolean
        get() = shizuku || root || accessibilityScreenshot

    /**
     * 渲染成给模型看的紧凑档案块。
     *
     * 保持短小：进入 system prompt 的每一行都在花用户的钱，只写"影响决策"的事实。
     */
    fun renderForModel(): String = buildString {
        appendLine("## 设备能力档案（当前真实可用性）")
        appendLine(
            "- 无障碍(读屏/手势/输入): " +
                if (accessibility) "可用" else "不可用（未开启 Muse 无障碍服务）",
        )
        appendLine(
            "- Shell 通道(Shizuku/adb: input/screencap/pm/am/settings): " +
                if (shizuku) "可用" else "不可用（未安装 Shizuku 或未授权）",
        )
        appendLine(
            "- Root(su: 系统分区读写/完整控制): " +
                if (root) "可用" else "不可用",
        )
        appendLine(
            "- 截屏能力: " +
                if (canScreenshot) {
                    if (accessibilityScreenshot && !shizuku && !root) {
                        "可用（走 Android 14+ 无障碍截图）"
                    } else {
                        "可用（走 Shell/Root）"
                    }
                } else {
                    "不可用（需 Android 14+ 无障碍、Shizuku/adb 或 Root）"
                },
        )
        appendLine(
            "- 虚拟屏(把应用启动到独立屏幕,不打扰用户当前界面): " +
                if (virtualDisplay) "可用" else "不可用",
        )
        appendLine(
            "- 内置 Node 运行时(跑脚本/数据处理/MCP stdio): " +
                if (nodeRuntime) "可用" else "不可用（运行时未就绪）",
        )
        append(
            "- 当前最高权限层: " + when (highestLevel) {
                PermissionLevel.ROOT -> "Root"
                PermissionLevel.SHELL -> "Shell(Shizuku/adb)"
                PermissionLevel.ACCESSIBILITY -> "无障碍"
                PermissionLevel.NONE -> "无（只能做应用内操作，无法控制其他应用）"
            },
        )
    }

    /**
     * 渲染操作决策规程：把"先探测、再选层、后验证"的既定做法写进提示词。
     *
     * 这些不是建议而是硬约束（每条都对应一次真实踩坑），因此与能力档案一起注入。
     */
    fun renderOperatingGuide(): String = buildString {
        appendLine("## 操作手机的决策规程")
        appendLine("1. 动手前先确认能力：上表列出的是**当前**真实可用性；表里写「不可用」的通道不要反复重试，直接换方案或向用户说明缺哪一项权限。")
        appendLine("2. 按最低够用层级选通道：")
        appendLine("   - 应用层（**不需要任何自动化授权**，永远先试这一层）：`app_list` 查名称与包名、`app_launch` 启动应用、`app_settings` 打开某个应用的系统设置页（可让用户自己改权限/清数据）。")
        appendLine("   - 界面交互：优先无障碍（可读控件树、可注入手势），比坐标盲点可靠。")
        appendLine("   - 需要截屏、系统界面、安装应用、改系统设置或强停/清数据/卸载时，才用 Shell/Root（`app_force_stop`/`app_clear_data`/`app_uninstall`/`device_shell`）。")
        appendLine("3. 每步都要验证：动作发出后重新读一次屏幕/状态确认结果（文字是否变化、页面是否切换），不要连发多个盲操作。")
        appendLine("4. 界面失败先看结构再换手段：先用读屏结果定位控件（文本/描述/层级）；找不到再考虑坐标点击；坐标也不稳时改用 Shell/Root 的命令级方案。")
        appendLine("5. 重复性/长流程优先写成脚本：用内置 Node 完成循环、JSON/正则处理、批量请求，比逐步推理省时省 token；稳定后沉淀为技能。")
        appendLine(
            "6. 不打扰用户：调用 `ui_agent` 时优先用 display=auto 并提供 package_name；" +
                "它会尝试创建本次专属虚拟屏，Shell/Root 或 App 不支持时明确降级到前台。" +
                "display=virtual 仅在能力档案显示虚拟屏可用时指定；不要假设兼容虚拟屏与 Agent 独立屏是同一块屏。",
        )
        appendLine("7. 高风险动作（发消息、支付、删除、改系统设置）先说明将要做什么，待用户确认后再执行。")
        appendLine("8. 缺权限就如实说：说明「需要哪一项」（如 Shizuku/Root/无障碍），不要用模拟结果代替真实执行，也不要谎报成功。")
        appendLine(
            "9. 动手前先查地图：要操作设备（点击/滑屏/读屏/启停应用/shell）之前，先调 `tool_status`\n" +
                "   看哪些真可用。它会把工具分成可用与因权限受阻两组，并告诉你缺什么。\n" +
                "   不要先答应「我帮你点一下」再去撞墙 —— 那比一开始就说做不了更伤。",
        )
    }

    companion object {
        /**
         * 从三层授权标志构造（最常用入口）。
         *
         * 参数多于 detekt 默认阈值（6）是刻意的：这些标志彼此独立、都要显式给出，
         * 用默认值会让"忘了传某一层"变成静默的假能力声明。调用方一律具名传参。
         */
        @Suppress("LongParameterList")
        fun fromFlags(
            accessibility: Boolean,
            shizuku: Boolean,
            root: Boolean,
            virtualDisplay: Boolean = false,
            nodeRuntime: Boolean = false,
            sandboxAvailable: Boolean = false,
            details: Map<String, String> = emptyMap(),
        ): DeviceCapabilityProfile = DeviceCapabilityProfile(
            accessibility = accessibility,
            shizuku = shizuku,
            root = root,
            virtualDisplay = virtualDisplay,
            nodeRuntime = nodeRuntime,
            sandboxAvailable = sandboxAvailable,
            details = details,
        )
    }
}
