package io.zer0.muse.web.a2a

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Bounds handler-owned A2A contexts so authenticated clients cannot create unlimited hidden sessions. */
class A2aContextRegistryTest {

    @Test
    fun `put and get preserve context to isolated session mapping`() {
        val registry = A2aContextRegistry(maxEntries = 2)
        assertNull(registry.put("ctx-1", "session-1"))
        assertEquals("session-1", registry.get("ctx-1"))
    }

    @Test
    fun `registry evicts least recently used session when full`() {
        val registry = A2aContextRegistry(maxEntries = 2)
        registry.put("ctx-1", "session-1")
        registry.put("ctx-2", "session-2")
        registry.get("ctx-1") // ctx-2 is now least-recently-used

        assertEquals("session-2", registry.put("ctx-3", "session-3"))
        assertNull(registry.get("ctx-2"))
        assertEquals("session-1", registry.get("ctx-1"))
        assertEquals("session-3", registry.get("ctx-3"))
        assertEquals(2, registry.size)
    }

    @Test
    fun `updating same context does not evict another context`() {
        val registry = A2aContextRegistry(maxEntries = 2)
        registry.put("ctx-1", "session-old")
        registry.put("ctx-2", "session-2")
        assertNull(registry.put("ctx-1", "session-new"))
        assertEquals("session-new", registry.get("ctx-1"))
        assertEquals("session-2", registry.get("ctx-2"))
        assertEquals(2, registry.size)
    }
}
