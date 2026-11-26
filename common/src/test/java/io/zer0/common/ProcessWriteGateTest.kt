package io.zer0.common

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进程写闸的恢复代数。
 *
 * 代数不是拿来「好看」的：模型调用可能在恢复窗口完整开合之后才返回，
 * 只靠 [ProcessWriteGate.restoring] 会把那种过期结果当成最新的写回去。
 */
class ProcessWriteGateTest {

    @After
    fun resetGate() {
        // 闸是进程级单例，测试失败也不能把 restoring 留在 true。
        ProcessWriteGate.end()
    }

    @Test
    fun `generation only advances when a restore window is entered`() {
        val before = ProcessWriteGate.generation
        assertTrue(ProcessWriteGate.begin())
        assertEquals(before + 1, ProcessWriteGate.generation)
        ProcessWriteGate.end()
        assertEquals("退出窗口不改变代数", before + 1, ProcessWriteGate.generation)
    }

    @Test
    fun `nested begin is rejected and does not advance the generation`() {
        assertTrue(ProcessWriteGate.begin())
        val inside = ProcessWriteGate.generation
        assertFalse("恢复进行中不允许重复进入", ProcessWriteGate.begin())
        assertEquals(inside, ProcessWriteGate.generation)
    }

    @Test
    fun `generation keeps advancing across separate windows`() {
        val start = ProcessWriteGate.generation
        repeat(3) {
            assertTrue(ProcessWriteGate.begin())
            ProcessWriteGate.end()
        }
        assertEquals(start + 3, ProcessWriteGate.generation)
    }
}