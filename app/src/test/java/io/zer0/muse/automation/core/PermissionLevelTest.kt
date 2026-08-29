package io.zer0.muse.automation.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionLevelTest {

    @Test
    fun `none does not cover any executable permission`() {
        assertTrue(PermissionLevel.NONE.covers(PermissionLevel.NONE))
        assertFalse(PermissionLevel.NONE.covers(PermissionLevel.ACCESSIBILITY))
        assertFalse(PermissionLevel.NONE.covers(PermissionLevel.SHELL))
        assertFalse(PermissionLevel.NONE.covers(PermissionLevel.ROOT))
    }

    @Test
    fun `higher levels cover lower levels`() {
        assertTrue(PermissionLevel.ACCESSIBILITY.covers(PermissionLevel.ACCESSIBILITY))
        assertTrue(PermissionLevel.SHELL.covers(PermissionLevel.ACCESSIBILITY))
        assertTrue(PermissionLevel.ROOT.covers(PermissionLevel.SHELL))
        assertTrue(PermissionLevel.ROOT.covers(PermissionLevel.ACCESSIBILITY))
        assertFalse(PermissionLevel.ACCESSIBILITY.covers(PermissionLevel.SHELL))
    }
}
