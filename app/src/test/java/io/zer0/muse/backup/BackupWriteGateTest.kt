package io.zer0.muse.backup

import io.zer0.common.ProcessWriteGate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupWriteGateTest {

    @After
    fun tearDown() {
        ProcessWriteGate.end()
    }

    @Test
    fun `backup import gate blocks concurrent writers and always releases`() = runBlocking {
        var observedInsideGate = false

        val result = withBackupImportWriteGate {
            observedInsideGate = ProcessWriteGate.restoring
            assertFalse(ProcessWriteGate.begin())
            "imported"
        }

        assertEquals("imported", result)
        assertTrue(observedInsideGate)
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
