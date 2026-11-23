package io.zer0.muse.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuseNotificationIdPolicyTest {

    @Test
    fun `proactive notification range is isolated from fixed ids`() {
        assertTrue(isMuseProactiveNotificationId(0x1000_0000))
        assertTrue(isMuseProactiveNotificationId(0x1FFF_FFFF))
        assertFalse(isMuseProactiveNotificationId(0x0FFF_FFFF))
        assertFalse(isMuseProactiveNotificationId(0x2000_0000))
        assertFalse(isMuseProactiveNotificationId(1004))
    }
}
