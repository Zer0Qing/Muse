package io.zer0.muse.runtime

import android.content.Context
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 通过 proot 在 Ubuntu rootfs 里执行命令。
 *
 * 为什么是 proot：Android 10+ 不允许执行应用可写目录里的二进制（W^X），
 * 而 proot 自己加载 ELF、不依赖系统的 execve，所以整套环境可以放在可下载的 rootfs 里。
 *
 * proot 那四个文件（proot、loader、libtalloc、libandroid-shmem）随 APK 的 jniLibs 走：
 * 只有 `nativeLibraryDir` 里的文件是安装时由系统落盘、允许执行的。
 */
class ProotRuntime(
    private val context: Context,
    private val store: RuntimeStore,
) {

    private val binsDir: File get() = File(context.applicationInfo.nativeLibraryDir)
    private val prootBin: File get() = File(binsDir, "libmuse_proot.so")
    private val loaderBin: File get() = File(binsDir, "libmuse_proot_loader.so")

    /** jniLibs 中执行所需的四个文件是否都在。 */
    fun isAvailable(): Boolean = prootBin.isFile && loaderBin.isFile &&
        File(binsDir, "libtalloc.so").isFile && File(binsDir, "libandroid-shmem.so").isFile

    private fun tmpDir(): File = File(store.rootDir(), "tmp").apply { mkdirs() }

    /**
     * 在 [RuntimeId] 对应的 rootfs 里执行命令。
     *
     * @param binds 额外的 `-b` 绑定，形如 "/host/path:/guest/path"
     * @param env 追加的环境变量（放进 guest 进程环境）
     */
    suspend fun exec(
        id: RuntimeId,
        argv: List<String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        binds: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
        workdirGuest: String? = null,
    ): ExecResult = withContext(Dispatchers.IO) {
        val state = store.state(id)
        if (state !is RuntimeState.Ready) {
            return@withContext ExecResult.failure(
                if (state is RuntimeState.Broken) "运行环境损坏：${state.reason}" else "运行环境未安装",
            )
        }
        if (!isAvailable()) {
            return@withContext ExecResult.failure("这个设备架构没有内置 proot（${context.applicationInfo.nativeLibraryDir}）")
        }
        val rootfs = store.installDir(id, state.revision)
        if (!rootfs.isDirectory) return@withContext ExecResult.failure("rootfs 目录缺失")

        val builder = buildProcessBuilder(
            rootfs = rootfs,
            argv = argv,
            binds = binds,
            env = env,
            workdirGuest = workdirGuest,
        )

        runCatching {
            val process = builder.start()
            val stdoutF = readAsync(process.inputStream)
            val stderrF = readAsync(process.errorStream)
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext ExecResult(
                    exitCode = -1,
                    stdout = stdoutF.get(2, TimeUnit.SECONDS).orEmpty(),
                    stderr = stderrF.get(2, TimeUnit.SECONDS).orEmpty(),
                    timedOut = true,
                )
            }
            ExecResult(
                exitCode = process.exitValue(),
                stdout = stdoutF.get(5, TimeUnit.SECONDS).orEmpty(),
                stderr = stderrF.get(5, TimeUnit.SECONDS).orEmpty(),
            )
        }.getOrElse { e ->
            Logger.e(TAG, "proot 启动失败: ${e.message}", e)
            ExecResult.failure(e.message ?: "proot 启动失败")
        }
    }

    /**
     * 构造一个可长期运行的 proot 子进程（stdin/stdout 由调用方持有）。
     *
     * 与 [exec] 的区别：这里不等待退出、不收集输出，适合插件桥接和 MCP stdio 这种
     * 需要持续读写的进程。返回 null 表示运行环境未就绪或当前 ABI 没有 proot。
     */
    fun processBuilder(
        id: RuntimeId,
        argv: List<String>,
        binds: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
        workdirGuest: String? = null,
    ): ProcessBuilder? {
        val state = store.state(id) as? RuntimeState.Ready ?: return null
        if (!isAvailable()) return null
        val rootfs = store.installDir(id, state.revision).takeIf { it.isDirectory } ?: return null
        return buildProcessBuilder(rootfs, argv, binds, env, workdirGuest)
    }

    private fun buildProcessBuilder(
        rootfs: File,
        argv: List<String>,
        binds: List<String>,
        env: Map<String, String>,
        workdirGuest: String?,
    ): ProcessBuilder {
        val command = buildList {
            add(prootBin.absolutePath)
            add("--link2symlink")
            add("-0")
            add("-r")
            add(rootfs.absolutePath)
            listOf("/dev", "/proc", "/sys").forEach {
                add("-b")
                add(it)
            }
            (binds + "${ProotDnsConfig.ensureFile(context).absolutePath}:/etc/resolv.conf")
                .forEach {
                    add("-b")
                    add(it)
                }
            add("-w")
            add(workdirGuest ?: "/")
            addAll(argv)
        }
        return ProcessBuilder(command).apply {
            directory(File("/"))
            val libs = binsDir.absolutePath
            environment().apply {
                put("PROOT_LOADER", loaderBin.absolutePath)
                put("PROOT_TMP_DIR", tmpDir().absolutePath)
                put("LD_LIBRARY_PATH", libs)
                put("HOME", "/root")
                put("PATH", "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin")
                put("TERM", "dumb")
                putAll(env)
            }
        }
    }

    /**
     * 安装后的自检：能跑 `node -v` 才算装好。
     *
     * 这一步不能省 —— 没有它就会出现「界面显示已安装、一用就报错」，
     * 而用户根本不知道该重装。
     */
    suspend fun selfCheck(id: RuntimeId): String? {
        val result = exec(id, listOf("/usr/local/bin/node", "-v"), timeoutMs = 30_000)
        return result.stdout.trim().takeIf { result.exitCode == 0 && it.startsWith("v") }
    }

    private fun readAsync(input: java.io.InputStream): java.util.concurrent.CompletableFuture<String> {
        val future = java.util.concurrent.CompletableFuture<String>()
        Thread {
            val text = runCatching { input.bufferedReader().use { it.readText() } }.getOrDefault("")
            future.complete(text)
        }.apply {
            isDaemon = true
            name = "proot-io"
        }.start()
        return future
    }

    /** 一次执行的结果。[failureMessage] 非空表示根本没跑起来。 */
    data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false,
        val failureMessage: String? = null,
    ) {
        val ok: Boolean get() = failureMessage == null && !timedOut && exitCode == 0

        companion object {
            fun failure(message: String) = ExecResult(-1, "", "", failureMessage = message)
        }
    }

    private companion object {
        const val TAG = "ProotRuntime"
        const val DEFAULT_TIMEOUT_MS = 120_000L
    }
}
