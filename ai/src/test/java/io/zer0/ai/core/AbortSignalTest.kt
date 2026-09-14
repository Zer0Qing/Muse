package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AbortSignalTest {
    @Test
    fun `abort listeners run once and are cleared`() {
        val signal = AbortSignal()
        var calls = 0
        signal.addAbortListener { calls++ }

        signal.abort()
        signal.abort()

        assertEquals(1, calls)
    }

    @Test
    fun `closed listener is not invoked and late listener runs immediately`() {
        val signal = AbortSignal()
        var calls = 0
        val registration = signal.addAbortListener { calls++ }
        registration.close()
        signal.abort()
        assertEquals(0, calls)

        signal.addAbortListener { calls++ }
        assertEquals(1, calls)
    }
}
