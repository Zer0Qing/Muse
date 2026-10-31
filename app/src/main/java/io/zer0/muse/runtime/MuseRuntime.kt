package io.zer0.muse.runtime

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.tools.TOOL_OUTPUTS_DIR
import io.zer0.muse.tools.ToolOutputCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

/**
 * v2.x 扩展运行时（P0 打样）：内置 Node.js 沙盒运行时管理器。
 *
 * ## 背景
 * Muse 的应用内沙盒（技能脚本 / MCP stdio server / 终端）需要真实运行时
 * （Node.js / npm）才能执行"市面上的"扩展生态。受 Android 10+ W^X 限制
 * （targetSdk 29+ 不可 exec 应用数据目录中的文件），运行时采用如下架构：
 *
 *  - **可执行部分**（node 主程序 + 依赖共享库）随 APK 发布在 jniLibs
 *    （extractNativeLibs=true，安装时解压到 nativeLibraryDir），
 *    此目录不受 W^X 限制，可直接 exec / dlopen；
 *  - **纯数据部分**（npm 等 JS 文件）打包为 assets/muse-runtime-assets.zip，
 *    首次使用时解压到 filesDir/muse-runtime/；
 *  - 共享库以 lib*.so 命名规范分发（libmuse_node.so / libssl.so / ...），
 *    内部 SONAME/DT_NEEDED 已重写、RPATH=$ORIGIN，自包含加载。
 *
 * 使用入口：[isReady] 检查 / [ensureData] 解压 / [execNode] 执行。
 */
@Suppress("TooManyFunctions") // 运行时门面：路径访问器 + 执行入口集合，属设计如此
object MuseRuntime {
    private const val TAG = "MuseRuntime"
    private const val OUTPUT_DRAIN_TIMEOUT_MS = 30_000L

    /** 内置 node 二进制（jniLibs 内，命名需符合 lib*.so 规范）。 */
    const val NODE_LIB_NAME = "libmuse_node.so"

    /** assets 中运行时数据包。 */
    private const val ASSETS_ZIP = "muse-runtime-assets.zip"

    /** 传给子进程的 CPU 核数环境变量名（脚本包装层在原生取不到时据此兜底）。 */
    const val ENV_CPU_COUNT = "MUSE_CPU_COUNT"

    /** 数据包版本标记（升级 assets 时同步修改，触发重新解压）。 */
    const val RUNTIME_DATA_VERSION = "npm-11.21.1"

    // ── 路径 ─────────────────────────────────────────────────────

    /** native 库目录（安装后解压出的 .so 所在目录）。 */
    fun nativeLibDir(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir)

    /** 内置 node 可执行文件。 */
    fun nodeBinary(ctx: Context): File = File(nativeLibDir(ctx), NODE_LIB_NAME)

    /** 运行时数据根目录（filesDir/muse-runtime）。 */
    fun runtimeDir(ctx: Context): File = File(ctx.filesDir, "muse-runtime")

    /** npm 数据目录。 */
    fun npmDir(ctx: Context): File = File(runtimeDir(ctx), "npm")

    /** npm CLI 入口（JS 文件，由 node 执行）。 */
    fun npmCli(ctx: Context): File = File(npmDir(ctx), "bin/npm-cli.js")

    /** 运行时 HOME 目录（子进程工作目录）。 */
    fun homeDir(ctx: Context): File = File(runtimeDir(ctx), "home")

    /** 子进程临时目录（TMPDIR）。 */
    fun tmpDir(ctx: Context): File = File(runtimeDir(ctx), "tmp")

    /** 运行时 bin 目录（PATH 首位，预留给 symlink/垫片）。 */
    fun binDir(ctx: Context): File = File(runtimeDir(ctx), "bin")

    /** 运行时就绪（node 二进制存在且 npm 数据已解压）。 */
    fun isReady(ctx: Context): Boolean = nodeBinary(ctx).isFile && npmCli(ctx).isFile

    // ── 数据解压 ─────────────────────────────────────────────────

    private const val MARK_FILE = ".runtime-data-version"

