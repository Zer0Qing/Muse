package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.muse.runtime.MuseRuntime
import io.zer0.muse.runtime.PythonRuntime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * v2.x 扩展运行时（P0 打样）：内置 Node 沙盒自检工具。
 *
 * 用户可在聊天里让助手调用本工具，验证内置运行时（libmuse_node.so + npm 数据）
 * 在真机上的可用性。除一次性数据解压外均为只读检查，无其它副作用。
 */
object RuntimeSelfCheckTool {

    const val NAME = "runtime_selfcheck"

    fun toolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME,
        description = "检查 Muse 内置 Node 运行时(应用沙盒内的运行环境)状态:node 版本、npm 版本、数据解压情况。" +
            "当用户询问「内置运行时」「运行时自检」「Node 沙盒」「MCP 运行环境」或需要排查内置运行时问题时调用。" +
            "返回: 分项检查结果文本。",
        parameters = emptyMap(),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    suspend fun execute(
        context: Context,
        /** v2.6.7: 可选注入。有则补「设备自动化三档通道」段 —— 自检要一次说完。 */
        automation: io.zer0.muse.automation.core.AutomationManager? = null,
    ): String {
        val sb = StringBuilder("【内置运行时自检】")

        val node = MuseRuntime.nodeBinary(context)
        sb.append("\n- Node 二进制: ").append(
            if (node.isFile) {
                "存在 (${node.length() / 1024 / 1024}MB)"
            } else {
                "缺失 (${MuseRuntime.nativeLibDir(context)})"
            },
        )

        val nodeV = runCatching { MuseRuntime.execNode(context, listOf("-v"), timeoutMs = 20_000) }.getOrNull()
        sb.append("\n- node -v: ").append(
            when {
                nodeV == null -> "执行异常"
                nodeV.exitCode == 0 -> "${nodeV.stdout} ✓"
                else -> {
                    val detail = if (nodeV.stderr.isNotBlank()) " ${nodeV.stderr.take(120)}" else ""
                    "失败 (exit=${nodeV.exitCode}$detail)"
                }
            },
        )

        val data = MuseRuntime.ensureData(context)
        sb.append("\n- 运行时数据: ").append(
            if (data.isSuccess) {
                "已就绪 (${MuseRuntime.RUNTIME_DATA_VERSION})"
            } else {
                "解压失败 (${data.exceptionOrNull()?.message})"
            },
        )

        val npmCli = MuseRuntime.npmCli(context)
        if (npmCli.isFile) {
            val npmV = runCatching {
                MuseRuntime.execNode(context, listOf(npmCli.absolutePath, "-v"), timeoutMs = 60_000)
            }.getOrNull()
            sb.append("\n- npm -v: ").append(
                when {
                    npmV == null -> "执行异常"
                    npmV.exitCode == 0 -> "${npmV.stdout} ✓"
                    else -> "失败 (exit=${npmV.exitCode})"
                },
            )
        } else {
            sb.append("\n- npm: 未就绪")
        }

        sb.append(buildCpuSection(probeCpu(context)))

        sb.append("\n- 结论: ").append(
            if (nodeV?.exitCode == 0 && MuseRuntime.isReady(context)) {
                "运行时就绪 ✓"
            } else {
                "运行时不完整，详见上方明细"
            },
        )
        sb.append("\n\n")
        appendPythonSection(context, sb)
        // v2.6.7: 补三档通道 —— 助手自审：「runtime_selfcheck 只报了 Node/Python，
        // 没有报 UI 自动化三通道，自检不自检，得调两次」。现在一次报全。
        automation?.let { appendChannelSection(it, sb) }
        return sb.toString()
    }

