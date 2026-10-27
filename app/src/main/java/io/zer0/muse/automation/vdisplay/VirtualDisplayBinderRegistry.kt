package io.zer0.muse.automation.vdisplay

import android.os.IBinder

/**
 * v2.2.1 虚拟屏:服务端 Binder 注册表。
 *
 * 服务端(shell uid 进程)经广播把 binder 递交给主应用的
 * [VirtualDisplayBinderReceiver],接收端写入本注册表;
 * [VirtualDisplayServerManager] 从这里取 binder 判断存活与复用。
 */
object VirtualDisplayBinderRegistry {
    @Volatile
    private var binder: IBinder? = null

    @Volatile
    private var expectedHandoffToken: String? = null

    /** 收到新的手递手 binder(或失效时清空)。 */
    fun update(value: IBinder?) {
        binder = value
    }

    fun setExpectedHandoffToken(token: String) {
        expectedHandoffToken = token.takeIf { it.isNotBlank() }
    }

    fun clearHandoffToken() {
        expectedHandoffToken = null
    }

    /** Accept only a binder that proves knowledge of the current launch token. */
    fun accept(value: IBinder, handoffToken: String?): Boolean {
        val expected = expectedHandoffToken ?: return false
        if (handoffToken.isNullOrBlank() || handoffToken != expected) return false
        binder = value
        return true
    }

    /** 当前 binder(binder 已死时返回 null 并顺手清空)。 */
    fun current(): IBinder? {
        val value = binder ?: return null
        if (!value.isBinderAlive) {
            binder = null
            return null
        }
        return value
    }
}
