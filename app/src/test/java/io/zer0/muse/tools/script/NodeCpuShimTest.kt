package io.zer0.muse.tools.script

import io.zer0.muse.runtime.MuseRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置 Node 的 CPU 兜底脚本：结构断言（文本）+ 组合断言（纯函数）。
 *
 * 行为验证（真实 Node 上跑空/非空两种场景）无法进 JVM 单测，这里只锁住
 * 「什么条件下才覆盖、合成条目字段是否齐全、作用域是否隔离」。
 */
class NodeCpuShimTest {

    private val shim = NodeCpuShim.script()

    @Test
    fun `env key matches runtime injection`() {
        assertEquals(MuseRuntime.ENV_CPU_COUNT, NodeCpuShim.ENV_KEY)
        assertTrue("shim 必须读取注入的环境变量", shim.contains("process.env.MUSE_CPU_COUNT"))
    }

    @Test
    fun `cpus override only when native list is empty`() {
        assertTrue(
            "只有原生返回空数组时才覆写 os.cpus",
            shim.contains("if (envCount > 0 && readNativeCpus().length === 0)"),
        )
        assertTrue("需要覆写 os.cpus 本身", shim.contains("os.cpus = function ()"))
        assertTrue("合成条目数取注入值", shim.contains("for (var i = 0; i < envCount; i++)"))
    }

    @Test
    fun `synthetic entries carry fields npm packages read`() {
        assertTrue(shim.contains("model: 'Muse virtual CPU'"))
        assertTrue(shim.contains("speed: 0"))
        assertTrue(shim.contains("times: { user: 0, nice: 0, sys: 0, idle: 0, irq: 0 }"))
    }

    @Test
    fun `availableParallelism override only when native is not positive`() {
        assertTrue("原生非正数或抛错时才覆写", shim.contains("if (!(nativeValue > 0))"))
        assertTrue(shim.contains("os.availableParallelism = function () { return effective; }"))
        assertTrue("覆写值优先用原生探测到的核数", shim.contains("var effective = detected > 0 ? detected :"))
    }

    @Test
    fun `shim is scoped in an iife and does not leak globals`() {
        val lines = shim.lines().filter { it.isNotBlank() }
        assertEquals("第一行必须是自执行函数开头", ";(function () {", lines.first().trim())
        assertEquals("最后一行必须闭合自执行函数", "})();", lines.last().trim())
    }

    @Test
    fun `wrap puts shim before body and keeps body intact`() {
        val body = "console.log('user script');"
        val wrapped = NodeCpuShim.wrap(body)
        assertEquals(shim + "\n" + body, wrapped)
        assertTrue("shim 必须早于脚本主体执行", wrapped.indexOf("os.cpus = function") < wrapped.indexOf(body))
    }
}
