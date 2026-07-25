package io.zer0.muse.data

data class AccountState(
    val isLoggedIn: Boolean = false,
    val userName: String = "",
    val loginAt: Long = 0L,
    val loginMethod: String = "",
    /** 离线体验(游客)模式 — 跳过登录直接进入主界面,无服务器同步。 */
    val isGuestMode: Boolean = false,
) {
    /** 是否已"通过"认证(登录或游客) — 用于路由判断 startDestination。 */
    val isAuthed: Boolean get() = isLoggedIn || isGuestMode

    companion object {
        val GUEST = AccountState()
    }
}