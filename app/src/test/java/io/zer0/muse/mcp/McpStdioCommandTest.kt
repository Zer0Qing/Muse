package io.zer0.muse.mcp

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P1-A: stdio 命令解析器纯逻辑测试。
 *
 * 覆盖：分词（引号）/ node, npm, npx 内置运行时映射 / 非法命令与越界路径拒绝。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class McpStdioCommandTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        // Robolectric 不预置 nativeLibraryDir（真机由系统提供），补一个稳定路径供路径映射断言。
        context.applicationInfo.nativeLibraryDir = "/tmp/muse-libs"
    }

    @Test
    fun tokenizeHandlesQuotesAndSpaces() {
        assertEquals(
            listOf("npx", "-y", "some package", "arg"),
            McpStdioCommand.tokenize("npx -y \"some package\" arg"),
        )
        assertEquals(emptyList<String>(), McpStdioCommand.tokenize("   "))
    }

    @Test
    fun resolveNpxMapsToBuiltInRuntime() {
        val r = McpStdioCommand.resolve("npx -y @modelcontextprotocol/server-filesystem /sdcard", context)
        assertTrue("应为 Ok: $r", r is McpStdioCommand.Result.Ok)
        val argv = (r as McpStdioCommand.Result.Ok).argv
        assertTrue("argv[0] 应为内置 node: ${argv[0]}", argv[0].endsWith("libmuse_node.so"))
        assertTrue("argv[1] 应为 npx-cli.js: ${argv[1]}", argv[1].endsWith("npx-cli.js"))
        assertEquals(
            listOf("-y", "@modelcontextprotocol/server-filesystem", "/sdcard"),
            argv.drop(2),
        )
    }

    @Test
    fun resolveNpmMapsToCli() {
        val r = McpStdioCommand.resolve("npm install foo", context)
        assertTrue(r is McpStdioCommand.Result.Ok)
        val argv = (r as McpStdioCommand.Result.Ok).argv
        assertTrue("argv[0] 应为内置 node: ${argv[0]}", argv[0].endsWith("libmuse_node.so"))
        assertTrue("argv[1] 应为 npm-cli.js: ${argv[1]}", argv[1].endsWith("npm-cli.js"))
    }

    @Test
    fun resolveNodeKeepsScriptArg() {
        val r = McpStdioCommand.resolve("node /tmp/x.js --flag", context)
        assertTrue(r is McpStdioCommand.Result.Ok)
        val argv = (r as McpStdioCommand.Result.Ok).argv
        assertEquals(3, argv.size)
        assertEquals("/tmp/x.js", argv[1])
        assertEquals("--flag", argv[2])
    }

    @Test
    fun rejectUnsupportedCommand() {
        val r = McpStdioCommand.resolve("rm -rf /", context)
        assertTrue("应拒绝: $r", r is McpStdioCommand.Result.Error)
    }

    @Test
    fun rejectEmptyCommand() {
        val r = McpStdioCommand.resolve("   ", context)
        assertTrue(r is McpStdioCommand.Result.Error)
    }

    @Test
    fun rejectAbsolutePathOutsideRuntime() {
        val r = McpStdioCommand.resolve("/system/bin/sh -c echo", context)
        assertTrue("应拒绝系统路径: $r", r is McpStdioCommand.Result.Error)
    }
}
