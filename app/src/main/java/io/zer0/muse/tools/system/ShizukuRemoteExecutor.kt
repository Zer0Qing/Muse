package io.zer0.muse.tools.system

import android.os.ParcelFileDescriptor
import io.zer0.common.Logger
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * 远程 shell 进程句柄。抽出来的目的是让排空/超时/销毁这套逻辑能在没有 Shizuku 的 JVM 上被测到。
 */
interface RemoteShellProcess {
    fun stdout(): InputStream
    fun stderr(): InputStream

    /** true 表示进程已退出；false 表示等满了 [timeoutMs] 还活着。 */
    fun waitFor(timeoutMs: Long): Boolean
    fun exitValue(): Int
    fun destroy()
    fun close()
}

/**
 * Shizuku 快路径：直接在 Shizuku 服务进程里起 `sh -c`。
 *
 * 与 UserService 的区别是**没有冷启动**：不 spawn app_process、不加载本 APK 的 dex、
 * 不需要 bind 与连接回调。在限制后台进程启动的 ROM 上，UserService 那条路会反复卡在
 * 绑定超时，而这条路只要 binder 活着就能立刻执行。
 *
 * 返回 null 只有一个含义：**命令根本没跑起来**，调用方可以安全退回 UserService。
 * 一旦进程起来了，无论超时还是 binder 中途死掉都必须返回结果 —— 再退回就等于把同一条
 * 命令执行两遍，带副作用的命令会被做两次。
 *
 * 这条路径不是 UserService 的替代品：上游已把便捷封装标记为准备移除，服务端实现随时可能变。
 * 因此它只作为快路径存在，UserService 始终保留为兜底。
 */
