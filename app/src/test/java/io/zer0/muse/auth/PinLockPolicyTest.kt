package io.zer0.muse.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-TEST-03: PIN 失败计数 → 指数退避 → 锁定态纯逻辑。
 */
class PinLockPolicyTest {

    @Test
    fun `no lock before five failures`() {
        for (count in 0..4) {
            assertEquals(0L, PinLockPolicy.lockDelayMs(count))
        }
    }

    @Test
    fun `delay doubles from five failures`() {
        assertEquals(30_000L, PinLockPolicy.lockDelayMs(5))
        assertEquals(60_000L, PinLockPolicy.lockDelayMs(6))
        assertEquals(120_000L, PinLockPolicy.lockDelayMs(7))
    }

    @Test
    fun `delay is capped to avoid overflow`() {
        assertEquals(30_000L * (1L shl 20), PinLockPolicy.lockDelayMs(100))
    }

    @Test
    fun `locked state compares now against lock until`() {
        assertFalse(PinLockPolicy.isLocked(nowMs = 1_000L, lockUntil = 0L))
        assertTrue(PinLockPolicy.isLocked(nowMs = 1_000L, lockUntil = 2_000L))
        assertFalse(PinLockPolicy.isLocked(nowMs = 2_000L, lockUntil = 2_000L))
    }
}
