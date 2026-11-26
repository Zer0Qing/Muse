package io.zer0.muse.data.agentbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConnectorHealthSnapshotTest {

    @Test
    fun `all ready capabilities are green`() {
        val snapshot = AgentConnectorHealthSnapshotFactory.from(
            prootReady = true,
            accessibilityReady = true,
            virtualDisplayReady = true,
            notificationReady = true,
            calendarReady = true,
            mcpEnabledCount = 2,
            channelCount = 1,
        )
        assertEquals(7, snapshot.items.size)
        assertTrue(snapshot.items.all { it.status == ConnectorHealthStatus.OK })
        assertFalse(snapshot.hasIssue)
    }

    @Test
    fun `missing permissions become actionable warnings`() {
        val snapshot = AgentConnectorHealthSnapshotFactory.from(
            prootReady = false,
            accessibilityReady = false,
            virtualDisplayReady = false,
            notificationReady = false,
            calendarReady = false,
            mcpEnabledCount = 0,
            channelCount = 0,
        )
        assertEquals(7, snapshot.items.count { it.status == ConnectorHealthStatus.WARN })
        assertTrue(snapshot.hasIssue)
        assertEquals(
            listOf("proot", "accessibility", "virtual_display", "notification", "calendar", "mcp", "channel"),
            snapshot.items.map { it.key },
        )
    }
}