class ShizukuRemoteExecutor(
    private val starter: (String) -> RemoteShellProcess? = ::startShizukuShellProcess,
) {

    companion object {
        private const val TAG = "ShizukuRemote"

        /** 与 UserService 侧的命令超时保持一致。 */
        const val DEFAULT_TIMEOUT_MS = 10_000L

        /** 文本路径保留的字节上限；流式路径不受此限。 */
        const val MAX_TEXT_BYTES = 2 * 1024 * 1024

        /** 进程已退出后再等管道收尾的上限。 */
        private const val DRAIN_TIMEOUT_MS = 30_000L
    }

    /** 文本结果。null = 这条路起不来，调用方应退回 UserService。 */
    fun execute(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShizukuAuthorizer.ShizukuExecResult? {
        val out = CappedText()
        val err = CappedText()
        val outcome = runCommand(command, timeoutMs, out, err) ?: return null
        return ShizukuAuthorizer.ShizukuExecResult(
            exitCode = outcome.exitCode,
            stdout = out.text(),
            stderr = if (outcome.note == null) err.text() else "${err.text()}\n${outcome.note}",
        )
    }

    /**
     * 把合并后的输出字节流进 [sink]，返回退出码；null = 起不来。
     *
     * stderr 在 shell 里就并进 stdout，顺序由操作系统保证，不是两条线程抢着往 sink 里写。
     */
    fun streamTo(command: String, sink: OutputStream, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Int? {
        val outcome = runCommand("(\n$command\n) 2>&1", timeoutMs, sink, DiscardSink) ?: return null
        outcome.note?.let { note ->
            runCatching { sink.write("\n$note".toByteArray(Charsets.UTF_8)) }
        }
        return outcome.exitCode
    }

    private data class RunOutcome(val exitCode: Int, val note: String?)

    private fun runCommand(command: String, timeoutMs: Long, out: OutputStream, err: OutputStream): RunOutcome? {
        val process = runCatching { starter(command) }
            .onFailure { Logger.i(TAG, "newProcess 起不来: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull() ?: return null

        // 分界线：上面还没执行命令，下面命令已经在跑。
        val pool = Executors.newFixedThreadPool(2)
        return try {
            // 必须两路同时排空：命令写满 stderr 管道后会阻塞在 write 上，
            // 先把 stdout 读到 EOF 的实现永远等不到那个 EOF。
            val outTask = pool.submit(Callable { drain(process.stdout(), out) })
            val errTask = pool.submit(Callable { drain(process.stderr(), err) })
            val exited = runCatching { process.waitFor(timeoutMs) }.getOrDefault(false)
            if (!exited) runCatching { process.destroy() }
            // 用 and 而非 &&：两条都要真的等过，短路会把另一条线程漏在后台。
            val drained = await(outTask) and await(errTask)
            val note = when {
                !exited -> "[超时] 命令执行超时(${timeoutMs / 1000}s),已终止"
                !drained -> "[输出流未能读完,当前输出可能不完整]"
                else -> null
            }
            RunOutcome(
                exitCode = if (note != null) -1 else runCatching { process.exitValue() }.getOrDefault(-1),
                note = note,
            )
        } finally {
            pool.shutdownNow()
            runCatching { process.close() }
        }
    }

    private fun drain(input: InputStream, sink: OutputStream): Boolean = runCatching {
        input.use { source ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                sink.write(buffer, 0, count)
            }
        }
    }.isSuccess

    private fun await(task: Future<Boolean>): Boolean = runCatching { task.get(DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        .getOrElse {
            task.cancel(true)
            false
        }

    private object DiscardSink : OutputStream() {
        override fun write(b: Int) = Unit
    }
}

/** 按字节保留头部的文本缓冲；超限丢尾并明确标注，不假装输出是完整的。 */
private class CappedText : OutputStream() {
    private val buffer = ByteArrayOutputStream()
    private var truncated = false

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        val room = ShizukuRemoteExecutor.MAX_TEXT_BYTES - buffer.size()
        if (room <= 0) {
            truncated = true
            return
        }
        val take = minOf(len, room)
        buffer.write(b, off, take)
        if (take < len) truncated = true
    }

    fun text(): String {
        val body = buffer.toString(Charsets.UTF_8.name())
        return if (truncated) "$body\n[输出已截断]" else body
    }
}

/**
 * 字节流增量解码成文本。管道按 8KB 切块，一个汉字正好被切在两块之间时，
 * 逐块 `String(bytes)` 会把它变成两个替换字符 —— 解码器必须跨块保持状态。
 */
internal class Utf8TextSink(private val onText: (String) -> Unit) : OutputStream() {
    // 默认动作是 REPORT：坏字节会被静默丢掉，收尾时半个汉字就凭空消失了。
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteArray(0)

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        val chunk = b.copyOfRange(off, off + len)
        val input = ByteBuffer.wrap(if (pending.isEmpty()) chunk else pending + chunk)
        val chars = CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, chars, false)
        pending = if (input.hasRemaining()) ByteArray(input.remaining()).also { input.get(it) } else ByteArray(0)
        chars.flip()
        if (chars.hasRemaining()) onText(chars.toString())
    }

    /** 收尾：残留的半个字符按替换字符吐出来，不让它凭空消失。 */
    fun flushRemainder() {
        val chars = CharBuffer.allocate(pending.size + 2)
        decoder.decode(ByteBuffer.wrap(pending), chars, true)
        decoder.flush(chars)
        pending = ByteArray(0)
        chars.flip()
        if (chars.hasRemaining()) onText(chars.toString())
    }
}

/**
 * 用 Shizuku 服务 binder 直接起进程。
 *
 * SDK 内部收到 binder 后做的正是 `IShizukuService.Stub.asInterface(binder)`；这里复用同一条
 * 公开 AIDL，不引新依赖、不用反射。binder 还没到达时返回 null，由调用方走 UserService。
 */
private fun startShizukuShellProcess(command: String): RemoteShellProcess? {
    val binder = Shizuku.getBinder() ?: return null
    val service = IShizukuService.Stub.asInterface(binder) ?: return null
    val remote = service.newProcess(arrayOf("sh", "-c", command), null, null) ?: return null
    return ShizukuProcessHandle(remote)
}

private class ShizukuProcessHandle(private val remote: IRemoteProcess) : RemoteShellProcess {
    private val opened = ConcurrentLinkedQueue<ParcelFileDescriptor>()

    override fun stdout(): InputStream = open(remote.inputStream)

    override fun stderr(): InputStream = open(remote.errorStream)

    override fun waitFor(timeoutMs: Long): Boolean = remote.waitForTimeout(timeoutMs, TimeUnit.MILLISECONDS.toString())

    override fun exitValue(): Int = remote.exitValue()

    override fun destroy() {
        remote.destroy()
    }

    override fun close() {
        // 排空线程正常结束时已经关过一遍；这里兜的是「线程还没起来就失败」的那次。
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    private fun open(fd: ParcelFileDescriptor): InputStream {
        opened.add(fd)
        return ParcelFileDescriptor.AutoCloseInputStream(fd)
    }
}
