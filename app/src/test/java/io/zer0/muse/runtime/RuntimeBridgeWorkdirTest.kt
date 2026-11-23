package io.zer0.muse.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeBridgeWorkdirTest {

    @Test
    fun `host files paths map to the guest mount without matching sibling prefixes`() {
        assertEquals("/muse-files", RuntimeBridge.toGuestPath("/data/user/0/app/files", "/data/user/0/app/files"))
        assertEquals(
            "/muse-files/work/scripts",
            RuntimeBridge.toGuestPath("/data/user/0/app/files", "/data/user/0/app/files/work/scripts"),
        )
        assertEquals(
            "/data/user/0/app/files-copy/scripts",
            RuntimeBridge.toGuestPath("/data/user/0/app/files", "/data/user/0/app/files-copy/scripts"),
        )
        assertEquals("/tmp/external", RuntimeBridge.toGuestPath("/data/user/0/app/files", "/tmp/external"))
    }
}
