package io.zer0.muse.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.3.1: 会话切换序号守卫单测(纯逻辑,无 Android 依赖)。
 *
 * 守卫是实例状态,每个界面实例各持一份；用例各自新建一个实例,不共享状态。
 */
class SessionSwitchGuardTest {
    private val guard = SessionSwitchGuard()

    @Test
    fun `latest token is never stale`() {
        val token = guard.begin()
        assertFalse(guard.isStale(token))
    }

    @Test
    fun `begin returns increasing tokens`() {
        val first = guard.begin()
        val second = guard.begin()
        assertTrue(second > first)
    }

    @Test
    fun `begin issues distinct tokens`() {
        val first = guard.begin()
        val second = guard.begin()
        assertTrue(first != second)
    }

    @Test
    fun `superseded token becomes stale`() {
        val old = guard.begin()
        guard.begin()
        assertTrue(guard.isStale(old))
    }

    /** 不变量:快速连续切换后,只有最后一次发起的切换可提交,更早的一律过期。 */
    @Test
    fun `rapid switching keeps only the last committable`() {
        val first = guard.begin()
        val second = guard.begin()
        val third = guard.begin()
        assertTrue(guard.isStale(first))
        assertTrue(guard.isStale(second))
        assertFalse(guard.isStale(third))
    }

    /**
     * 不变量:两个界面实例的序号互不干扰。
     *
     * 这正是实例化的理由：一个实例里发起了新切换，不该让另一个实例刚发起的切换过期。
     */
    @Test
    fun `separate instances do not invalidate each other`() {
        val other = SessionSwitchGuard()
        val mine = guard.begin()
        other.begin()
        assertFalse("另一个实例的切换不应让本实例的 token 过期", guard.isStale(mine))
    }
}
