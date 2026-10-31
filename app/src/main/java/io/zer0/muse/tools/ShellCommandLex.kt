package io.zer0.muse.tools

import java.io.File

/**
 * 命令边界用到的词法层:把一段 shell 字符串切成可判定的单元。
 *
 *  - [ShellSegment]:按 shell 控制符(`|` `||` `&&` `;` `&` 换行)切出的一段命令,
 *    带取「一级命令名 / 参数 / 像路径的参数」的小工具。
 *  - [ShellLocator]:把一个路径参数放到工作区基准下判落点(工作区内 / 工作区根 /
 *    `/data` 应用私有数据 / 系统分区 / 存储卷 / 设备根 / 工作区外 / 判不出)。
 *  - [ShellCommandLex]:参数形状的判断(递归开关、世界可写模式、无害汇聚点)。
 *
 * 这里刻意**不**做 shell 语义解析:只做分段与参数级匹配。破坏力判据本身在
 * [ShellCommandBoundary],那边按「一条命令能毁掉什么」组织,这边只回答「它碰到哪」。
 */

/** 一个路径参数能碰到什么。 */
internal enum class ShellScope {
    /** 严格落在工作区内部:工具的正常作用域。 */
    WORKSPACE,

    /** 工作区根本身。 */
    WORKSPACE_ROOT,

    /** `/data` 下工作区以外的应用私有数据(含其它应用)。 */
    APP_DATA,

    /** 系统分区与内核接口。 */
    SYSTEM,

    /** 用户可见存储卷。 */
    STORAGE,

    /** 设备根 `/`。 */
    DEVICE_ROOT,

    /** `/dev/null` 这类无害汇聚点。 */
    SINK,

    /** 工作区以外的其它路径。 */
    OUTSIDE,

    /** 判不出落点(没有工作目录基准的相对路径)。 */
    UNKNOWN,
}

/** 路径参数的落点,以及它打到的是「整个目录」还是「目录里的一个子集」。 */
internal data class ShellTarget(val scope: ShellScope, val wholeDirectory: Boolean)

/**
 * 一段命令:它的分隔符与切好的 token。
 * `|` 段是「被上一段输出喂」的命令;`&&`/`||`/`;` 段各自是独立命令。
 */
internal class ShellSegment(val separator: String, val tokens: List<String>) {

    /** 上游通过管道把输出喂给本段。 */
    val isPipeInput: Boolean get() = separator == "|"

    /** 一级命令下标:跳过 `FOO=bar` 环境变量前缀与 `nohup`/`timeout` 这类包装命令。 */
    private fun verbIndex(): Int? = tokens.indexOfFirst { token ->
        !ENV_ASSIGNMENT.matches(token) && bareName(token) !in COMMAND_WRAPPERS
    }.takeIf { it >= 0 }

    /** 一级命令名(去掉路径前缀、转小写)。 */
    fun verb(): String? = verbIndex()?.let { bareName(tokens[it]) }

    /** 一级命令之后的参数。 */
    fun args(): List<String> = verbIndex()?.let { tokens.drop(it + 1) } ?: emptyList()

    /** 参数里「像路径」的 token:排除选项与 `of=x` 这类操作数。 */
    fun pathArgs(): List<String> = args().filterNot { it.startsWith("-") || OPERAND_ASSIGNMENT.matches(it) }

    private fun bareName(token: String) = token.substringAfterLast('/').lowercase()

