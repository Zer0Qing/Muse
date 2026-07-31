package io.zer0.muse.tools.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3-3: [AndroidPermissionLevel] 单元测试 — 三通道权限等级逻辑。
 */
class AndroidPermissionLevelTest {

    @Test
    fun `atLeast returns true for same or lower level`() {
        assertTrue(AndroidPermissionLevel.NONE.atLeast(AndroidPermissionLevel.NONE))
        assertTrue(AndroidPermissionLevel.ACCESSIBILITY.atLeast(AndroidPermissionLevel.ACCESSIBILITY))
        assertTrue(AndroidPermissionLevel.SHIZUKU.atLeast(AndroidPermissionLevel.ACCESSIBILITY))
        assertTrue(AndroidPermissionLevel.ROOT.atLeast(AndroidPermissionLevel.NONE))
    }

    @Test
    fun `atLeast returns false for higher level`() {
        assertFalse(AndroidPermissionLevel.NONE.atLeast(AndroidPermissionLevel.ACCESSIBILITY))
        assertFalse(AndroidPermissionLevel.ACCESSIBILITY.atLeast(AndroidPermissionLevel.SHIZUKU))
        assertFalse(AndroidPermissionLevel.SHIZUKU.atLeast(AndroidPermissionLevel.ROOT))
    }

    @Test
    fun `highestOf returns NONE when no channel available`() {
        val level = AndroidPermissionLevel.highestOf(
            AndroidPermissionLevel.ACCESSIBILITY to false,
            AndroidPermissionLevel.SHIZUKU to false,
            AndroidPermissionLevel.ROOT to false,
        )
        assertEquals(AndroidPermissionLevel.NONE, level)
    }

    @Test
    fun `highestOf returns highest available level`() {
        assertEquals(
            AndroidPermissionLevel.ACCESSIBILITY,
            AndroidPermissionLevel.highestOf(
                AndroidPermissionLevel.ACCESSIBILITY to true,
                AndroidPermissionLevel.SHIZUKU to false,
                AndroidPermissionLevel.ROOT to false,
            ),
        )
        assertEquals(
            AndroidPermissionLevel.SHIZUKU,
            AndroidPermissionLevel.highestOf(
                AndroidPermissionLevel.ACCESSIBILITY to true,
                AndroidPermissionLevel.SHIZUKU to true,
                AndroidPermissionLevel.ROOT to false,
            ),
        )
        assertEquals(
            AndroidPermissionLevel.ROOT,
            AndroidPermissionLevel.highestOf(
                AndroidPermissionLevel.ACCESSIBILITY to true,
                AndroidPermissionLevel.SHIZUKU to true,
                AndroidPermissionLevel.ROOT to true,
            ),
        )
    }

    @Test
    fun `highestOf ignores unavailable channels`() {
        assertEquals(
            AndroidPermissionLevel.SHIZUKU,
            AndroidPermissionLevel.highestOf(
                AndroidPermissionLevel.ACCESSIBILITY to false,
                AndroidPermissionLevel.SHIZUKU to true,
                AndroidPermissionLevel.ROOT to false,
            ),
        )
    }

    @Test
    fun `ordinal order is NONE less than ACCESSIBILITY less than SHIZUKU less than ROOT`() {
        assertTrue(AndroidPermissionLevel.NONE.ordinal < AndroidPermissionLevel.ACCESSIBILITY.ordinal)
        assertTrue(AndroidPermissionLevel.ACCESSIBILITY.ordinal < AndroidPermissionLevel.SHIZUKU.ordinal)
        assertTrue(AndroidPermissionLevel.SHIZUKU.ordinal < AndroidPermissionLevel.ROOT.ordinal)
    }
}
