package io.zer0.muse.tools

import java.io.File

/**
 * 通用 Shell 工具(命令是一段任意 shell 字符串,如 terminal_exec)使用的两档命令边界。
 *
 * ## 为什么不是直接套用 [ToolPermissionResolver.isUnsafeCommand]
 * `execute_shell` 是**只读查询**工具:17 条命令白名单、禁管道禁重定向,所以 [isUnsafeCommand]
 * 把 `` ` `` `$` `|` `;` `&` `<` `>` `*` `?` `{}` 整个语法集连同 rm/git/curl/python/chmod
 * 等可执行文件名一律判为不安全 —— 放在只读沙箱上不会误杀。
 * `terminal_exec` 是**通用终端**:管道切日志、重定向存输出、变量传参、子目录递归删除
 * 都是它的正常工作,把那套判据原样搬过来它基本就没法用了。
 * 所以这里按「真实破坏力」而不是「字符串形状」重新分档。
 *
 * ## 两档
 *  - [forbidReason] —— 绝对不许出现的最小集合。terminal_exec 直接拒绝执行,
 *    TRUSTED 模式与「始终允许该工具」都绕不过:它是白名单/审批层出 bug 时的地板。
 *  - [cautionReason] —— 有破坏力但很可能是正常工作(删工作区内的文件、改权限、下载、
 *    `git push --force`、杀进程、设备状态命令)。terminal_exec **不拦**这一档,
 *    交给既有审批链:terminal_exec 本身是 HIGH 风险,ASK/STRICT 模式下逐次弹卡,
 *    用户在卡上能看到命令原文。本层只额外记一条警告日志,便于事后回溯。
 *
 * ## 第一档是最小集,不是完备集
 * 只抓「一条命令就能毁掉一片数据」的形态:设备根与应用私有数据/系统分区上的删除与写入、
 * 格式化与块设备写入、越界的递归改权限、下载即执行(`curl … | sh`)、
 * 提权与设备级命令、杀 init / fork 炸弹。明确**不做**的事:
 *  - 不穷举破坏性命令。`mv` 挪走目录、`tar` 覆盖、`truncate` 清空、`dd` 写满磁盘这些
 *    无数变种不在这里;它们要么落在第二档,要么由审批链承担。
 *  - 不禁「先下载、再执行本地脚本」(`curl … -o i.sh && sh i.sh`)。它与 `curl … | sh`
 *    破坏力同型,但禁止它就等于禁止一切脚本执行(`sh build.sh` 是本工具的正当用途);
 *    这一类只在第二档标出来,由审批卡上的命令原文承担。
 *  - 不解析 shell 语义。命令替换的碎片拼接、base64 解码后再执行,本边界看不见
 *    (它只做分段与参数级匹配)。它拦的是**模型正常生成的破坏性命令**,
 *    不是攻击者精心构造的字符串 —— 后者要靠审批卡上让用户读到的命令原文。
 *  - 不替代工具沙盒。terminal_exec 以 App 自身权限运行,绝大多数系统路径本来就写不进去;
 *    这里拦的是「即使权限层出 bug 也不该发生」的意图。
 *
 * 分段、落点这些读法在 [ShellSegment] / [ShellLocator] / [ShellCommandLex] 里。
 */
internal object ShellCommandBoundary {

    /**
     * 第一档:命中即拒绝执行。
     *
     * @param command 待执行的 shell 命令原文
     * @param workspaceDir 工具的工作目录(terminal_exec 的应用工作区)。相对路径按它解析成
     *   绝对落点,才能区分「工作区内的正常清理」与「越界删除」。传 null 时相对路径判不出
     *   落点,不参与第一档拦截。
     * @return 命中规则时返回可直接讲给用户的原因;未命中返回 null
     */
    fun forbidReason(command: String, workspaceDir: File? = null): String? {
        if (command.isBlank()) return null
        if (command.replace(FORK_BOMB_SPACES, "").contains(FORK_BOMB_SHAPE)) return FORK_BOMB_REASON
        val locator = ShellLocator(workspaceDir)
        for (segment in ShellSegment.split(command)) {
            val hit = deleteReason(segment, locator)
                ?: writeReason(segment, locator)
                ?: permissionReason(segment, locator)
                ?: formatReason(segment)
                ?: deviceReason(segment)
                ?: remoteCodeReason(segment)
                ?: killReason(segment)
            if (hit != null) return hit
        }
        return redirectReason(command, locator)
    }

