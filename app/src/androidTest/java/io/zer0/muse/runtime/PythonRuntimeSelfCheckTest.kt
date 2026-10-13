package io.zer0.muse.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.zer0.muse.tools.PythonScriptTool
import io.zer0.muse.tools.script.PythonSkillEngine
import io.zer0.muse.tools.script.SkillEngineResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v2.7 扩展运行时（Python）自检：内置 Python 运行时全链路验证（模拟器 / 真机 connected）。
 *
 * 覆盖链路：
 *  t1 stub 二进制存在（nativeLibraryDir 落盘）
 *  t2 exec 成功（W^X 绕行验证）+ python -V
 *  t3 stdlib 解压（assets）
 *  t4 基础求值（print / 表达式）
 *  t5 标准库 C 扩展加载（zlib / hashlib / sqlite3 / ssl）
 *  t6 文件读写
 *  t7 网络 + TLS（urllib + _ssl）
 *  t8 PythonSkillEngine 端到端（结果协议 + logs）
 *  t9 execute_python_script 工具层（JSON 返回结构）
 *  t10 错误传播（脚本异常 → ok=false）
 *
 * 运行：
 *  ./gradlew :app:connectedDebugAndroidTest \
 *    -Pandroid.testInstrumentationRunnerArguments.class=io.zer0.muse.runtime.PythonRuntimeSelfCheckTest
 */