    /**
     * 从 assets 解压运行时数据（幂等；版本标记命中时跳过）。
     */
    suspend fun ensureData(ctx: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val rt = runtimeDir(ctx)
            val mark = File(rt, MARK_FILE)
            val installed = runCatching { mark.readText().trim() }.getOrNull()
            if (installed == RUNTIME_DATA_VERSION && npmCli(ctx).isFile) {
                setupBinLinks(ctx)
                return@runCatching
            }

            Logger.i(TAG, "解压运行时数据 ($installed -> $RUNTIME_DATA_VERSION)")
            npmDir(ctx).deleteRecursively()
            ctx.assets.open(ASSETS_ZIP).use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val target = File(rt, entry.name)
                        val rootPath = rt.canonicalPath + File.separator
                        if (!target.canonicalPath.startsWith(rootPath)) {
                            throw SecurityException("zip 条目越界: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().buffered().use { out -> zip.copyTo(out) }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            mark.writeText(RUNTIME_DATA_VERSION)
            setupBinLinks(ctx)
            Logger.i(TAG, "运行时数据解压完成")
        }.onFailure { Logger.w(TAG, "运行时数据解压失败: ${it.message}", it) }
    }

    /**
     * P1-B: 准备运行时 bin 入口（symlink: node / npm / npx）。
     *
     * 供终端与子进程的 PATH 查找使用。npm/npx 目标为 JS 文件（先补可执行位，
     * 让 shell 的查找判定通过）；实际执行由 exec 垫片按 shebang 重写为 node。
     */
    private fun setupBinLinks(ctx: Context) {
        val bin = binDir(ctx).apply { mkdirs() }
        val npmBin = File(npmDir(ctx), "bin")
        listOf("npm-cli.js", "npx-cli.js").forEach { name ->
            File(npmBin, name).takeIf { it.isFile }?.setExecutable(true, false)
        }

        fun relink(name: String, target: File) {
            if (!target.isFile) return
            val link = File(bin, name)
            // lstat 存在（含断链）先删后建，保证指向最新目标
            runCatching { android.system.Os.lstat(link.absolutePath) }
                .onSuccess { runCatching { link.delete() } }
            runCatching { android.system.Os.symlink(target.absolutePath, link.absolutePath) }
                .onFailure { Logger.w(TAG, "bin symlink 创建失败($name): ${it.message}") }
        }
        relink("node", nodeBinary(ctx))
        relink("npm", File(npmBin, "npm-cli.js"))
        relink("npx", File(npmBin, "npx-cli.js"))
    }

    // ── 子进程环境 ───────────────────────────────────────────────

    /**
     * 构造受控子进程环境。
     *
     * 关键变量：
     *  - HOME/TMPDIR：指向运行时数据目录（Android 无可用默认值）
     *  - PATH：运行时 bin（预留）+ 系统路径
     *  - LD_LIBRARY_PATH：native 库目录（双保险；库内 RPATH=$ORIGIN 已覆盖）
     *  - SHELL：/system/bin/sh（Android 无 /bin/sh）
     *  - MUSE_CPU_COUNT：Java 侧看到的 CPU 核数（部分设备上 libuv 取不到，交给脚本层兜底）
     */
    fun buildEnv(ctx: Context, extra: Map<String, String> = emptyMap()): Map<String, String> {
        listOf(homeDir(ctx), tmpDir(ctx), binDir(ctx)).forEach { it.mkdirs() }
        return buildMap {
            put("HOME", homeDir(ctx).absolutePath)
            put("TMPDIR", tmpDir(ctx).absolutePath)
            put("PATH", "${binDir(ctx).absolutePath}:/system/bin:/system/xbin")
            put("LD_LIBRARY_PATH", nativeLibDir(ctx).absolutePath)
            put("SHELL", "/system/bin/sh")
            put("TERM", "xterm-256color")
            // P1-B: exec 垫片（名称映射 + shebang 解析；文件缺失时自动跳过）
            File(nativeLibDir(ctx), "libmuse_exec.so").takeIf { it.isFile }?.let {
                put("LD_PRELOAD", it.absolutePath)
            }
            put("MUSE_NODE_BIN", nodeBinary(ctx).absolutePath)
            put("MUSE_NPM_CLI", npmCli(ctx).absolutePath)
            put("MUSE_NPX_CLI", File(npmDir(ctx), "bin/npx-cli.js").absolutePath)
            put(ENV_CPU_COUNT, childCpuCount().toString())
            putAll(extra)
        }
    }

    /** 子进程可用的 CPU 核数；取不到时按 1 处理，避免下游拿到 0 再除零或退化。 */
    internal fun childCpuCount(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    // ── 子进程执行 ───────────────────────────────────────────────

    /** 子进程执行结果。 */
    data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false,
        val stdoutFile: File? = null,
        val stderrFile: File? = null,
        val captureError: String? = null,
    )

    /** 执行内置 node（args 追加在 node 路径之后）。 */
    suspend fun execNode(
        ctx: Context,
        args: List<String>,
        timeoutMs: Long = 30_000L,
        workdir: File? = homeDir(ctx),
        env: Map<String, String> = emptyMap(),
    ): ExecResult = exec(
        ctx = ctx,
        cmd = listOf(nodeBinary(ctx).absolutePath) + args,
        timeoutMs = timeoutMs,
        workdir = workdir,
        env = env,
    )

    /** 通用子进程执行（清空继承环境，使用 [buildEnv] 构造的环境）。 */
    suspend fun exec(
        ctx: Context,
        cmd: List<String>,
        timeoutMs: Long = 30_000L,
        workdir: File? = null,
        env: Map<String, String> = emptyMap(),
    ): ExecResult = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder(cmd)
        workdir?.let {
            it.mkdirs()
            pb.directory(it)
        }
        pb.environment().clear()
        pb.environment().putAll(buildEnv(ctx, env))

        val process = pb.start()
        val outputDirectory = File(ctx.filesDir, TOOL_OUTPUTS_DIR)
        val outCapture = ToolOutputCapture(outputDirectory, "node_stdout")
        val errCapture = ToolOutputCapture(outputDirectory, "node_stderr")
        val captureFailure = AtomicReference<Throwable?>(null)
        val tOut = outputReaderThread("muse-node-stdout", process.inputStream, outCapture, captureFailure)
        val tErr = outputReaderThread("muse-node-stderr", process.errorStream, errCapture, captureFailure)
        tOut.start()
        tErr.start()

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(3, TimeUnit.SECONDS)
        }
        tOut.join(OUTPUT_DRAIN_TIMEOUT_MS)
        tErr.join(OUTPUT_DRAIN_TIMEOUT_MS)
        if (tOut.isAlive) {
            runCatching { process.inputStream.close() }
            tOut.join(1_000)
            captureFailure.compareAndSet(null, IOException("stdout 输出流未能完整排空"))
        }
        if (tErr.isAlive) {
            runCatching { process.errorStream.close() }
            tErr.join(1_000)
            captureFailure.compareAndSet(null, IOException("stderr 输出流未能完整排空"))
        }