    /**
     * 第二档:有破坏力但很可能就是正常工作 —— **不拦**,由既有审批链(HIGH 风险逐次确认)
     * 负责,调用方最多记一条日志。
     *
     * @return 破坏性的描述;命令没有破坏力时返回 null
     */
    fun cautionReason(command: String): String? =
        ShellSegment.split(command).firstNotNullOfOrNull(::cautionForSegment)

    private fun cautionForSegment(segment: ShellSegment): String? {
        val verb = segment.verb() ?: return null
        if (verb == "find" && segment.tokens.contains("-delete")) return "find -delete 批量删除"
        if (verb == "git" && segment.args().any { it in GIT_DESTRUCTIVE_ARGS }) return "git 破坏性子命令(reset/clean/强推)"
        return CAUTION_NOTES[verb]
    }

    // ── 第一档具体规则 ────────────────────────────────────────────────────

    /** 删除:落点越界(设备根/系统分区/应用私有数据)一律拦;递归删除还得覆盖到工作区外才拦。 */
    private fun deleteReason(segment: ShellSegment, locator: ShellLocator): String? {
        val verb = segment.verb() ?: return null
        if (verb == "find") return findDeleteReason(segment, locator)
        if (verb !in DELETE_VERBS) return null
        val recursive = segment.args().any(ShellCommandLex::isRecursiveFlag)
        return segment.pathArgs().firstNotNullOfOrNull { raw ->
            deleteTargetReason(raw, locator.target(raw), recursive)
        }
    }

    private fun deleteTargetReason(raw: String, target: ShellTarget, recursive: Boolean): String? =
        unacceptable("删除", target, raw) ?: when {
            !recursive -> null
            target.scope == ShellScope.WORKSPACE_ROOT && target.wholeDirectory -> "递归删除整个应用工作区:$raw"
            target.scope == ShellScope.STORAGE || target.scope == ShellScope.OUTSIDE -> "递归删除指向工作区之外的路径:$raw"
            else -> null
        }

    /** `find <根> … -delete`:扫描根越出工作区就拦;工作区内按谓词删除属于第二档。 */
    private fun findDeleteReason(segment: ShellSegment, locator: ShellLocator): String? {
        if (!segment.tokens.contains("-delete")) return null
        val root = segment.pathArgs().firstOrNull() ?: return null
        val target = locator.target(root)
        unacceptable("find -delete 的扫描根", target, root)?.let { return it }
        return when (target.scope) {
            ShellScope.STORAGE, ShellScope.OUTSIDE -> "find -delete 的扫描根在工作区之外:$root"
            else -> null
        }
    }

    /** 显式写入目标:`dd of=`、`tee`、`cp/mv/install/ln` 的落点、`truncate`。 */
    private fun writeReason(segment: ShellSegment, locator: ShellLocator): String? {
        val destinations = when (segment.verb()) {
            "dd" -> segment.args().filter { it.startsWith("of=") }.map { it.removePrefix("of=") }
            "tee" -> segment.args().filterNot { it.startsWith("-") }
            "cp", "mv", "install", "ln", "truncate" -> listOfNotNull(segment.pathArgs().lastOrNull())
            else -> emptyList()
        }
        return destinations.firstNotNullOfOrNull { raw -> unacceptable("写入", locator.target(raw), raw) }
    }

    /**
     * 改权限/属主。设备根与系统分区上的 `chmod`/`chown` 一律拦(`chmod 777 /` 这类);
     * 工作区内的改权限是准备脚本的正常过程,不拦 —— 递归放开工作区外的写权限才拦。
     */
    private fun permissionReason(segment: ShellSegment, locator: ShellLocator): String? {
        val verb = segment.verb() ?: return null
        if (verb !in PERMISSION_VERBS) return null
        val args = segment.args()
        val recursive = args.any(ShellCommandLex::isRecursiveFlag)
        val worldWritable = args.any(ShellCommandLex::isWorldWritableMode)
        return segment.pathArgs().firstNotNullOfOrNull { raw ->
            permissionTargetReason(verb, locator.target(raw).scope, recursive, worldWritable, raw)
        }
    }

