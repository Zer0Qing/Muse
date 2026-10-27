package io.zer0.muse.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 版本号形状：vX.Y.Z 或 X.Y.Z。只验证能输出版本，不绑定具体版本。 */
private val NODE_VERSION_SHAPE = Regex("""^v?\d+\.\d+\.\d+""")

/**
 * P0 打样自检：内置 Node 运行时全链路验证（模拟器 / 真机 connected 测试）。
 *
 * 覆盖链路：
 *  t1 二进制存在（nativeLibraryDir 落盘）
 *  t2 exec 成功（W^X 绕行验证） + node -v
 *  t3 JS 求值
 *  t4 动态库加载（zlib / crypto / ICU）
 *  t5 网络 + TLS（libssl / libcrypto / CA）
 *  t6 assets 数据解压（npm）
 *  t7 npm 执行
 *
 * 运行：
 *  ./gradlew :app:connectedDebugAndroidTest \
 *    -Pandroid.testInstrumentationRunnerArguments.class=io.zer0.muse.runtime.RuntimeSelfCheckTest
 */
@RunWith(AndroidJUnit4::class)
class RuntimeSelfCheckTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun t1_nodeBinaryExists() {
        val node = MuseRuntime.nodeBinary(ctx)
        assertTrue("node 应存在于 nativeLibraryDir: ${node.absolutePath}", node.isFile)
        assertTrue("node 大小异常: ${node.length()}", node.length() > 10_000_000L)
    }

    @Test
    fun t2_nodeVersion() {
        runBlocking {
            val r = MuseRuntime.execNode(ctx, listOf("-v"), timeoutMs = 20_000)
            assertEquals("stderr=${r.stderr} timedOut=${r.timedOut}", 0, r.exitCode)
            assertTrue("输出异常: ${r.stdout}", NODE_VERSION_SHAPE.containsMatchIn(r.stdout.trim()))
        }
    }

    @Test
    fun t3_nodeEval() {
        runBlocking {
            val r = MuseRuntime.execNode(ctx, listOf("-e", "console.log(40+2)"), timeoutMs = 20_000)
            assertEquals("stderr=${r.stderr}", 0, r.exitCode)
            assertEquals("42", r.stdout.trim())
        }
    }

    @Test
    fun t4_nativeDeps() {
        runBlocking {
            val js = """
                const z = require('zlib');
                const c = require('crypto');
                const g = z.gzipSync(Buffer.from('muse'));
                const h = c.createHash('sha256').update('x').digest('hex');
                const n = new Intl.NumberFormat('en-US').format(1234567.89);
                console.log('deps-ok', g.length, h.length, n);
            """.trimIndent()
            val r = MuseRuntime.execNode(ctx, listOf("-e", js), timeoutMs = 30_000)
            assertEquals("stderr=${r.stderr}", 0, r.exitCode)
            assertTrue("输出异常: ${r.stdout}", r.stdout.contains("deps-ok"))
        }
    }

    @Test
    fun t5_networkTls() {
        runBlocking {
            val js = "fetch('https://registry.npmjs.org/-/ping')" +
                ".then(r=>{console.log('net-ok',r.status);process.exit(0)})" +
                ".catch(e=>{console.error('net-fail:'+e.message);process.exit(2)})"
            val r = MuseRuntime.execNode(ctx, listOf("-e", js), timeoutMs = 90_000)
            assertEquals("stdout=${r.stdout} stderr=${r.stderr}", 0, r.exitCode)
            assertTrue(r.stdout.contains("net-ok"))
        }
    }

    @Test
    fun t6_npmDataExtract() {
        runBlocking {
            val res = MuseRuntime.ensureData(ctx)
            assertTrue("解压失败: ${res.exceptionOrNull()?.message}", res.isSuccess)
            assertTrue("npm-cli.js 应存在", MuseRuntime.npmCli(ctx).isFile)
        }
    }

    @Test
    fun t7_npmVersion() {
        runBlocking {
            MuseRuntime.ensureData(ctx).getOrThrow()
            val r = MuseRuntime.execNode(
                ctx,
                listOf(MuseRuntime.npmCli(ctx).absolutePath, "-v"),
                timeoutMs = 120_000,
            )
            assertEquals("stderr=${r.stderr}", 0, r.exitCode)
            assertTrue("npm 版本输出异常: ${r.stdout}", NODE_VERSION_SHAPE.containsMatchIn(r.stdout.trim()))
        }
    }

    @Test
    fun t8_symlinkExec() {
        runBlocking {
            // P1 前置验证：runtime/bin/node 符号链接可被 exec（npm/npx 名字解析基础）
            val bin = MuseRuntime.binDir(ctx).apply { mkdirs() }
            val link = java.io.File(bin, "node")
            link.delete()
            android.system.Os.symlink(MuseRuntime.nodeBinary(ctx).absolutePath, link.absolutePath)
            val r = MuseRuntime.exec(
                ctx = ctx,
                cmd = listOf(link.absolutePath, "-v"),
                timeoutMs = 20_000,
            )
            assertEquals("stderr=${r.stderr}", 0, r.exitCode)
            assertTrue("输出异常: ${r.stdout}", NODE_VERSION_SHAPE.containsMatchIn(r.stdout.trim()))
        }
    }

    @Test
    fun t10_shimNameResolutionAndShebang() {
        runBlocking {
            // P1-B 冒烟：sh 内按名字调 node/npm（名称映射）+ shebang 脚本直接执行（npx .bin 场景）
            MuseRuntime.ensureData(ctx).getOrThrow()

            val r1 = MuseRuntime.exec(ctx, listOf("/system/bin/sh", "-c", "node -v"), timeoutMs = 20_000)
            assertEquals("stderr=${r1.stderr}", 0, r1.exitCode)
            assertTrue("输出: ${r1.stdout}", NODE_VERSION_SHAPE.containsMatchIn(r1.stdout.trim()))

            val r2 = MuseRuntime.exec(ctx, listOf("/system/bin/sh", "-c", "npm -v"), timeoutMs = 60_000)
            assertEquals("stderr=${r2.stderr}", 0, r2.exitCode)
            assertTrue("输出: ${r2.stdout}", NODE_VERSION_SHAPE.containsMatchIn(r2.stdout.trim()))

            val script = java.io.File(MuseRuntime.binDir(ctx), "hello-tool")
            script.writeText("#!/usr/bin/env node\nconsole.log('shebang-ok', 6*7);\n")
            script.setExecutable(true, false)
            val r3 = MuseRuntime.exec(ctx, listOf("/system/bin/sh", "-c", script.absolutePath), timeoutMs = 20_000)
            assertEquals("stderr=${r3.stderr}", 0, r3.exitCode)
            assertTrue("输出: ${r3.stdout}", r3.stdout.contains("shebang-ok 42"))
        }
    }
}
