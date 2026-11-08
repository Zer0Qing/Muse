package io.zer0.muse.terminal

/**
 * Termux 结果回传的第三条冗余路径：命令自己把结果写回环回 TCP。
 *
 * 为什么需要它：前两条路径（PendingIntent 广播、清单接收器）都依赖 Android 的广播机制，
 * 而广播恰恰是最容易被 ROM 拦掉的一层——一旦拦掉，命令明明跑完了，调用方也只能等到超时，
 * 既拿不到输出，也分不清「Termux 没执行」和「执行了但结果丢了」。
 * 环回 TCP 完全绕开广播：bash 用内建的 `/dev/tcp` 直接连 127.0.0.1，只要命令真的跑了就一定有结果。
 * 顺带解决了 Termux 单次回传约 100KB 的截断上限。
 *
 * 安全边界：只绑 127.0.0.1（外部设备连不上）、端口由系统随机分配、载荷头必须带本次进程的
 * 随机会话 token 与请求 id，两者都对不上就丢弃；收到的字节只当字符串输出，不执行、不拼路径。
 */
internal object TermuxLoopbackProtocol {

    const val HEADER_PREFIX = "MUSE/1"

    /** 单次回传上限：超过就截断并标记，避免一条 `cat` 大文件把进程内存吃光。 */
    const val MAX_PAYLOAD_BYTES = 4 * 1024 * 1024

    /** 头部长度上限：正常头部不到 200 字节，超过说明对端不是在说我们的协议。 */
    private const val MAX_HEADER_BYTES = 512

    data class Header(
        val token: String,
        val requestId: Int,
        val exitCode: Int,
        val stdoutLength: Int,
        val stderrLength: Int,
    )

    /** 解析载荷头；格式不对、字段缺失、长度非法一律返回 null（纯函数，可单测）。 */
    fun parseHeader(line: String): Header? {
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size != 6 || fields[0] != HEADER_PREFIX) return null
        val requestId = fields[2].toIntOrNull()
        val exitCode = fields[3].toIntOrNull()
        val stdoutLength = fields[4].toIntOrNull()
        val stderrLength = fields[5].toIntOrNull()
        if (requestId == null || exitCode == null || stdoutLength == null || stderrLength == null) return null
        if (stdoutLength < 0 || stderrLength < 0) return null
        return Header(fields[1], requestId, exitCode, stdoutLength, stderrLength)
    }

    /** 头部读取上限，供监听器逐字节读行时判停。 */
    fun maxHeaderBytes(): Int = MAX_HEADER_BYTES

    /**
     * 把用户命令包进「先落临时文件、再经环回回传、最后照常打印到 stdout/stderr」的外壳。
     *
     * 最后那两步 `cat` 不能省：Termux 的广播路径回传的是它自己捕获的 stdout/stderr，
     * 外壳若把输出全吞进临时文件，广播路径就变成空输出——冗余反而弄坏了主路径。
     *
     * 外壳自身失败时（没有 mktemp、bash 不支持 /dev/tcp、端口没人听）必须退回直接执行原命令，
     * 绝不能让冗余措施把本来能跑的命令弄挂。
     */
    fun buildWrapper(command: String, port: Int, token: String, requestId: Int): String = buildString {
        appendLine("__muse_d=\$(mktemp -d 2>/dev/null)")
        appendLine("if [ -n \"\$__muse_d\" ] && [ -d \"\$__muse_d\" ]; then")
        appendLine("(")
        appendLine(command)
        appendLine(") >\"\$__muse_d/o\" 2>\"\$__muse_d/e\"")
        appendLine("__muse_rc=\$?")
        appendLine("__muse_ol=\$(wc -c <\"\$__muse_d/o\" 2>/dev/null)")
        appendLine("__muse_el=\$(wc -c <\"\$__muse_d/e\" 2>/dev/null)")
        // 长度量不出来（wc 缺失或坏了）就别发环回：头部会声明 0 长度却跟着真实字节，
        // 载荷错位后还会抢在广播前面送回一份坏结果。这种情况安静退回广播路径。
        appendLine("if [ -n \"\$__muse_ol\" ] && [ -n \"\$__muse_el\" ]; then")
        // 重定向从左到右生效：先把 stderr 丢进 /dev/null，再开 /dev/tcp。
        // 顺序反过来的话，连接被拒时 bash 的报错会混进 Termux 捕获的 stderr，污染命令输出。
        val format = "'$HEADER_PREFIX %s %s %s %s %s\\n' '$token' '$requestId'"
        appendLine("{ printf $format \"\$__muse_rc\" \"\$__muse_ol\" \"\$__muse_el\"; \\")
        appendLine("cat \"\$__muse_d/o\"; cat \"\$__muse_d/e\"; } 2>/dev/null >\"/dev/tcp/127.0.0.1/$port\"")
        appendLine("fi")
        appendLine("cat \"\$__muse_d/o\"")
        appendLine("cat \"\$__muse_d/e\" >&2")
        appendLine("rm -f \"\$__muse_d/o\" \"\$__muse_d/e\"")
        appendLine("rmdir \"\$__muse_d\" 2>/dev/null")
        appendLine("exit \"\$__muse_rc\"")
        appendLine("else")
        appendLine(command)
        appendLine("fi")
    }
}