    companion object {
        private val SEGMENT_SEPARATOR = Regex("""\|\||&&|[;|&\r\n]""")
        private val WHITESPACE = Regex("\\s+")

        /** `FOO=bar rm -rf x` 里的 `FOO=bar` 是环境变量赋值,不是命令。 */
        private val ENV_ASSIGNMENT = Regex("""[A-Za-z_][A-Za-z0-9_]*=.*""")

        /** `dd of=x` 这类 `key=value` 操作数不是位置参数,不当路径看。 */
        private val OPERAND_ASSIGNMENT = Regex("""[a-z]{1,4}=.*""")

        /** 包装命令:真正的一级命令在它后面,取命令名要跳过它们。 */
        private val COMMAND_WRAPPERS = setOf(
            "env", "nohup", "time", "timeout", "nice", "ionice", "xargs", "watch", "command", "exec",
            "busybox", "toybox",
        )

        fun split(command: String): List<ShellSegment> {
            val result = mutableListOf<ShellSegment>()
            var cursor = 0
            var separator = ""
            for (match in SEGMENT_SEPARATOR.findAll(command)) {
                append(result, separator, command.substring(cursor, match.range.first))
                separator = match.value
                cursor = match.range.last + 1
            }
            append(result, separator, command.substring(cursor))
            return result
        }

        private fun append(into: MutableList<ShellSegment>, separator: String, text: String) {
            val tokens = tokenize(text)
            if (tokens.isNotEmpty()) into += ShellSegment(separator, tokens)
        }

        private fun tokenize(text: String): List<String> =
            text.trim().split(WHITESPACE).filter { it.isNotEmpty() }.map(ShellCommandLex::stripQuotes)
    }
}

/**
 * 路径落点判定器:以工具的工作区目录为基准,把命令参数里的路径归类。
 *
 * 设备上的工作区是 `/data/user/0/<pkg>/files/workspace`,而 `/data/data/<pkg>` 是同一份数据的
 * 软链写法,所以绝对路径要按「原样」与「解软链后」两种写法一起比 —— 否则
 * `cat x > /data/data/<pkg>/files/workspace/out.txt` 这种工作区内的正常写法会被误判成越界。
 *
 * @param workspaceDir 工具的工作目录;为 null 时相对路径判不出落点,一律不参与拦截
 */
internal class ShellLocator(private val workspaceDir: File?) {

    private val workspacePosix: String? = workspaceDir?.let { dir ->
        toPosix(runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath))
    }

    /** 把一个路径参数放到工作区基准下判落点。 */
    fun target(raw: String): ShellTarget {
        val trimmed = ShellCommandLex.stripQuotes(raw).trim()
        if (trimmed.isEmpty()) return ShellTarget(ShellScope.UNKNOWN, wholeDirectory = true)
        val path = expandHome(trimmed).removeSurrounding("$(", ")").removeSurrounding("(", ")")
        val lastSegment = path.substringAfterLast('/')
        val globbed = lastSegment.isNotEmpty() && lastSegment.all { it == '*' || it == '?' }
        // `*` 打到目录下全部内容,`*.log` 只打到一个子集 —— 破坏力不同级
        val container = if (globbed) path.substringBeforeLast('/', "").ifEmpty { "." } else path
        return ShellTarget(classify(absoluteize(normalizeTrailing(container))), wholeDirectory = !globbed || lastSegment == "*")
    }

    private fun classify(path: String?): ShellScope = when {
        path == null -> ShellScope.UNKNOWN
        path == "/" -> ShellScope.DEVICE_ROOT
        // 工作区判定必须前置于 /data:工作区本身就挂在 /data 下
        resolvesToWorkspaceRoot(path) -> ShellScope.WORKSPACE_ROOT
        resolvesInsideWorkspace(path) -> ShellScope.WORKSPACE
        ShellCommandLex.isHarmlessSink(path) -> ShellScope.SINK
        SYSTEM_PATHS.any { path == it || path.startsWith("$it/") } -> ShellScope.SYSTEM
        path == "/data" || path.startsWith("/data/") -> ShellScope.APP_DATA
        STORAGE_PATHS.any { path == it || path.startsWith("$it/") } -> ShellScope.STORAGE
        else -> ShellScope.OUTSIDE
    }

    private fun resolvesToWorkspaceRoot(path: String) =
        path == workspacePosix || variants(path).any { it == workspacePosix }

    private fun resolvesInsideWorkspace(path: String) =
        insideWorkspace(path) || variants(path).any { insideWorkspace(it) }

    private fun insideWorkspace(absolute: String) =
        workspacePosix != null && absolute != workspacePosix && absolute.startsWith("$workspacePosix/")

    /** 同一个绝对路径的其它写法(解软链);判不出更深的写法时按原样。 */
    private fun variants(path: String): List<String> {
        if (!path.startsWith("/")) return emptyList()
        val canonical = runCatching { toPosix(File(path).canonicalPath) }.getOrDefault(path)
        return if (canonical == path) emptyList() else listOf(canonical)
    }

    /**
     * 相对路径按工作区解析成绝对路径;没有工作区基准时返回 null ——
     * 判不出落点就不参与拦截,避免凭空误杀。
     */
    private fun absoluteize(path: String): String? {
        if (path.startsWith("/")) return path
        if (workspaceDir == null) return null
        return runCatching { File(workspaceDir, path).canonicalPath }.getOrNull()?.let(::toPosix)
    }

    /** Android 应用进程里 `~`/`$HOME` 展开到设备根,不能当成「用户自己的小窝」。 */
    private fun expandHome(path: String): String {
        val forms = listOf("~", "\$HOME", "\${HOME}")
        if (path in forms) return "/"
        val prefix = forms.firstOrNull { path.startsWith("$it/") } ?: return path
        return path.removePrefix(prefix)
    }

    private fun normalizeTrailing(path: String) = path.trimEnd('/').ifEmpty { "/" }

    private fun toPosix(path: String) = path.replace('\\', '/')

    companion object {
        /**
         * 会被这些前缀碰到的路径:整机只读分区或内核接口,终端的正常用途碰不到。
         * `/dev/block` `/dev/disk` 单列 —— `/dev/null` 这类无害节点不在此列。
         */
        private val SYSTEM_PATHS = listOf(
            "/system", "/vendor", "/product", "/apex", "/boot", "/recovery", "/sbin",
            "/proc", "/sys", "/etc", "/linkerconfig", "/dev/block", "/dev/disk",
        )

        private val STORAGE_PATHS = listOf("/sdcard", "/storage", "/mnt", "/oem", "/data/media")
    }
}

