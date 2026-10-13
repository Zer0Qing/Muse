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
 * v2.7 扩展运行时（Python）：内置 Python 沙盒运行时管理器。
 *
 * ## 与内置 Node 运行时（[MuseRuntime]）的关系
 *
 * 两者**同构**：同一套「可执行部分随 APK 进 jniLibs + 数据部分进 assets 解压」
 * 的分层，同一套 W^X 规避策略，同一套子进程执行与输出捕获机制。差异只有三点：
 *
 *  1. Python 没有官方的 `bin/python3` 可执行文件，用自写 stub（`libmuse_python.so`，
 *     见 app/src/main/jni/muse_python.c）承载可执行入口；
 *  2. Python 的 C 扩展（lib-dynload 下的 .so）必须落在只读区才能 dlopen，因此随
 *     jniLibs 分发，运行时用 symlink 挂回 stdlib 的 `lib-dynload` 目录；
 *  3. Python 的库名（`libssl_python.so` 等）由官方包已做区分，无需 patchelf 改名。
 *
 * ## 目录布局（运行时）
 *
 * ```
 * filesDir/muse-python/
 *   ├── lib/python3.14/          ← stdlib（assets 解压）
 *   │     └── lib-dynload/       ← symlink 指向 nativeLibraryDir 的 C 扩展
 *   ├── home/                    ← HOME / 工作目录
 *   ├── tmp/                     ← TMPDIR
 *   └── bin/                     ← python3 / pip 垫片入口
 * ```
 *
 * ## 与 nativeLibraryDir 的对应
 *
 * APK 安装时把 jniLibs 解压到 nativeLibraryDir，其中包含：
 *  - `libmuse_python.so`（stub 可执行）
 *  - `libpython3.14.so` / `libpython3.so`
 *  - `libssl_python.so` / `libcrypto_python.so` / `libsqlite3_python.so`
 *  - 58 个 cpython-314 的 C 扩展（.so）
 */
@Suppress("TooManyFunctions") // 运行时门面：路径访问器 + 执行入口集合，属设计如此
object PythonRuntime {
    private const val TAG = "PythonRuntime"
    private const val OUTPUT_DRAIN_TIMEOUT_MS = 30_000L

    /** Python 系列版本（目录名与主库名同步）。 */
    const val PYTHON_SERIES = "3.14"

    /** 完整版本号（自检展示用）。 */
    const val PYTHON_VERSION = "3.14.8"

    /** stub 可执行文件（jniLibs 内，命名需符合 lib*.so 规范）。 */
    const val PYTHON_STUB_LIB_NAME = "libmuse_python.so"

    /** 解释器主库（stub 用 dlopen 加载）。 */
    const val PYTHON_LIB_NAME = "libpython3.14.so"

    /** assets 中运行时数据包。 */
    private const val ASSETS_ZIP = "muse-python-assets.zip"

    /** 数据包版本标记（升级 assets 时同步修改，触发重新解压）。 */
    const val PYTHON_DATA_VERSION = "python-$PYTHON_VERSION-1"

    // ── 路径 ─────────────────────────────────────────────────────

    /** native 库目录（安装后解压出的 .so 所在目录）。 */
    fun nativeLibDir(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir)

    /** 内置 python stub 可执行文件。 */
    fun pythonBinary(ctx: Context): File = File(nativeLibDir(ctx), PYTHON_STUB_LIB_NAME)

    /** 解释器主库。 */
    fun pythonLib(ctx: Context): File = File(nativeLibDir(ctx), PYTHON_LIB_NAME)

    /** 运行时数据根目录（filesDir/muse-python）。 */
    fun runtimeDir(ctx: Context): File = File(ctx.filesDir, "muse-python")

    /** stdlib 根目录（…/lib/python3.14）。 */
    fun stdlibDir(ctx: Context): File = File(File(runtimeDir(ctx), "lib"), "python$PYTHON_SERIES")

    /** lib-dynload 目录（stdlib 内；运行时由 symlink 指向 nativeLibraryDir）。 */
    fun dynloadDir(ctx: Context): File = File(stdlibDir(ctx), "lib-dynload")

    /** 运行时 HOME 目录（子进程工作目录）。 */
    fun homeDir(ctx: Context): File = File(runtimeDir(ctx), "home")

