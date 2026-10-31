package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自检里的 CPU 探测量：正常时 ok，取不到时 warn，且不影响整体结论。 */
class RuntimeSelfCheckCpuProbeTest {

    private val okProbe = RuntimeSelfCheckTool.CpuProbe(entries = 8, parallelism = 8, envCount = 8)
    private val emptyProbe = RuntimeSelfCheckTool.CpuProbe(entries = 0, parallelism = 0, envCount = 8)

    @Test
    fun `cpu probe item exists as its own self-check entry`() {
        val section = RuntimeSelfCheckTool.buildCpuSection(okProbe)
        assertTrue("必须是独立一项", section.startsWith("\n- CPU 探测: "))
        assertTrue("需报 os.cpus().length", section.contains("os.cpus().length=8"))
        assertTrue("需报 availableParallelism", section.contains("os.availableParallelism()=8"))
        assertTrue("需报注入值", section.contains("MUSE_CPU_COUNT=8"))
    }

    @Test
    fun `ok when both values are positive`() {
        val section = RuntimeSelfCheckTool.buildCpuSection(okProbe)
        assertTrue("取到值应标 ok", section.contains("✓"))
        assertFalse("取到值时不应出现 warn", section.contains("warn"))
    }

    @Test
    fun `warn when values cannot be read`() {
        val section = RuntimeSelfCheckTool.buildCpuSection(emptyProbe)
        assertTrue("取不到值应标 warn", section.contains("warn"))
        assertFalse("不应标 ok", section.contains("✓"))
    }

    @Test
    fun `probe failure only warns and leaves overall verdict alone`() {
        val section = RuntimeSelfCheckTool.buildCpuSection(null)
        assertTrue(section.contains("warn"))
        assertFalse("不能把失败写成整体失败", section.contains("运行时不完整"))
        assertFalse("不能自带总结论行（只能说明不影响结论）", section.contains("结论:"))
        assertFalse("探测未执行时也不应报 ok", section.contains("✓"))
    }

    @Test
    fun `tool definition is registered under the stable name`() {
        assertEquals("runtime_selfcheck", RuntimeSelfCheckTool.toolDef().name)
        assertEquals(ToolRiskLevel.SAFE, RuntimeSelfCheckTool.toolDef().riskLevel)
    }
}
