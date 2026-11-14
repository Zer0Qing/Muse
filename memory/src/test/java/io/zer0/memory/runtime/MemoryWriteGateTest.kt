package io.zer0.memory.runtime

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryWriteGateTest {

    private val scope = MemoryScopeKey("default", "main", "default")

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
}