    /** 子进程临时目录（TMPDIR）。 */
    fun tmpDir(ctx: Context): File = File(runtimeDir(ctx), "tmp")

    /** 运行时 bin 目录（PATH 首位，预留给 symlink/垫片）。 */
    fun binDir(ctx: Context): File = File(runtimeDir(ctx), "bin")

    /**
     * 合并后的 CA 证书 bundle（SSL_CERT_FILE 指向它）。
     *
     * ## 为什么需要它
     *
     * Android 的 `/system/etc/security/cacerts` 每个证书一个文件，文件名是
     * **OpenSSL old hash**（MD5-based）；而 OpenSSL 3.x 在 `SSL_CERT_DIR` 下
     * 按 **new hash**（SHA1-based）查找 —— 文件名对不上，导致 `SSL_CERT_DIR`
     * 指向该目录时 `loaded certs: 0`，TLS 握手报 CERTIFICATE_VERIFY_FAILED
     * （实测确认）。合并成单个 PEM bundle 走 SSL_CERT_FILE 可绕开 hash 索引。
     */
    fun caBundleFile(ctx: Context): File = File(runtimeDir(ctx), "cacert.pem")

    /** 运行时就绪（stub 二进制存在且 stdlib 已解压）。 */
    fun isReady(ctx: Context): Boolean = pythonBinary(ctx).isFile && File(stdlibDir(ctx), "os.py").isFile

    // ── 数据解压 ─────────────────────────────────────────────────

    private const val MARK_FILE = ".python-data-version"

