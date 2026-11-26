package io.zer0.memory.runtime

import io.zer0.common.ProcessWriteGate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryWriteGateTest {

    private val scope = MemoryScopeKey("default", "main", "default")

    @After
    fun resetProcessGate() {
        ProcessWriteGate.end()
    }

    @Test
    fun `gate blocks while open and allows after close`() {
        val gate = DefaultMemoryWriteGate()
        assertFalse(gate.isBlocked(scope))
        gate.open()
        assertTrue(gate.isBlocked(scope))
        gate.close()
        assertFalse(gate.isBlocked(scope))
    }

    @Test
    fun `withWritesPaused runs block`() = runTest {
        val gate = DefaultMemoryWriteGate()
        var value = 0
        gate.withWritesPaused(scope) { value = 42 }
        assertEquals(42, value)
    }

    @Test
    fun `process gate alone also blocks the memory gate`() {
        // 自动恢复只开进程闸(不开 DefaultMemoryWriteGate)，记忆写入仍必须停下。
        val gate = DefaultMemoryWriteGate()
        assertFalse(gate.isBlocked(scope))
        assertTrue(ProcessWriteGate.begin())
        assertTrue(gate.isBlocked(scope))
        ProcessWriteGate.end()
        assertFalse(gate.isBlocked(scope))
    }

    @Test
    fun `fence taken before a restore window is stale after the window closes`() {
        val gate = DefaultMemoryWriteGate()
        val fence = gate.fence()
        assertFalse("未发生恢复时栅栏有效", gate.isStale(fence))
        assertFalse(gate.blocksWrite(scope, fence))

        // 恢复窗口在「任务运行期间」完整开合 —— 任务返回时 restoring 已经是 false。
        assertTrue(ProcessWriteGate.begin())
        ProcessWriteGate.end()
        assertFalse(gate.isBlocked(scope))
        assertTrue("窗口开过就作废栅栏", gate.isStale(fence))
        assertTrue(gate.blocksWrite(scope, fence))
    }

    @Test
    fun `fence taken after a restore window stays valid`() {
        val gate = DefaultMemoryWriteGate()
        ProcessWriteGate.begin()
        ProcessWriteGate.end()
        val fence = gate.fence()
        assertFalse(gate.isStale(fence))
        assertFalse(gate.blocksWrite(scope, fence))
    }
}