package io.zer0.muse.runtime

import android.content.Context
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

internal class ProotProbe(private val context: Context) {

    suspend fun nodeVersion(rootfsDir: File): String? = healthCheck(rootfsDir).nodeVersion

    suspend fun healthCheck(rootfsDir: File, revision: String? = null): RuntimeHealthCheck = withContext(Dispatchers.IO) {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val requiredNativeFiles = listOf(
            "libmuse_proot.so",
            "libmuse_proot_loader.so",
            "libtalloc.so",
            "libandroid-shmem.so",
        )
        val missingNative = requiredNativeFiles
            .map { File(nativeDir, it) }
            .filterNot(File::isFile)
            .map { it.name }
        if (missingNative.isNotEmpty()) {
            return@withContext RuntimeHealthCheck(
                revision = revision,
                healthy = false,
                missingPaths = missingNative,
                stderrTail = "缺少 native runtime 文件: ${missingNative.joinToString()}",
                failureKind = RuntimeFailureKind.ABI,
            )
        }

        val requiredRootfsPaths = listOf(
            "usr/local/bin/node",
            "usr/local/lib/node_modules/npm/bin/npm-cli.js",
            "usr/bin",
            "tmp",
        )
        val missingRootfs = requiredRootfsPaths
            .map { File(rootfsDir, it) }
            .filterNot(File::exists)
            .map { it.relativeTo(rootfsDir).invariantSeparatorsPath }
        if (missingRootfs.isNotEmpty()) {
            return@withContext RuntimeHealthCheck(
                revision = revision,
                healthy = false,
                missingPaths = missingRootfs,
                stderrTail = "rootfs 缺少关键路径: ${missingRootfs.joinToString()}",
                failureKind = RuntimeFailureKind.BROKEN,
            )
        }

        val node = runProbe(rootfsDir, nativeDir, listOf("/usr/local/bin/node", "-v"))
        if (node.timedOut) {
            return@withContext RuntimeHealthCheck(
                revision = revision,
                healthy = false,
                stderrTail = tail(node.stderr),
                failureKind = RuntimeFailureKind.STALLED,
            )
        }
        val nodeVersion = node.stdout.trim()
        if (node.exitCode != 0 || !nodeVersion.startsWith("v")) {
            return@withContext RuntimeHealthCheck(
                revision = revision,
                healthy = false,
                stderrTail = tail(node.stderr),
                failureKind = RuntimeFailureKind.SELF_CHECK,
            )
        }

        val npm = runProbe(
            rootfsDir,
            nativeDir,
            listOf("/usr/local/bin/node", "/usr/local/lib/node_modules/npm/bin/npm-cli.js", "--version"),
        )
        if (npm.timedOut || npm.exitCode != 0 || npm.stdout.trim().isEmpty()) {
            return@withContext RuntimeHealthCheck(
                revision = revision,
                healthy = false,
                nodeVersion = nodeVersion,
                stderrTail = tail(npm.stderr),
                failureKind = if (npm.timedOut) RuntimeFailureKind.STALLED else RuntimeFailureKind.SELF_CHECK,
            )
        }
        RuntimeHealthCheck(
            revision = revision,
            healthy = true,
            nodeVersion = nodeVersion,
            npmVersion = npm.stdout.trim(),
        )
    }

    private fun runProbe(rootfsDir: File, nativeDir: File, argv: List<String>): ProbeResult {
        val proot = File(nativeDir, "libmuse_proot.so")
        val loader = File(nativeDir, "libmuse_proot_loader.so")
        val tmp = File(context.filesDir, "runtime/tmp").apply { mkdirs() }
        val command = buildList {
            add(proot.absolutePath)
            addAll(listOf("--link2symlink", "-0", "-r", rootfsDir.absolutePath))
            listOf("/dev", "/proc", "/sys").forEach { addAll(listOf("-b", it)) }
            addAll(listOf("-w", "/"))
            addAll(argv)
        }
        return runCatching {
            val process = ProcessBuilder(command).apply {
                environment().apply {
                    put("PROOT_LOADER", loader.absolutePath)
                    put("PROOT_TMP_DIR", tmp.absolutePath)
                    put("LD_LIBRARY_PATH", nativeDir.absolutePath)
                    put("HOME", "/root")
                    put("PATH", "/usr/local/bin:/usr/bin:/bin")
                }
            }.start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val stdoutThread = Thread {
                process.inputStream.bufferedReader().use { stdout.append(it.readText()) }
            }.apply {
                isDaemon = true
                name = "runtime-probe-stdout"
            }
            val stderrThread = Thread {
                process.errorStream.bufferedReader().use { stderr.append(it.readText()) }
            }.apply {
                isDaemon = true
                name = "runtime-probe-stderr"
            }
            stdoutThread.start()
            stderrThread.start()
            val finished = process.waitFor(SELF_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(PROCESS_CLEANUP_MS, TimeUnit.MILLISECONDS)
            }
            stdoutThread.join(PROCESS_CLEANUP_MS)
            stderrThread.join(PROCESS_CLEANUP_MS)
            ProbeResult(
                exitCode = if (finished) process.exitValue() else -1,
                stdout = stdout.toString(),
                stderr = stderr.toString(),
                timedOut = !finished,
            )
        }.getOrElse { error ->
            Logger.e(TAG, "运行环境自检启动失败: ${error.message}", error)
            ProbeResult(-1, "", error.message ?: error.javaClass.simpleName, timedOut = false)
        }
    }

    private fun tail(text: String): String = text.takeLast(STDERR_TAIL_CHARS)

    private data class ProbeResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
    )

    private companion object {
        const val TAG = "ProotProbe"
        const val SELF_CHECK_TIMEOUT_MS = 30_000L
        const val PROCESS_CLEANUP_MS = 2_000L
        const val STDERR_TAIL_CHARS = 1_200
    }
}
