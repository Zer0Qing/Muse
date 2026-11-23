package io.zer0.muse.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StableProactiveNotificationIdTest {
    @Test
    fun idsAreDeterministicAcrossCallsAndStayInMuseProactiveRange() {
        val first = MuseNotificationIdPolicy.notificationIdForOutcome("outcome-1")
        assertEquals(first, MuseNotificationIdPolicy.notificationIdForOutcome("outcome-1"))
        assertNotEquals(first, MuseNotificationIdPolicy.notificationIdForOutcome("outcome-2"))
        assertNotEquals(
            MuseNotificationIdPolicy.notificationIdForDispatch("dispatch-1"),
            MuseNotificationIdPolicy.notificationIdForOutcome("dispatch-1"),
        )
        assertTrue(MuseNotificationIdPolicy.isProactiveId(first))
        assertTrue(first >= 0x1000_0000)
        assertTrue(first <= 0x1FFF_FFFF)
    }
}
