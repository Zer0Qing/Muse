package io.zer0.muse.tools

import io.zer0.ai.core.ToolDefinition
import io.zer0.muse.data.skill.SkillEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRouteSnapshotTest {
    private val local = ToolDefinition("same", "local", "{\"type\":\"object\"}")
    private val skill = SkillEntity("same", "skill", "skill", "{}")

    @Test
    fun `same name local wins definition and route`() {
        val snapshot = RouteTable.snapshot(listOf(local), listOf(skill))

        assertEquals(listOf("same"), snapshot.definitions.map { it.name })
        assertTrue(snapshot.routeFor("same") is ToolRouteSnapshot.Route.Local)
    }

    @Test
    fun `unexposed tool has no route`() {
        val snapshot = RouteTable.snapshot(emptyList(), emptyList())

        assertFalse(snapshot.isExposed("not_exposed"))
        assertEquals(null, snapshot.routeFor("not_exposed"))
    }
}
