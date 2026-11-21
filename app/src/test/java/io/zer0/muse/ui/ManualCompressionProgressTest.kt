package io.zer0.muse.ui

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import io.zer0.muse.ui.common.ManualCompressionPhase
import io.zer0.muse.ui.common.runManualCompressionStages

class ManualCompressionProgressTest {

    @Test
    fun updateMemoryFirstShowsBothStagesInExecutionOrder() = runTest {
        val phases = mutableListOf<ManualCompressionPhase>()
        val operations = mutableListOf<String>()

        val result = runManualCompressionStages(
            updateMemoryFirst = true,
            onPhase = { phases.add(it) },
            updateMemory = { operations += "memory" },
            compress = {
                operations += "context"
                "done"
            },
        )

        assertEquals("done", result)
        assertEquals(
            listOf(
                ManualCompressionPhase.UPDATING_MEMORY,
                ManualCompressionPhase.COMPRESSING_CONTEXT,
            ),
            phases,
        )
        assertEquals(listOf("memory", "context"), operations)
    }

    @Test
    fun contextOnlyCompressionSkipsMemoryStage() = runTest {
        val phases = mutableListOf<ManualCompressionPhase>()

        runManualCompressionStages(
            updateMemoryFirst = false,
            onPhase = { phases.add(it) },
            updateMemory = { error("memory stage should not run") },
            compress = { Unit },
        )

        assertEquals(listOf(ManualCompressionPhase.COMPRESSING_CONTEXT), phases)
    }
}
