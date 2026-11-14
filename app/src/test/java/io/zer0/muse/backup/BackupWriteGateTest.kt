package io.zer0.muse.backup

import io.zer0.common.ProcessWriteGate
import io.zer0.memory.runtime.DefaultMemoryWriteGate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupWriteGateTest {

    @After
    fun tearDown() {
        DefaultMemoryWriteGate.shared.close()
        ProcessWriteGate.end()
    }

    @Test
    fun `backup import gate blocks concurrent writers and always releases`() = runBlocking {
        var observedInsideGate = false

        val result = withBackupImportWriteGate {
            observedInsideGate = ProcessWriteGate.restoring
            assertTrue(
                "memory write gate should be blocked during backup import",
                DefaultMemoryWriteGate.shared.isBlocked(io.zer0.memory.runtime.MemoryScopeKey("default", "main", "default")),
            )
            assertFalse(ProcessWriteGate.begin())
            "imported"
        }

        assertEquals("imported", result)
        assertTrue(observedInsideGate)
        assertFalse(
            "memory write gate should be released after import",
            DefaultMemoryWriteGate.shared.isBlocked(io.zer0.memory.runtime.MemoryScopeKey("default", "main", "default")),
        )
        assertFalse(ProcessWriteGate.restoring)
    }

    @Test
    fun `backup import gate releases after failure`() = runBlocking {
        runCatching {
            withBackupImportWriteGate {
                error("parse failed")
            }
        }

        assertFalse(ProcessWriteGate.restoring)
        assertTrue(ProcessWriteGate.begin())
    }
}
