package io.zer0.muse.notification

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 「该问用户要通知权限了」的一次性信号。
 *
 * 通知权限不在启动时开口要:新用户的第一帧就被系统弹窗打断,只会条件反射地点拒绝。
 * 投递通知时被系统拦下(SecurityException)才说明"这个功能确实需要它",
 * 这时再让界面把请求提出来,用户知道少了什么、也更容易同意。
 */
object NotificationPermissionBus {

    /** true 表示有一次通知因缺权限被拦下,等待界面(前台时)提出请求。 */
    val needed = MutableStateFlow(false)

    /** 前台界面取走这次请求;取走后不会重复弹窗。 */
    fun consume(): Boolean {
        if (!needed.value) return false
        needed.value = false
        return true
    }
}