    private fun permissionTargetReason(
        verb: String,
        scope: ShellScope,
        recursive: Boolean,
        worldWritable: Boolean,
        raw: String,
    ): String? = when {
        scope == ShellScope.DEVICE_ROOT -> "$verb 作用在设备根上:$raw"
        scope == ShellScope.SYSTEM -> "$verb 作用在系统分区上:$raw"
        scope == ShellScope.APP_DATA -> "$verb 作用在 /data 下工作区以外的应用私有数据上:$raw"
        recursive && worldWritable && scope in OUT_OF_SCOPE_STORAGE -> {
            "递归放开工作区外路径的写权限:$verb $raw"
        }
        else -> null
    }

    /** 格式化与块设备:这些命令的作用对象不可能是工作区里的文件。 */
    private fun formatReason(segment: ShellSegment): String? {
        val verb = segment.verb() ?: return null
        if (verb == "mkfs" || verb.startsWith("mkfs.")) return "文件系统格式化命令:$verb"
        if (verb in BLOCK_DEVICE_VERBS) return "直接操作分区/块设备的命令:$verb"
        return ddDeviceReason(verb, segment)
    }

    private fun ddDeviceReason(verb: String, segment: ShellSegment): String? {
        if (verb != "dd") return null
        val output = segment.args().firstOrNull { it.startsWith("of=") }?.removePrefix("of=").orEmpty()
        return if (output.startsWith("/dev/") && !ShellCommandLex.isHarmlessSink(output)) "dd 写入设备节点:$output" else null
    }

    /**
     * 设备级命令:提权、电源、挂载/命名空间,以及会清掉或卸载应用数据的 `pm`/`am`/`appops`
     * 子命令。这些本来就有各自的专用工具(device_shell / app_clear_data / am_start 等),
     * 走的是各自的审批;出现在文件终端里没有正当用途。
     */
    private fun deviceReason(segment: ShellSegment): String? {
        val verb = segment.verb() ?: return null
        DEVICE_ONLY_VERBS[verb]?.let { return "$it:$verb" }
        return destructiveSubcommandReason(verb, segment.args().firstOrNull { !it.startsWith("-") })
    }

    private fun destructiveSubcommandReason(verb: String, subcommand: String?): String? = when {
        verb == "pm" && subcommand in PM_DESTRUCTIVE -> "包管理破坏性子命令:pm $subcommand"
        verb == "am" && subcommand == "force-stop" -> "强停其它应用的命令:am force-stop"
        verb == "appops" && subcommand == "set" -> "改其它应用权限面的命令:appops set"
        else -> null
    }

    /**
     * 「把一段动态内容当命令码执行」这一类:管道喂给 shell/解释器的 stdin,
     * 或 `eval "$(curl …)"`。`curl … | sh`、`wget -qO- … | bash` 是它的典型形态。
     *
     * 管道本身不拦(`cat x | grep y` 天天在用),只在内容真会被执行时拦:
     *  - shell 不带 `-c` 时,stdin 就是它的命令来源 → 拦
     *  - 解释器没有脚本参数,或参数是 `-`/`-s`/`--stdin` → stdin 是命令来源 → 拦
     *  - `… | python3 -m json.tool`、`… | python3 fmt.py`、`… | sh -c 'echo hi'`:
     *    管道里是**数据**不是命令码 → 不拦,这是终端的正当用法
     */
    private fun remoteCodeReason(segment: ShellSegment): String? {
        val verb = segment.verb() ?: return null
        val args = segment.args()
        val pipedCode = segment.isPipeInput && verb in INTERPRETERS &&
            ShellCommandLex.stdinIsCommandSource(args, verb in SHELL_INTERPRETERS)
        if (pipedCode) return "把管道内容当作命令码执行:| $verb ${args.joinToString(" ")}".trim()
        if (verb in SCRIPT_SOURCES && args.any(ShellCommandLex::loadsRemoteContent)) {
            return "$verb 执行来自网络的脚本内容"
        }
        return null
    }

    private fun killReason(segment: ShellSegment): String? = when {
        segment.verb() != "kill" -> null
        segment.args().any { it == "1" } -> "kill 指向 PID 1(init)"
        segment.args().any { it == "-1" } -> "kill 指向全部进程"
        else -> null
    }