/** 参数形状的判断:与落点无关,只看这个 token / 这串参数本身说明了什么操作。 */
internal object ShellCommandLex {

    private val HARMLESS_SINKS = listOf("/dev/null", "/dev/zero", "/dev/stdout", "/dev/stderr", "/dev/tty")

    private val WORLD_WRITABLE_LAST_DIGITS = charArrayOf('6', '7')

    /** 显式声明「stdin 就是我的命令来源」的参数。 */
    private val STDIN_CODE_ARGS = setOf("-", "-s", "--stdin")

    fun isHarmlessSink(path: String) = HARMLESS_SINKS.any { path == it || path.startsWith("$it/") }

    /** `-r`/`-R`/`-rf`/`--recursive`:递归开关(组合短选项逐字符看)。 */
    fun isRecursiveFlag(token: String) =
        token == "--recursive" ||
            (token.startsWith("-") && !token.startsWith("--") && token.any { it == 'r' || it == 'R' })

    /** 世界可写的模式:`777`/`776`/`o+w`/`a+w` 这类放开了其它用户写权限的写法。 */
    fun isWorldWritableMode(token: String): Boolean {
        if (token.startsWith("-")) return false
        if (token.contains("+w") && (token.startsWith("a") || token.startsWith("o") || token == "+w")) return true
        val octal = token.filter { it.isDigit() }
        return octal.length == 3 && octal.last() in WORLD_WRITABLE_LAST_DIGITS
    }

    fun stripQuotes(token: String) = token.removeSurrounding("\"").removeSurrounding("'")

    /**
     * 管道上游是不是被当成**命令码**读:
     *  - 解释器没有脚本参数,或参数是 `-`/`-s`/`--stdin` → stdin 是命令来源
     *  - shell 只要不带 `-c` 就从 stdin 读命令
     * `… | python3 -m json.tool`、`… | python3 fmt.py`、`… | sh -c 'echo hi'` 都不算。
     */
    fun stdinIsCommandSource(args: List<String>, isShell: Boolean) = when {
        args.isEmpty() || args.first() in STDIN_CODE_ARGS -> true
        isShell -> args.none { it == "-c" }
        else -> false
    }

    /** 参数里是不是在拉远程内容(`eval "$(curl …)"` 这类判据用)。 */
    fun loadsRemoteContent(token: String) =
        token.contains("curl") || token.contains("wget") || token.contains("://")
}
