package io.zer0.muse.terminal

import io.zer0.common.Logger
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 环回结果监听器：一次执行开一个，收到一条载荷就结束。
 *
 * 只绑 127.0.0.1，端口交给系统分配（固定端口会被别的应用抢占，也会让设备上的其他程序
 * 有机会往里灌假结果）。[open] 失败返回 null，调用方据此退回广播-only，不影响命令执行。
 */
internal class TermuxLoopbackListener private constructor(
    private val server: ServerSocket,
    private val token: String,
    private val requestId: Int,
) : Closeable {

    val port: Int get() = server.localPort

    @Volatile
    private var active: Socket? = null

    @Volatile
    private var closed = false

    /**
     * 阻塞等待一次结果连接，必须在 IO 线程调用。
     * 超时、被 [close] 打断、载荷不合法都返回 null——调用方还有广播路径兜着。
     */
    @Suppress("TooGenericExceptionCaught") // 阻塞 accept/read 抛的异常种类枚举不完，一律降级为「这条路没拿到」。
    fun await(timeoutMs: Long): TermuxChannel.TermuxResult? {
        val socket = try {
            server.soTimeout = timeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            server.accept()
        } catch (e: Exception) {
            if (!closed) Logger.d(TAG, "环回监听结束 requestId=$requestId: ${e.javaClass.simpleName}")
            return null
        }
        active = socket
        // close() 可能正好落在 accept 返回与上面这行赋值之间：那次 close 看到的 active 还是 null，
        //  socket 就没人关了。不自己补这一刀，readPayload 会一直阻塞到 READ_TIMEOUT_MS，
        //  而 exec 的 coroutineScope 要等子协程收尾 —— 广播结果早回来了也得白等二十秒。
        if (closed) {
            runCatching { socket.close() }
            active = null
            return null
        }
        return try {
            socket.soTimeout = READ_TIMEOUT_MS
            readPayload(socket.getInputStream())
        } catch (e: Exception) {
            Logger.w(TAG, "环回结果读取失败 requestId=$requestId: ${e.message}")
            null
        } finally {
            runCatching { socket.close() }
            active = null
        }
    }

    private fun readPayload(input: InputStream): TermuxChannel.TermuxResult? {
        val header = TermuxLoopbackProtocol.parseHeader(readHeaderLine(input) ?: return null)
        if (header == null) {
            Logger.w(TAG, "环回载荷头不合法 requestId=$requestId")
            return null
        }
        // token 与 id 双重校验：设备上别的应用能连到环回端口，但拿不到本次进程的随机 token。
        if (header.token != token || header.requestId != requestId) {
            Logger.w(TAG, "环回载荷身份不符 requestId=$requestId，已丢弃")
            return null
        }
        val stdout = readChunk(input, header.stdoutLength, TermuxLoopbackProtocol.MAX_PAYLOAD_BYTES)
        val stderr = readChunk(
            input,
            header.stderrLength,
            TermuxLoopbackProtocol.MAX_PAYLOAD_BYTES - stdout.bytes.size,
        )
        return TermuxChannel.TermuxResult(
            exitCode = header.exitCode,
            stdout = String(stdout.bytes, Charsets.UTF_8),
            stderr = String(stderr.bytes, Charsets.UTF_8),
            error = null,
            truncated = stdout.capped || stderr.capped,
        )
    }

    private fun readHeaderLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        val limit = TermuxLoopbackProtocol.maxHeaderBytes()
        while (buffer.size() < limit) {
            val b = input.read()
            if (b < 0) break
            if (b == '\n'.code) return String(buffer.toByteArray(), Charsets.UTF_8)
            buffer.write(b)
        }
        return null
    }

    /** 按声明长度读满；超出预算或提前 EOF 都算截断，宁可给不完整输出也不无上限吃内存。 */
    private fun readChunk(input: InputStream, length: Int, budget: Int): Chunk {
        val want = minOf(length, budget.coerceAtLeast(0))
        val bytes = ByteArray(want)
        var read = 0
        while (read < want) {
            val n = input.read(bytes, read, want - read)
            if (n < 0) break
            read += n
        }
        return Chunk(bytes.copyOf(read), read < length)
    }

    private class Chunk(val bytes: ByteArray, val capped: Boolean)

    /** 幂等：exec 的正常收尾与超时收尾都会调，关掉 server 与已接受的 socket 才能打断阻塞读。 */
    override fun close() {
        closed = true
        runCatching { server.close() }
        runCatching { active?.close() }
    }

    companion object {
        private const val TAG = "TermuxLoopback"
        private const val READ_TIMEOUT_MS = 20_000

        /** 绑定失败原因枚举不完（无 INTERNET 权限 / SELinux / 端口耗尽），一律降级为广播-only。 */
        @Suppress("TooGenericExceptionCaught")
        fun open(token: String, requestId: Int): TermuxLoopbackListener? = try {
            val server = ServerSocket()
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1)
            TermuxLoopbackListener(server, token, requestId)
        } catch (e: Exception) {
            Logger.w(TAG, "环回监听不可用，退回广播-only: ${e.message}")
            null
        }
    }
}
