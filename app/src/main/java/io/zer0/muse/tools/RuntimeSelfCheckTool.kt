package io.zer0.muse.tools

import android.content.Context
import io.zer0.muse.runtime.MuseRuntime
import io.zer0.muse.runtime.PythonRuntime

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

    suspend fun execute(context: Context): String {
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

        sb.append("\n- 结论: ").append(
            if (nodeV?.exitCode == 0 && MuseRuntime.isReady(context)) {
                "运行时就绪 ✓"
            } else {
                "运行时不完整，详见上方明细"
            },
        )
        sb.append("\n\n")
        appendPythonSection(context, sb)
        return sb.toString()
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
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(RuntimeSelfCheckTool.toolDef()) { _ ->
            RuntimeSelfCheckTool.execute(context)
        }
    }
}
