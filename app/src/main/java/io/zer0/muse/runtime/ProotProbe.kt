package io.zer0.muse.runtime

import android.content.Context
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 对**尚未写指针**的 rootfs 目录做一次探测。
 *
 * 安装流程需要它：自检发生在 `current.json` 写入之前，而 [ProotRuntime.exec] 只认指针指向的目录。
 * 与其在安装期间临时改指针（失败会留下脏状态），不如让探测直接吃目录路径。
 */
internal class ProotProbe(private val context: Context) {

    /** 返回 `v24.22.1` 这样的版本号；跑不起来返回 null。 */
    suspend fun nodeVersion(rootfsDir: File): String? = withContext(Dispatchers.IO) {
        val bins = File(context.applicationInfo.nativeLibraryDir)
        val proot = File(bins, "libmuse_proot.so")
        val loader = File(bins, "libmuse_proot_loader.so")
        if (!proot.isFile || !loader.isFile) {
            Logger.w(TAG, "jniLibs 里没有 proot，跳不过自检")
            return@withContext null
        }
        val tmp = File(context.filesDir, "runtime/tmp").apply { mkdirs() }
        val command = listOf(
            proot.absolutePath,
            "--link2symlink",
            "-0",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-w", "/",
            "/usr/local/bin/node", "-v",
        )
        runCatching {
            val process = ProcessBuilder(command).apply {
                environment().apply {
                    put("PROOT_LOADER", loader.absolutePath)
                    put("PROOT_TMP_DIR", tmp.absolutePath)
                    put("LD_LIBRARY_PATH", bins.absolutePath)
                    put("HOME", "/root")
                    put("PATH", "/usr/local/bin:/usr/bin:/bin")
                }
            }.start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outThread = Thread { process.inputStream.bufferedReader().use { stdout.append(it.readText()) } }
                .apply { isDaemon = true }
            val errThread = Thread { process.errorStream.bufferedReader().use { stderr.append(it.readText()) } }
                .apply { isDaemon = true }
            outThread.start(); errThread.start()
            val finished = process.waitFor(SELF_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                Logger.w(TAG, "自检超时（${SELF_CHECK_TIMEOUT_MS}ms）")
                return@withContext null
            }
            outThread.join(2_000); errThread.join(2_000)
            val version = stdout.toString().trim()
            if (process.exitValue() == 0 && version.startsWith("v")) {
                version
            } else {
                Logger.w(TAG, "自检失败，exit=${process.exitValue()} stderr=${stderr.toString().take(300)}")
                null
            }
        }.getOrElse { e ->
            Logger.e(TAG, "自检异常: ${e.message}", e)
            null
        }
    }

    private companion object {
        const val TAG = "ProotProbe"
        const val SELF_CHECK_TIMEOUT_MS = 30_000L
    }
}
