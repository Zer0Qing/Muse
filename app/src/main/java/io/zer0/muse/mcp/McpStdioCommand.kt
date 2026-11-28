package io.zer0.muse.mcp

import android.content.Context
import io.zer0.muse.runtime.MuseRuntime
import io.zer0.muse.runtime.RuntimeBridge
import java.io.File

/**
 * P1-A: stdio MCP server 命令解析器。
 *
 * 把用户配置的命令行（如 `npx -y @modelcontextprotocol/server-filesystem /sdcard`）
 * 转换为可直接执行的 argv：
 *  - `node` / `npm` / `npx` → 应用内置运行时（[MuseRuntime]）
 *  - 绝对路径（仅限应用私有目录：nativeLibraryDir / 运行时目录）→ 原样透传
 *  - 其他命令一律拒绝（沙盒内只允许运行内置运行时承载的 Node 系程序）
 */
object McpStdioCommand {

    sealed class Result {
        /** 可直接交给 ProcessBuilder 的 argv（argv[0] 为绝对路径）。 */
        data class Ok(val argv: List<String>) : Result()

        /** 解析失败（命令为空 / 不支持 / 越界路径）。 */
        data class Error(val message: String) : Result()
    }

    private const val GUEST_NODE = "/usr/local/bin/node"

    fun tokenize(commandLine: String): List<String> {
        val tokens = mutableListOf<String>()
        val token = StringBuilder()
        var inQuote = false
        var started = false
        var index = 0
        while (index < commandLine.length) {
            val ch = commandLine[index++]
            when {
                ch == '"' -> {
                    inQuote = !inQuote
                    started = true
                }
                inQuote && ch == '\\' && commandLine.getOrNull(index) in listOf('"', '\\') ->
                    token.append(commandLine[index++])
                ch.isWhitespace() && !inQuote -> {
                    if (started) {
                        tokens += token.toString()
                        token.clear()
                        started = false
                    }
                }
                else -> {
                    token.append(ch)
                    started = true
                }
            }
        }
        if (inQuote) return emptyList()
        if (started) tokens += token.toString()
        return tokens
    }

    fun join(command: String, args: List<String>): String = (listOf(command) + args).joinToString(" ") { part ->
        if (part.isNotEmpty() && part.none { it.isWhitespace() || it == '"' }) {
            part
        } else {
            "\"" + part.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
    }

    /**
     * 外置运行环境下的命令解析：node/npm/npx 指向 guest 内的 Node 发行版。
     *
     * 与 [resolve] 分开是为了不让旧路径判断和 guest 路径混在一起：宿主侧的
     * nativeLibraryDir 在 proot 里不可见，能用的只有 filesDir 挂载点。
     */
    fun resolveForGuest(commandLine: String, context: Context): Result {
        val tokens = tokenize(commandLine)
        if (tokens.isEmpty()) return Result.Error("命令为空")
        val head = tokens[0]
        val rest = tokens.drop(1)
        // Node 发行版自带 npm，直接用 rootfs 里的副本，不依赖 APK 的 npm 数据包。
        val npmCli = "/usr/local/lib/node_modules/npm/bin/npm-cli.js"
        val npxCli = "/usr/local/lib/node_modules/npm/bin/npx-cli.js"
        return when {
            head == "node" -> Result.Ok(listOf(GUEST_NODE) + rest)
            head == "npm" -> Result.Ok(listOf(GUEST_NODE, npmCli) + rest)
            head == "npx" -> Result.Ok(listOf(GUEST_NODE, npxCli) + rest)
            head.startsWith("/") -> {
                val root = runCatching { context.filesDir.canonicalPath }.getOrNull()
                    ?: return Result.Error("无法解析应用目录")
                val file = runCatching { File(head).canonicalPath }.getOrNull()
                    ?: return Result.Error("无法解析路径")
                if (root.isNotBlank() && file.startsWith(root) && File(file).isFile) {
                    Result.Ok(listOf(RuntimeBridge.toGuestPath(context, file)) + rest)
                } else {
                    Result.Error("仅允许应用运行时内的程序路径")
                }
            }
            else -> Result.Error(
                "不支持的命令 '$head'（当前支持 node / npm / npx 或应用运行时内的绝对路径）",
            )
        }
    }

    /** 解析命令行 → 可执行 argv。 */
    fun resolve(commandLine: String, context: Context): Result {
        val tokens = tokenize(commandLine)
        if (tokens.isEmpty()) return Result.Error("命令为空")
        val head = tokens[0]
        val rest = tokens.drop(1)
        val node = MuseRuntime.nodeBinary(context).absolutePath
        return when {
            head == "node" -> Result.Ok(listOf(node) + rest)
            head == "npm" -> Result.Ok(
                listOf(node, MuseRuntime.npmCli(context).absolutePath) + rest,
            )
            head == "npx" -> Result.Ok(
                listOf(node, File(MuseRuntime.npmDir(context), "bin/npx-cli.js").absolutePath) + rest,
            )
            head.startsWith("/") -> {
                // 绝对路径：仅允许应用私有目录内的程序（防越权执行任意系统/用户文件）
                val f = File(head)
                val allowed = runCatching {
                    f.canonicalPath.startsWith(MuseRuntime.nativeLibDir(context).canonicalPath) ||
                        f.canonicalPath.startsWith(MuseRuntime.runtimeDir(context).canonicalPath)
                }.getOrDefault(false)
                if (allowed && f.isFile) {
                    Result.Ok(tokens)
                } else {
                    Result.Error("仅允许应用运行时内的程序路径")
                }
            }
            else -> Result.Error(
                "不支持的命令 '$head'（当前支持 node / npm / npx 或应用运行时内的绝对路径）",
            )
        }
    }
}
