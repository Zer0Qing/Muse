package io.zer0.muse.terminal

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.util.GlobalCoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * v2.x 终端:会话管理器 —— 常驻 shell 会话,输出流式回传 UI。
 *
 * 双引擎:
 *  - **PTY 引擎**(首选,[Pty] native 就绪时):真伪终端,forkpty 自编译实现;
 *    交互式程序、Ctrl-C 信号、窗口尺寸全支持;
 *  - **管道引擎**(兜底,无 native 库时):`/system/bin/sh` + ProcessBuilder,
 *    零原生依赖,基础命令可用。
 *
 * 线程模型:输出读取在 IO 协程;回调 [Listener] 由调用方决定线程(UI 侧自行切主线程)。
 */
class TerminalSessionManager(private val appContext: Context) {

    interface Listener {
        fun onOutput(bytes: ByteArray)
        fun onExit(code: Int)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + GlobalCoroutineExceptionHandler)
    private var process: Process? = null
    private var masterFd: Int = -1
    private var childPid: Int = -1
    private var readerJob: Job? = null

    /** 主动拆除会话时抑制 onExit 回调(避免重启时 UI 误报进程退出)。 */
    @Volatile
    private var suppressExit = false

    @Volatile
    private var listener: Listener? = null

    /** 当前是否运行在 PTY 引擎(决定 UI 是否本地回显、是否提供 Ctrl-C)。 */
    val isPty: Boolean
        @Synchronized get() = masterFd >= 0

    /** 会话是否在运行。 */
    val isRunning: Boolean
        @Synchronized get() = if (masterFd >= 0) true else process?.isAlive == true

    fun setListener(l: Listener?) {
        listener = l
    }

    /** 若未运行则启动会话(幂等)。 */
    @Synchronized
    fun ensureStarted() {
        if (isRunning) return
        startLocked()
    }

    /** 重启会话(先拆旧会话,再启动新会话)。 */
    @Synchronized
    fun restart() {
        killLocked()
        startLocked()
    }

    /** 向会话写入文本(命令需自带换行;PTY 下由终端回显,勿本地回显)。 */
    fun write(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val fd = masterFd
        val proc = process
        scope.launch {
            runCatching {
                if (fd >= 0) {
                    Pty.write(fd, bytes, 0, bytes.size)
                } else if (proc != null) {
                    proc.outputStream.write(bytes)
                    proc.outputStream.flush()
                }
            }.onFailure { Logger.w(TAG, "stdin 写入失败: ${it.message}") }
        }
    }

    /** v2.x PTY:发送 Ctrl-C(SIGINT)到前台进程组;管道引擎返回 false(不可用)。 */
    fun sendInterrupt(): Boolean {
        val pid = childPid
        if (masterFd < 0 || pid <= 0) return false
        return runCatching {
            android.os.Process.sendSignal(-pid, SIGNAL_SIGINT)
            true
        }.onFailure { Logger.w(TAG, "SIGINT 发送失败: ${it.message}") }.getOrDefault(false)
    }

    /** 同步终端窗口尺寸(PTY 引擎;全屏程序排版依赖)。 */
    fun resize(rows: Int, cols: Int) {
        val fd = masterFd
        if (fd >= 0) {
            runCatching { Pty.setWindowSize(fd, rows, cols) }
        }
    }

    // ── 引擎启动 ─────────────────────────────────────────────

    @Synchronized
    private fun startLocked() {
        suppressExit = false
        if (Pty.available && startPtyLocked()) return
        startPipeLocked()
    }

