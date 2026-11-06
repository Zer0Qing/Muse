package io.zer0.muse.notification

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知权限信号的取走语义。
 *
 * 这个信号决定"要不要向用户提授权"。取走必须一次性:否则同一次被拦会在用户已经
 * 拒绝之后反复弹窗,把按需请求变成骚扰。
 */
class NotificationPermissionBusTest {

    @After
    fun reset() {
        NotificationPermissionBus.needed.value = false
    }

    @Test
    fun `没有信号时取不到`() {
        NotificationPermissionBus.needed.value = false
        assertFalse(NotificationPermissionBus.consume())
    }

    @Test
    fun `被拦下一次只提一次请求`() {
        NotificationPermissionBus.needed.value = true
        assertTrue("第一次必须取到,否则用户永远等不到授权提示", NotificationPermissionBus.consume())
        assertFalse("取走之后必须复位,不能反复弹窗", NotificationPermissionBus.consume())
        assertFalse(NotificationPermissionBus.needed.value)
    }
}