    /**
     * CPU 探测：内置 Node 的 `os.cpus()` / `os.availableParallelism()` 是否取到核数。
     *
     * 沙盒环境确实可能拿不到（libuv 读不到 CPU 列表），所以只报 warn，不参与整体结论。
     */
    private suspend fun probeCpu(context: Context): CpuProbe? {
        val r = runCatching { MuseRuntime.execNode(context, listOf("-e", CPU_PROBE_SCRIPT), timeoutMs = 20_000) }.getOrNull()
        if (r == null || r.exitCode != 0) return null
        val line = r.stdout.lineSequence().lastOrNull { it.trim().startsWith("{") }?.trim() ?: return null
        return runCatching {
            val obj = AppJson.parseToJsonElement(line) as? JsonObject ?: return@runCatching null
            CpuProbe(
                entries = (obj["cpus"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                parallelism = (obj["ap"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                envCount = (obj["env"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
            )
        }.getOrNull()
    }

    /** 渲染 CPU 探测项（纯函数，便于单测）。取不到值时报 warn，不影响整体结论。 */
    internal fun buildCpuSection(probe: CpuProbe?): String {
        val prefix = "\n- CPU 探测: "
        if (probe == null) return prefix + "warn（探测未执行成功；沙盒里取不到核数不影响整体结论）"
        val entries = probe.entries ?: 0
        val parallelism = probe.parallelism ?: 0
        val detail =
            "os.cpus().length=$entries, os.availableParallelism()=$parallelism, " +
                "MUSE_CPU_COUNT=${probe.envCount ?: "未注入"}"
        return if (entries > 0 && parallelism > 0) {
            prefix + detail + " ✓"
        } else {
            prefix + detail + " warn（取不到核数；依赖 CPU 数的脚本会退化，但不影响整体结论）"
        }
    }

    /** 内置 Node 侧 CPU 探测结果（字段缺失时为 null）。 */
    internal data class CpuProbe(val entries: Int?, val parallelism: Int?, val envCount: Int?)

    private const val CPU_PROBE_SCRIPT =
        "const os=require('node:os');" +
            "console.log(JSON.stringify({cpus:os.cpus().length," +
            "ap:os.availableParallelism(),env:process.env.MUSE_CPU_COUNT||null}))"

    /** 设备控制通道段（无障碍 / Shizuku / Root + 单项运行时权限提示）。 */
    private suspend fun appendChannelSection(automation: io.zer0.muse.automation.core.AutomationManager, sb: StringBuilder) {
        val state = automation.refreshPermissions()
        sb.append("\n\n【设备控制通道自检】")
        sb.append("\n- 无障碍: ").append(if (state.accessibilityEnabled) "已开启 ✓" else "未开启")
        sb.append("\n- Shizuku/ADB: ").append(
            if (state.shellEnabled) "已就绪 ✓" else "不可用（${state.shizukuMessage}）",
        )
        sb.append("\n- Root: ").append(if (state.rootEnabled) "已获取 ✓" else "未获取")
        sb.append("\n- 最高可用层级: ").append(state.highestLevelName())
        sb.append("\n- 结论: ").append(
            if (state.anyEnabled) {
                "可执行设备操作；具体哪些工具可用请调 tool_status"
            } else {
                "无任何设备控制通道，屏幕/应用/shell 类工具均不可用；只能做应用内操作"
            },
        )
    }

    /** v2.7: 追加内置 Python 运行时自检段（拆出以控制单函数长度与复杂度）。 */
    private suspend fun appendPythonSection(context: Context, sb: StringBuilder) {
        sb.append("【内置 Python 运行时自检】")
        val pyBin = PythonRuntime.pythonBinary(context)
        sb.append("\n- Python stub: ").append(
            if (pyBin.isFile) "存在 (${pyBin.length() / 1024}KB)" else "缺失 (${PythonRuntime.nativeLibDir(context)})",
        )
        val pyV = runCatching { PythonRuntime.execPython(context, listOf("-V"), timeoutMs = 30_000) }.getOrNull()
        sb.append("\n- python -V: ").append(
            when {
                pyV == null -> "执行异常"
                pyV.exitCode == 0 -> "${(pyV.stdout + pyV.stderr).trim()} ✓"
                else -> "失败 (exit=${pyV.exitCode} ${pyV.stderr.take(120)})"
            },
        )
        val pyData = PythonRuntime.ensureData(context)
        sb.append("\n- Python 数据: ").append(
            if (pyData.isSuccess) "已就绪 (${PythonRuntime.PYTHON_DATA_VERSION})" else "解压失败 (${pyData.exceptionOrNull()?.message})",
        )
        if (pyV?.exitCode == 0 && pyData.isSuccess) {
            val probe = runCatching {
                PythonRuntime.execPython(
                    context,
                    listOf("-c", "import ssl,sqlite3,zlib,json;print('ext-ok')"),
                    timeoutMs = 30_000,
                )
            }.getOrNull()
            sb.append("\n- 标准库扩展: ").append(
                if (probe?.exitCode == 0 && probe.stdout.contains("ext-ok")) "ssl/sqlite3/zlib/json ✓" else "加载异常",
            )
        }
        sb.append("\n- Python 结论: ").append(
            if (pyV?.exitCode == 0 && PythonRuntime.isReady(context)) "运行时就绪 ✓" else "运行时不完整，详见上方明细",
        )
    }
}

/**
 * v2.x 扩展运行时（P0）：自检工具注册器（init 块自动注册到 ToolRegistry）。
 */
class RuntimeSelfCheckToolRegistrar(
    private val toolRegistry: ToolRegistry,
    private val context: Context,
    /** v2.6.7: 注入后自检会一并上报三档通道状态。 */
    private val automationManager: io.zer0.muse.automation.core.AutomationManager? = null,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(RuntimeSelfCheckTool.toolDef()) { _ ->
            RuntimeSelfCheckTool.execute(context, automationManager)
        }
    }
}