    /** 重定向落盘的目标:`> /system/...`、`>> /data/data/...`。`2>&1` 这类不是落盘。 */
    private fun redirectReason(command: String, locator: ShellLocator): String? =
        REDIRECT.findAll(command)
            .map { ShellCommandLex.stripQuotes(it.groupValues[1]) }
            .filter { it.isNotEmpty() && !it.startsWith("&") }
            .firstNotNullOfOrNull { raw -> unacceptable("重定向写入", locator.target(raw), raw) }

    /** 目标落在「绝对不可接受」的区域时的原因;其余区域由调用方按档位判断。 */
    private fun unacceptable(label: String, target: ShellTarget, raw: String): String? = when (target.scope) {
        ShellScope.DEVICE_ROOT -> "${label}命中设备根($raw)"
        ShellScope.SYSTEM -> "${label}命中系统分区($raw)"
        ShellScope.APP_DATA -> "${label}命中 /data 下工作区以外的应用私有数据($raw)"
        else -> null
    }

    // ── 判据表 ────────────────────────────────────────────────────────────

    private val FORK_BOMB_SPACES = Regex("\\s+")
    private const val FORK_BOMB_SHAPE = ":(){:"
    private const val FORK_BOMB_REASON = "fork 炸弹的自我复制写法"

    private val REDIRECT = Regex("""\d?>{1,2}\s*("?[^"\s;|&]+)""")

    private val DELETE_VERBS = setOf("rm", "rmdir", "shred", "unlink")
    private val PERMISSION_VERBS = setOf("chmod", "chown", "chgrp")
    private val BLOCK_DEVICE_VERBS = setOf("fdisk", "sfdisk", "parted", "sgdisk", "wipefs", "losetup", "mkswap")

    /** 设备级命令:在 App 沙盒终端里没有正当用途,各自有专用工具与审批。 */
    private val DEVICE_ONLY_VERBS = mapOf(
        "su" to "提权命令",
        "sudo" to "提权命令",
        "doas" to "提权命令",
        "reboot" to "设备电源命令",
        "poweroff" to "设备电源命令",
        "shutdown" to "设备电源命令",
        "halt" to "设备电源命令",
        "mount" to "挂载与系统运行时命令",
        "umount" to "挂载与系统运行时命令",
        "setprop" to "挂载与系统运行时命令",
        "restorecon" to "挂载与系统运行时命令",
        "chroot" to "挂载与系统运行时命令",
        "switch_root" to "挂载与系统运行时命令",
    )

    private val PM_DESTRUCTIVE = setOf("clear", "uninstall", "disable", "delete")

    /** 递归放开这些区域的写权限才算第一档;工作区内改权限属于正常准备脚本的过程。 */
    private val OUT_OF_SCOPE_STORAGE = setOf(ShellScope.STORAGE, ShellScope.OUTSIDE)

    private val SHELL_INTERPRETERS = setOf("sh", "bash", "dash", "ash", "ksh", "zsh", "csh", "tcsh")
    private val INTERPRETERS = SHELL_INTERPRETERS + setOf(
        "python", "python2", "python3", "perl", "ruby", "node", "php", "lua",
    )

    /** 显式声明「stdin 就是我的命令来源」的参数。 */
    private val SCRIPT_SOURCES = setOf("eval", "source")

    private val GIT_DESTRUCTIVE_ARGS = setOf("--force", "reset", "clean", "push", "checkout")

    /** 第二档:有破坏力但属于正常工作,只记日志,不拦。 */
    private val CAUTION_NOTES = mapOf(
        "rm" to "删除文件",
        "rmdir" to "删除目录",
        "unlink" to "删除文件",
        "shred" to "销毁文件内容",
        "mv" to "移动/覆盖文件",
        "truncate" to "截断文件",
        "dd" to "按块读写",
        "chmod" to "改文件权限",
        "chown" to "改文件属主",
        "chgrp" to "改文件属组",
        "kill" to "终止进程",
        "pkill" to "按名批量终止进程",
        "killall" to "按名批量终止进程",
        "curl" to "网络下载",
        "wget" to "网络下载",
        "pm" to "包管理命令",
        "am" to "活动管理器命令",
        "settings" to "系统设置读写",
        "input" to "注入输入事件",
        "sh" to "执行脚本文件",
        "bash" to "执行脚本文件",
        "python" to "执行脚本文件",
        "python3" to "执行脚本文件",
        "node" to "执行脚本文件",
        "perl" to "执行脚本文件",
        "ruby" to "执行脚本文件",
    )
}