    private fun startPtyLocked(): Boolean {
        val workDir = io.zer0.muse.workspace.WorkspaceLocation.resolve(appContext).apply { mkdirs() }
        val pidArray = IntArray(1)
        val fd = runCatching {
            // P1-B: 终端 PATH 追加运行时 bin，并注入 exec 垫片(名称映射 + shebang；运行时未就绪时为空串跳过)
            val (extraPath, preloadLib) = runtimeInjection(appContext)
            Pty.createSubprocess(
                SHELL_PATH,
                workDir.absolutePath,
                pidArray,
                extraPath = extraPath,
                preloadLib = preloadLib,
            )
        }.getOrElse {
            Logger.w(TAG, "PTY 引擎启动失败: ${it.message}")
            -1
        }
        if (fd < 0) return false
        masterFd = fd
        childPid = pidArray[0]
        Logger.i(TAG, "PTY 会话启动: pid=$childPid fd=$fd")
        readerJob = scope.launch {
            val buffer = ByteArray(8192)
            try {
                while (isActive) {
                    val n = Pty.read(fd, buffer, 0, buffer.size)
                    if (n <= 0) break
                    listener?.onOutput(buffer.copyOf(n))
                }
            } catch (t: Throwable) {
                Logger.d(TAG, "PTY 读取循环结束: ${t.message}")
            }
            val code = runCatching { Pty.waitFor(childPid) }.getOrDefault(-1)
            if (!suppressExit) listener?.onExit(code)
        }
        return true
    }

    private fun startPipeLocked() {
        val workDir = io.zer0.muse.workspace.WorkspaceLocation.resolve(appContext).apply { mkdirs() }
        val started = runCatching {
            ProcessBuilder(SHELL_PATH)
                .directory(workDir)
                .redirectErrorStream(true)
                .apply {
                    environment()["TERM"] = "xterm-256color"
                    environment()["HOME"] = workDir.absolutePath
                    val (extraPath, preloadLib) = runtimeInjection(appContext)
                    val pathPrefix = if (extraPath.isNotEmpty()) "$extraPath:" else ""
                    environment()["PATH"] = "$pathPrefix/system/bin:/system/xbin:$workDir/bin"
                    if (preloadLib.isNotEmpty()) environment()["LD_PRELOAD"] = preloadLib
                }
                .start()
        }
        started.onFailure {
            Logger.w(TAG, "管道会话启动失败: ${it.message}")
            listener?.onExit(-1)
        }
        val proc = started.getOrNull() ?: return
        process = proc
        Logger.i(TAG, "管道会话启动(PTY 不可用)")
        readerJob = scope.launch {
            val buffer = ByteArray(8192)
            try {
                val input = proc.inputStream
                while (isActive) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    listener?.onOutput(buffer.copyOf(n))
                }
            } catch (e: Exception) {
                Logger.d(TAG, "读取循环结束: ${e.message}")
            }
            val code = runCatching { proc.exitValue() }.getOrDefault(-1)
            if (!suppressExit) listener?.onExit(code)
        }
    }

    @Synchronized
    private fun killLocked() {
        suppressExit = true
        readerJob?.cancel()
        readerJob = null
        if (masterFd >= 0) {
            runCatching { Pty.closeFd(masterFd) }
            masterFd = -1
        }
        if (childPid > 0) {
            runCatching { android.os.Process.sendSignal(childPid, SIGNAL_SIGKILL) }
            childPid = -1
        }
        process?.let { proc ->
            runCatching { proc.destroy() }
            runCatching { proc.destroyForcibly() }
        }
        process = null
    }

    companion object {
        private const val TAG = "TerminalSession"
        private const val SHELL_PATH = "/system/bin/sh"
        private const val SIGNAL_SIGINT = 2
        private const val SIGNAL_SIGKILL = 9

        /** P1-B: 终端运行时注入 — extraPath=运行时 bin, preloadLib=exec 垫片;未就绪时为空串。 */
        private fun runtimeInjection(appContext: Context): Pair<String, String> {
            val bin = File(appContext.filesDir, "muse-runtime/bin")
            val shim = File(appContext.applicationInfo.nativeLibraryDir, "libmuse_exec.so")
            val extraPath = if (bin.isDirectory) bin.absolutePath else ""
            val preloadLib = if (shim.isFile) shim.absolutePath else ""
            return extraPath to preloadLib
        }
    }
}

/**
 * 进程级单例持有者:终端会话跨页面存活(App 存活期内),
 * 页面销毁只解绑 listener,不杀会话。
 */
object TerminalSessionStore {

    @Volatile
    private var manager: TerminalSessionManager? = null

    fun get(context: Context): TerminalSessionManager {
        manager?.let { return it }
        return synchronized(this) {
            manager ?: TerminalSessionManager(context.applicationContext).also { manager = it }
        }
    }
}