@RunWith(AndroidJUnit4::class)
class PythonRuntimeSelfCheckTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun t1_pythonStubExists() {
        val bin = PythonRuntime.pythonBinary(ctx)
        assertTrue("python stub 应存在于 nativeLibraryDir: ${bin.absolutePath}", bin.isFile)
        assertTrue("stub 大小异常: ${bin.length()}", bin.length() > 1_000L)
    }

    @Test
    fun t2_pythonVersion() {
        runBlocking {
            val r = PythonRuntime.execPython(ctx, listOf("-V"), timeoutMs = 30_000)
            assertEquals("stderr=${r.stderr} timedOut=${r.timedOut}", 0, r.exitCode)
            assertTrue("输出异常: ${r.stdout}${r.stderr}", (r.stdout + r.stderr).contains("3.14"))
        }
    }

    @Test
    fun t3_stdlibExtract() {
        runBlocking {
            val res = PythonRuntime.ensureData(ctx)
            assertTrue("解压失败: ${res.exceptionOrNull()?.message}", res.isSuccess)
            assertTrue("os.py 应存在", java.io.File(PythonRuntime.stdlibDir(ctx), "os.py").isFile)
        }
    }

    @Test
    fun t4_basicEval() {
        runBlocking {
            PythonRuntime.ensureData(ctx).getOrThrow()
            val r = PythonRuntime.execPython(
                ctx,
                listOf("-c", "print(40 + 2)"),
                timeoutMs = 30_000,
            )
            assertEquals("stderr=${r.stderr}", 0, r.exitCode)
            assertTrue("输出异常: ${r.stdout}", r.stdout.trim() == "42")
        }
    }

    @Test
    fun t5_cExtensions() {
        runBlocking {
            PythonRuntime.ensureData(ctx).getOrThrow()
            val code = """
                import zlib, hashlib, sqlite3, ssl, json, re
                h = hashlib.sha256(b'x').hexdigest()
                z = zlib.compress(b'muse')
                con = sqlite3.connect(':memory:')
                con.execute('create table t(a)')
                con.execute('insert into t values (1)')
                n = con.execute('select count(*) from t').fetchone()[0]
                print('ext-ok', len(h), len(z), n, ssl.OPENSSL_VERSION.split()[1])
            """.trimIndent()
            val r = PythonRuntime.execPython(ctx, listOf("-c", code), timeoutMs = 60_000)
            assertEquals("stderr=${r.stderr}", 0, r.exitCode)
            assertTrue("输出异常: ${r.stdout}", r.stdout.contains("ext-ok"))
        }
    }

    @Test
    fun t6_fileIo() {
        runBlocking {
            PythonRuntime.ensureData(ctx).getOrThrow()
            val target = java.io.File(PythonRuntime.tmpDir(ctx), "py-io-test.txt")
            val code = """
                p = r'${target.absolutePath}'
                open(p, 'w').write('muse-python')
                print(open(p).read())
            """.trimIndent()
            val r = PythonRuntime.execPython(ctx, listOf("-c", code), timeoutMs = 30_000)
            assertEquals("stderr=${r.stderr}", 0, r.exitCode)
            assertTrue("输出异常: ${r.stdout}", r.stdout.contains("muse-python"))
        }
    }

    @Test
    fun t7_networkTls() {
        runBlocking {
            PythonRuntime.ensureData(ctx).getOrThrow()
            val code = """
                import urllib.request, ssl
                try:
                    with urllib.request.urlopen('https://www.python.org/', timeout=30) as resp:
                        print('net-ok', resp.status)
                except Exception as e:
                    print('net-fail', type(e).__name__, e)
            """.trimIndent()
            val r = PythonRuntime.execPython(ctx, listOf("-c", code), timeoutMs = 90_000)
            assertEquals("stderr=${r.stderr}", 0, r.exitCode)
            assertTrue("TLS 网络失败: ${r.stdout}", r.stdout.contains("net-ok"))
        }
    }

    @Test
    fun t8_skillEngineProtocol() {
        runBlocking {
            PythonRuntime.ensureData(ctx).getOrThrow()
            val result = PythonSkillEngine(ctx).eval(
                script = "print('hello from script')\nresult = {'sum': 6 * 7}",
                timeoutMs = 30_000,
            )
            assertTrue("应成功: $result", result is SkillEngineResult.Success)
            val ok = result as SkillEngineResult.Success
            assertTrue("value 应含 sum=42: ${ok.valueJson}", ok.valueJson.contains("42"))
            assertTrue("logs 应捕获 print: ${ok.consoleLogs}", ok.consoleLogs.any { it.contains("hello from script") })
        }
    }

    @Test
    fun t9_toolLayerJson() {
        runBlocking {
            PythonRuntime.ensureData(ctx).getOrThrow()
            val json = PythonScriptTool.execute(
                ctx,
                mapOf("code" to "result = [1, 2, 3]"),
            )
            assertTrue("返回应为 JSON: $json", json.contains("\"result\""))
            assertTrue("应含数组值: $json", json.contains("[1,2,3]"))
            assertTrue("error 应为 null: $json", json.contains("\"error\":null"))
        }
    }

    @Test
    fun t10_errorPropagation() {
        runBlocking {
            PythonRuntime.ensureData(ctx).getOrThrow()
            val result = PythonSkillEngine(ctx).eval(
                script = "raise ValueError('boom')",
                timeoutMs = 30_000,
            )
            assertTrue("应失败: $result", result is SkillEngineResult.Error)
            assertTrue(
                "错误应含 ValueError: ${(result as SkillEngineResult.Error).message}",
                result.message.contains("ValueError"),
            )
        }
    }

    /** v2.7 P2：插件 Python 函数调用（callPluginFunction）。 */
    @Test
    fun t11_pluginFunctionCall() {
        runBlocking {
            PythonRuntime.ensureData(ctx).getOrThrow()
            val entry = """
                def greet(args):
                    return {"msg": "hello " + str(args.get("name", "world"))}
            """.trimIndent()
            val result = PythonSkillEngine(ctx).callPluginFunction(
                entryCode = entry,
                functionName = "greet",
                argsJson = "[{\"name\": \"muse\"}]",
                timeoutMs = 30_000,
            )
            assertTrue("插件函数调用应成功: $result", result is SkillEngineResult.Success)
            val ok = result as SkillEngineResult.Success
            assertTrue("返回值应含 hello muse: ${ok.valueJson}", ok.valueJson.contains("hello muse"))
        }
    }

    /** v2.7 P2：插件入口缺函数时给可读错误。 */
    @Test
    fun t12_pluginMissingFunction() {
        runBlocking {
            PythonRuntime.ensureData(ctx).getOrThrow()
            val result = PythonSkillEngine(ctx).callPluginFunction(
                entryCode = "def other(args):\n    return 1\n",
                functionName = "missing_fn",
                argsJson = "[{}]",
                timeoutMs = 30_000,
            )
            assertTrue("应失败: $result", result is SkillEngineResult.Error)
            assertTrue(
                "应提示未定义函数: ${(result as SkillEngineResult.Error).message}",
                result.message.contains("missing_fn"),
            )
        }
    }

    /** v2.7 P3：pip 引导（ensurepip）与纯 Python 包安装。 */
    @Test
    fun t13_pipBootstrap() {
        runBlocking {
            val res = PythonRuntime.ensurePip(ctx)
            assertTrue("ensurepip 失败: ${res.exceptionOrNull()?.message}", res.isSuccess)
            val r = PythonRuntime.execPython(ctx, listOf("-m", "pip", "--version"), timeoutMs = 60_000)
            assertEquals("stderr=${r.stderr}", 0, r.exitCode)
            assertTrue("pip 版本输出异常: ${r.stdout}", r.stdout.contains("pip"))
        }
    }

    /** v2.7 P3：pip 安装纯 Python 包并 import。 */
    @Test
    fun t14_pipInstallPurePythonPackage() {
        runBlocking {
            PythonRuntime.ensurePip(ctx).getOrThrow()
            // 选一个体积小、纯 Python、无 C 扩展的包
            val install = PythonRuntime.execPython(
                ctx,
                listOf("-m", "pip", "install", "--no-cache-dir", "six"),
                timeoutMs = 180_000,
            )
            assertEquals("安装失败: ${install.stdout}\n${install.stderr}", 0, install.exitCode)
            val use = PythonRuntime.execPython(
                ctx,
                listOf("-c", "import six; result = six.__version__; print('six-ok', result)"),
                timeoutMs = 60_000,
            )
            assertEquals("stderr=${use.stderr}", 0, use.exitCode)
            assertTrue("应能 import six: ${use.stdout}", use.stdout.contains("six-ok"))
        }
    }
}