    /**
     * 从 assets 解压 stdlib（幂等；版本标记命中时跳过）。
     */
    suspend fun ensureData(ctx: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val rt = runtimeDir(ctx)
            val mark = File(rt, MARK_FILE)
            val installed = runCatching { mark.readText().trim() }.getOrNull()
            if (installed == PYTHON_DATA_VERSION && File(stdlibDir(ctx), "os.py").isFile) {
                setupDynloadLinks(ctx)
                ensureCaBundle(ctx)
                return@runCatching
            }

            Logger.i(TAG, "解压 Python 运行时数据 ($installed -> $PYTHON_DATA_VERSION)")
            val libRoot = File(rt, "lib")
            libRoot.deleteRecursively()
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
            mark.writeText(PYTHON_DATA_VERSION)
            setupDynloadLinks(ctx)
            ensureCaBundle(ctx)
            Logger.i(TAG, "Python 运行时数据解压完成")
        }.onFailure { Logger.w(TAG, "Python 运行时数据解压失败: ${it.message}", it) }
    }

    /**
     * 把 stdlib 的 lib-dynload 目录指向 nativeLibraryDir 里的 C 扩展。
     *
     * ## 为什么要 symlink
     *
     * Python 按 `sys.path` 搜索 C 扩展，标准位置是 `<stdlib>/lib-dynload`。
     * 但 Android 10+ 不允许 dlopen 应用数据目录里的 .so（W^X），扩展只能放在
     * APK 安装解压出的**只读** nativeLibraryDir。因此这里把 lib-dynload 建成
     * 指向 nativeLibraryDir 的 symlink —— Python 照常按路径找到扩展，
     * dlopen 的实际文件在允许的只读区。
     *
     * 同时给每个扩展补建 `_ssl.so`（无 ABI 后缀）的 symlink，兼容部分
     * 按短名查找的路径。
     */
    private fun setupDynloadLinks(ctx: Context) {
        val nativeDir = nativeLibDir(ctx)
        val dynload = dynloadDir(ctx)

        // 1) lib-dynload 目录整体 → nativeLibraryDir
        //    先清掉 assets 里的占位目录，再建 symlink。
        if (dynload.exists() && !isSymlink(dynload)) {
            dynload.deleteRecursively()
        }
        if (!dynload.exists()) {
            dynload.parentFile?.mkdirs()
            runCatching {
                android.system.Os.symlink(nativeDir.absolutePath, dynload.absolutePath)
            }.onFailure {
                // 设备/文件系统不支持 symlink 时的兜底：退化为复制（部分扩展可能 dlopen 失败，
                // 但纯 Python 能力仍可用）。
                Logger.w(TAG, "lib-dynload symlink 失败，退化复制: ${it.message}")
                runCatching {
                    dynload.mkdirs()
                    nativeDir.listFiles { f -> f.isFile && f.name.contains(".cpython-") }
                        ?.forEach { f -> f.copyTo(File(dynload, f.name), overwrite = true) }
                }
            }
        }
    }

    private fun isSymlink(file: File): Boolean = runCatching {
        val attr = android.system.Os.lstat(file.absolutePath)
        android.system.OsConstants.S_ISLNK(attr.st_mode)
    }.getOrDefault(false)

    /**
     * 定位系统 CA 证书目录。
     *
     * Android 没有 Python 默认的 CA 存储，`ssl` 模块会因找不到信任根而报
     * CERTIFICATE_VERIFY_FAILED。系统 CA 位于这两个路径（后者为 Android 14+ 的
     * Conscrypt apex），文件名是 OpenSSL 的 subject-hash 形式，可直接用 SSL_CERT_DIR 读取。
     */
    private fun resolveCaCertDir(): String? = CA_CERT_DIRS.firstOrNull { File(it).isDirectory }

    private val CA_CERT_DIRS = listOf(
        "/apex/com.android.conscrypt/cacerts",
        "/system/etc/security/cacerts",
    )

    /**
     * 生成合并的 CA bundle（幂等）。
     * 把系统 CA 目录下所有证书文件按 PEM 拼接写入 [caBundleFile]。
     * 首次调用生成一次；已存在且非空则跳过。
     */
    suspend fun ensureCaBundle(ctx: Context): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val bundle = caBundleFile(ctx)
            if (bundle.isFile && bundle.length() > 0) return@runCatching bundle

            val dir = resolveCaCertDir() ?: error("未找到系统 CA 目录")
            bundle.parentFile?.mkdirs()
            bundle.outputStream().bufferedWriter().use { out ->
                File(dir).listFiles { f -> f.isFile }?.sortedBy { it.name }?.forEach { cert ->
                    val text = runCatching { cert.readText() }.getOrNull() ?: return@forEach
                    if (!text.contains("BEGIN CERTIFICATE")) return@forEach
                    out.write(text.trim())
                    out.write("\n")
                }
            }
            if (bundle.length() == 0L) error("系统 CA 目录无可读证书")
            Logger.i(TAG, "CA bundle 已生成: ${bundle.length()} bytes")
            bundle
        }.onFailure { Logger.w(TAG, "CA bundle 生成失败: ${it.message}") }
    }

    // ── pip（第三方包） ────────────────────────────────────────

    /**
     * 确保 pip 可用（幂等）。
     *
     * python.org 的 Android 包自带 `ensurepip`（含 pip wheel，见
     * stdlib/ensurepip/_bundled/pip-*.whl），但默认不安装。首次调用时执行
     * `python -m ensurepip`，把 pip 装到运行时目录。
     *
     * 装好之后即可用 `python -m pip install <包名>` 安装第三方包：
     * 纯 Python 包（py3-none-any wheel）可直接用；带 C 扩展的包需要
     * Android 平台 wheel（PyPI 上已有部分项目经 cibuildwheel 发布）。
     */
    suspend fun ensurePip(ctx: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureData(ctx).getOrThrow()
            if (isPipReady(ctx)) return@runCatching

            Logger.i(TAG, "安装 pip（ensurepip）…")
            val r = execPython(ctx, listOf("-m", "ensurepip", "--upgrade"), timeoutMs = 180_000)
            check(r.exitCode == 0) {
                "ensurepip 失败(exit=${r.exitCode}): ${(r.stderr.ifBlank { r.stdout }).take(300)}"
            }
            Logger.i(TAG, "pip 安装完成")
        }.onFailure { Logger.w(TAG, "pip 安装失败: ${it.message}") }
    }

    /** pip 是否已就绪。 */
    fun isPipReady(ctx: Context): Boolean = File(sitePackagesDir(ctx), "pip").isDirectory ||
        File(sitePackagesDir(ctx), "pip").isFile

    /** site-packages 目录（第三方包安装位置）。 */
    fun sitePackagesDir(ctx: Context): File = File(stdlibDir(ctx), "site-packages")

    // ── 子进程环境 ───────────────────────────────────────────────

    /**
     * 构造受控子进程环境。
     *
     * 关键变量：
     *  - PYTHONHOME：运行时根目录；Python 在此下按 `lib/pythonX.Y` 找标准库
     *  - PYTHONPATH：C 扩展搜索路径 —— 指向 nativeLibraryDir（58 个扩展随 jniLibs
     *    分发，是唯一保证可 dlopen 的只读区），外加 stdlib 的 lib-dynload 兼容位
     *  - HOME/TMPDIR：运行时数据目录（Android 无可用默认值）
     *  - PATH：运行时 bin + 系统路径
     *  - LD_LIBRARY_PATH：native 库目录（libpython 与 libssl_python 等依赖库定位）
     */
    fun buildEnv(ctx: Context, extra: Map<String, String> = emptyMap()): Map<String, String> {
        listOf(homeDir(ctx), tmpDir(ctx), binDir(ctx)).forEach { it.mkdirs() }
        return buildMap {
            put("HOME", homeDir(ctx).absolutePath)
            put("TMPDIR", tmpDir(ctx).absolutePath)
            put("PATH", "${binDir(ctx).absolutePath}:/system/bin:/system/xbin")
            put("LD_LIBRARY_PATH", nativeLibDir(ctx).absolutePath)
            put("SHELL", "/system/bin/sh")
            put("LANG", "C.UTF-8")
            // Python 定位标准库：PYTHONHOME 之下按 lib/pythonX.Y 查找
            put("PYTHONHOME", runtimeDir(ctx).absolutePath)
            // C 扩展：nativeLibraryDir 是 dlopen 的唯一可靠来源（只读区）
            put("PYTHONPATH", dynloadDir(ctx).absolutePath + ":" + nativeLibDir(ctx).absolutePath)
            put("PYTHONDONTWRITEBYTECODE", "1")
            put("PYTHONUNBUFFERED", "1")
            put("PYTHONIOENCODING", "utf-8")
            // Android 上 Python 无默认 CA 存储。
            // 优先用合并 bundle（SSL_CERT_FILE）；不存在时退化用 SSL_CERT_DIR。
            val bundle = caBundleFile(ctx)
            if (bundle.isFile) {
                put("SSL_CERT_FILE", bundle.absolutePath)
            } else {
                resolveCaCertDir()?.let { put("SSL_CERT_DIR", it) }
            }
            put("MUSE_PYTHON_BIN", pythonBinary(ctx).absolutePath)
            putAll(extra)
        }
    }

    // ── 子进程执行 ───────────────────────────────────────────────

    /** 子进程执行结果（与 [MuseRuntime.ExecResult] 对齐）。 */
    data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false,
        val stdoutFile: File? = null,
        val stderrFile: File? = null,
        val captureError: String? = null,
    )

    /** 执行内置 python（args 追加在 stub 路径之后）。 */
    suspend fun execPython(
        ctx: Context,
        args: List<String>,
        timeoutMs: Long = 30_000L,
        workdir: File? = homeDir(ctx),
        env: Map<String, String> = emptyMap(),
    ): ExecResult = exec(
        ctx = ctx,
        cmd = listOf(pythonBinary(ctx).absolutePath) + args,
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
        val outCapture = ToolOutputCapture(outputDirectory, "python_stdout")
        val errCapture = ToolOutputCapture(outputDirectory, "python_stderr")
        val captureFailure = AtomicReference<Throwable?>(null)
        val tOut = outputReaderThread("muse-python-stdout", process.inputStream, outCapture, captureFailure)
        val tErr = outputReaderThread("muse-python-stderr", process.errorStream, errCapture, captureFailure)
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
                    outCapture.finish(header = "[stdout 输出未能完整接收]", emptyMessage = "")
                }.getOrElse {
                    outCapture.close()
                    outCapture.savedFile?.let { "[stdout 部分输出文件: ${it.absolutePath}]" }.orEmpty()
                }
            val partialStderr =
                runCatching {
                    errCapture.finish(header = "[stderr 输出未能完整接收]", emptyMessage = "")
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

    @Suppress("TooGenericExceptionCaught") // 与 MuseRuntime 同型：读线程必须捕获 Throwable，否则 Error（如 OOM）会静默丢失
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
            // 与 MuseRuntime.outputReaderThread 同型：读线程必须捕获 Throwable，
            // 否则 Error（如 OOM）会静默丢失并让上层误判输出完整。
            failure.compareAndSet(null, error)
        }
    }, threadName)
}
