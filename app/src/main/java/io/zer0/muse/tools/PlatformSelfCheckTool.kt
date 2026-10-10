package io.zer0.muse.tools

import android.content.Context
import io.zer0.muse.runtime.MuseRuntime

/**
 * v2.x 扩展运行时：平台自检工具。
 *
 * 让助手能在聊天里自查"它所在的这个 App 平台"到底有哪些能力可用 ——
 * 覆盖内置 Node 运行时 + 各工具通道的授权/就绪状态,一次调用给出结构化清单。
 *
 * 用途:
 *  - 用户问"内置运行时能用吗""这些工具能不能用上"时直接调用;
 *  - 排查"某个工具调不动"时先自检,把"权限未授予"与"功能故障"区分开。
 *
 * 设计:
 *  - 只读检查,无副作用(除 Node 数据一次性解压,本就在应用初始化会做);
 *  - 通道状态来自运行环境授权提供器([ToolRegistry.permissionStatusProvider]),
 *    与工具执行前的预检同源,不会出现"自检说能用但实际调不动"的分叉;
 *  - 单点失败不影响其它项(每项独立 runCatching)。
 */
object PlatformSelfCheckTool {

    const val NAME = "platform_selfcheck"

    fun toolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME,
        description = "自检 Muse 运行平台:内置 Node 运行时状态 + 各工具通道(无障碍/Shell/Root/Termux)" +
            "的授权与就绪情况,返回结构化清单。" +
            "当用户询问「自检」「平台能力」「工具能不能用」「运行时状态」或排查工具调不动时调用。",
        parameters = emptyMap(),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    /**
     * 执行自检。
     *
     * @param context 应用上下文
     * @param permissionStatusProvider 运行环境授权状态提供器(来自 ToolRegistry,与执行预检同源)
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    suspend fun execute(context: Context, permissionStatusProvider: (() -> ToolPermissionStatus?)?): String {
        val sb = StringBuilder("【平台自检】")

        // ── 1. 内置 Node 运行时 ──────────────────────────────────────────
        sb.append("\n■ 内置 Node 运行时")
        val node = MuseRuntime.nodeBinary(context)
        sb.append("\n  - Node 二进制: ").append(
            if (node.isFile) "存在 (${node.length() / 1024 / 1024}MB)" else "缺失",
        )
        val nodeV = runCatching { MuseRuntime.execNode(context, listOf("-v"), timeoutMs = 20_000) }.getOrNull()
        sb.append("\n  - node -v: ").append(
            when {
                nodeV == null -> "执行异常"
                nodeV.exitCode == 0 -> "${nodeV.stdout} ✓"
                else -> "失败 (exit=${nodeV.exitCode})"
            },
        )
        val data = MuseRuntime.ensureData(context)
        sb.append("\n  - 运行时数据: ").append(
            if (data.isSuccess) "已就绪 (${MuseRuntime.RUNTIME_DATA_VERSION})" else "解压失败",
        )
        val npmCli = MuseRuntime.npmCli(context)
        if (npmCli.isFile) {
            val npmV = runCatching {
                MuseRuntime.execNode(context, listOf(npmCli.absolutePath, "-v"), timeoutMs = 60_000)
            }.getOrNull()
            sb.append("\n  - npm -v: ").append(
                when {
                    npmV == null -> "执行异常"
                    npmV.exitCode == 0 -> "${npmV.stdout} ✓"
                    else -> "失败 (exit=${npmV.exitCode})"
                },
            )
        } else {
            sb.append("\n  - npm: 未就绪")
        }
        sb.append("\n  - 结论: ").append(
            if (nodeV?.exitCode == 0 && MuseRuntime.isReady(context)) "运行时就绪 ✓" else "运行时不完整",
        )

        // ── 2. 工具通道授权/就绪 ─────────────────────────────────────────
        sb.append("\n■ 工具通道")
        val status = runCatching { permissionStatusProvider?.invoke() }.getOrNull()
        if (status == null) {
            sb.append("\n  - 无法获取通道状态(授权提供器未就绪)")
        } else {
            appendChannel(sb, "无障碍(读屏/点按)", status.accessibility, "开启后可执行屏幕读取与 UI 点击类工具")
            appendChannel(sb, "Shell / Root", status.shellTier, "开启后可执行命令类与系统级工具")
            appendChannel(sb, "Termux", status.termux, "开启后可执行 termux 命令通道")
        }

        // ── 3. 结论指引 ─────────────────────────────────────────────────
        sb.append("\n■ 结论")
        val allOk = status?.let { it.accessibility && it.shellTier } == true
        sb.append(
            if (allOk) {
                "\n  主要通道就绪 ✓。如某个工具仍调不动,多半是该工具本身的问题,可提供具体工具名进一步排查。"
            } else {
                "\n  部分通道未就绪 —— 未就绪的通道对应的工具会不可用,需用户到系统设置授权后才生效。" +
                    "这属于权限未授予,不是功能故障。"
            },
        )
        return sb.toString()
    }

    private fun appendChannel(sb: StringBuilder, label: String, ready: Boolean, hint: String) {
        sb.append("\n  - ").append(label).append(": ")
            .append(if (ready) "可用 ✓" else "未授予")
        if (!ready) sb.append(" —— ").append(hint)
    }
}

/**
 * v2.x 扩展运行时：平台自检工具注册器(init 块自动注册到 ToolRegistry)。
 *
 * 通道状态通过 [ToolRegistry.permissionStatusProvider] 读取 —— 与工具执行前预检同源,
 * 避免"自检说可用、实际调不动"的分叉。
 */
class PlatformSelfCheckToolRegistrar(
    private val toolRegistry: ToolRegistry,
    private val context: Context,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(PlatformSelfCheckTool.toolDef()) { _ ->
            PlatformSelfCheckTool.execute(context) {
                toolRegistry.permissionStatusProvider?.invoke()
            }
        }
    }
}
