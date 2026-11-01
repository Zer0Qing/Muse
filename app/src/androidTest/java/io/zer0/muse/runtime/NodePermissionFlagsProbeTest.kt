package io.zer0.muse.runtime

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * P0-7-3 的**前置探针**：先量清"加了 `--permission` 的 Node 在这台设备上还起不起得来"，
 * 再决定要不要把它接进插件路径。
 *
 * 为什么不直接接上去然后跑测试：Node 的权限模型会拒绝**所有**路径读取，
 * 包括它自己启动时可能要读的东西（icu 数据、snapshot 附带文件）。
 * 如果盲目加旗标，症状会是"插件全部启动失败"，而且报错信息在子进程里，很难归因。
 *
 * 这个探针只做观测、不改产品代码：三种组合各跑一次，把结果原样打进 logcat。
 */
@RunWith(AndroidJUnit4::class)
class NodePermissionFlagsProbeTest {

    private data class Probe(val label: String, val args: List<String>)

    @Test
    fun probeNodePermissionFlags() = runBlockingUnit {
        val context = ApplicationProvider.getApplicationContext<Context>()
        MuseRuntime.ensureData(context).getOrThrow()
        val node = MuseRuntime.nodeBinary(context)
        val work = File(context.filesDir, "probe-permissions").apply { mkdirs() }

        val script = File(work, "probe.js").apply {
            writeText(
                """
                const fs = require('fs');
                let denied = 'ALLOWED';
                try {
                  fs.readFileSync('${probeTargetPath(context)}');
                } catch (e) { denied = String(e.code || e.message).toUpperCase(); }
                let selfRead = 'OK';
                try { selfRead = fs.readFileSync(process.argv[1]).length > 0 ? 'OK' : 'EMPTY'; }
                catch (e) { selfRead = String(e.code || e.message); }
                let net = 'PRESENT';
                try { net = typeof fetch; } catch (e) { net = 'THROW'; }
                process.stdout.write(JSON.stringify({ denied: denied, selfRead: selfRead, fetchType: net }) + '\n');
                """.trimIndent(),
            )
        }

        val probes = listOf(
            Probe("无旗标基线", listOf(script.absolutePath)),
            Probe(
                "--permission + 放行脚本目录",
                listOf("--permission", "--allow-fs-read=${work.absolutePath}", script.absolutePath),
            ),
            Probe(
                "--permission + 只放行 node 二进制目录",
                listOf("--permission", "--allow-fs-read=${node.parentFile.absolutePath}", script.absolutePath),
            ),
        )

        val report = probes.joinToString("\n") { probe ->
            val output = runNode(node, probe.args, context)
            "  [${probe.label}] -> $output"
        }
        Log.i(TAG, "PROBE RESULT\n$report")

        // 基线必须能跑，否则这台设备/node 本身有问题，后面的判断都不成立
        assertTrue("基线没跑起来，无法判断旗标影响", report.contains("\"denied\""))
    }

    private fun probeTargetPath(context: Context): String {
        val target = File(context.filesDir, "plugin_configs.json")
        if (!target.exists()) target.writeText("{}")
        return target.absolutePath.replace("\\", "\\\\")
    }

    private fun runNode(node: File, args: List<String>, context: Context): String =
        runCatching {
            val builder = ProcessBuilder(listOf(node.absolutePath) + args).apply {
                environment().clear()
                environment().putAll(MuseRuntime.buildEnv(context))
                redirectErrorStream(true)
            }
            val process = builder.start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(20, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@runCatching "TIMEOUT output=${output.take(300)}"
            }
            "exit=${process.exitValue()} out=${output.trim().take(400)}"
        }.getOrElse { "LAUNCH-FAIL ${it.message}" }

    private fun runBlockingUnit(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }

    private companion object {
        const val TAG = "NodeProbe"
    }
}