        val outputFailure = captureFailure.get()
        if (outputFailure != null) {
            val partialStdout =
                runCatching {
                    outCapture.finish(
                        header = "[stdout 输出未能完整接收]",
                        emptyMessage = "",
                    )
                }.getOrElse {
                    outCapture.close()
                    outCapture.savedFile?.let { "[stdout 部分输出文件: ${it.absolutePath}]" }.orEmpty()
                }
            val partialStderr =
                runCatching {
                    errCapture.finish(
                        header = "[stderr 输出未能完整接收]",
                        emptyMessage = "",
                    )
                }.getOrElse {
                    errCapture.close()
                    errCapture.savedFile?.let { "[stderr 部分输出文件: ${it.absolutePath}]" }.orEmpty()
                }
            return@withContext ExecResult(
                exitCode = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1,
                stdout = partialStdout,
                stderr = partialStderr,
                timedOut = !finished,
                stdoutFile = outCapture.savedFile,
                stderrFile = errCapture.savedFile,
                captureError = outputFailure.message ?: outputFailure.javaClass.simpleName,
            )
        }

        val stdout = outCapture.finish(header = "", emptyMessage = "")
        val stderr = errCapture.finish(header = "", emptyMessage = "")

        ExecResult(
            exitCode = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1,
            stdout = stdout,
            stderr = stderr,
            timedOut = !finished,
            stdoutFile = outCapture.savedFile,
            stderrFile = errCapture.savedFile,
        )
    }

    private fun outputReaderThread(
        threadName: String,
        input: InputStream,
        capture: ToolOutputCapture,
        failure: AtomicReference<Throwable?>,
    ): Thread = Thread({
        try {
            InputStreamReader(input, Charsets.UTF_8).buffered().use { reader ->
                val buffer = CharArray(8_192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    capture.append(String(buffer, 0, count))
                }
            }
        } catch (error: Throwable) {
            failure.compareAndSet(null, error)
        }
    }, threadName)
}
