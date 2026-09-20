package io.zer0.muse.tools

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 浏览器实例注册表(P2-21 合并后)。
 *
 * 原实现「每个会话独立 BrowserManager」与 ToolRegistry/UI 胶囊共享的全局单例并存,
 * 造成浏览器双实例:主会话工具循环写在私有注册表上、子代理/定时任务写在 ToolRegistry
 * 的全局单例上,状态分叉且 UI 胶囊观察不到。现统一为**全局唯一 BrowserManager**:
 *  - [getForSession] 返回共享实例,并登记该会话"已使用浏览器"(驱动 UI 胶囊显隐)
 *  - 所有执行路径(主循环/子代理/定时任务)命中同一 WebView 状态,UI 实时可看
 *  - 会话删除只需 [closeSession] 取消登记,不再释放底层共享实例
 */
class BrowserManagerRegistry(
    private val context: Context,
    /** P2-21: 全局唯一浏览器实例(与 ToolRegistry/UI 胶囊共享)。null = 自建(独立/测试环境)。 */
    private val sharedManager: BrowserManager? = null,
) {

    private val lock = Any()

    /** 实际使用的浏览器实例:全仓共享,避免状态分叉。 */
    private val manager: BrowserManager = sharedManager ?: BrowserManager(context)

    // 已使用浏览器的会话 id 集合(驱动 UI 胶囊显示;WebView 状态本身全局共享)
    private val _activeSessionIds = MutableStateFlow<Set<String>>(emptySet())
    /** 已使用浏览器的会话 id 集合。 */
    val activeSessionIds: StateFlow<Set<String>> = _activeSessionIds.asStateFlow()

    /**
     * 获取(或登记)指定会话的浏览器实例 — 返回全局共享实例并登记该会话。
     *
     * P2-21: 不再按会话新建 WebView;任何会话/子代理/定时任务的浏览器工具调用
     * 都操作同一实例,消除「会话级 vs 全局单例」的状态分叉。
     */
    fun getForSession(sessionId: String): BrowserManager {
        synchronized(lock) {
            _activeSessionIds.value = _activeSessionIds.value + sessionId
            return manager
        }
    }

    /** 若会话已登记使用浏览器则返回共享实例(不登记),否则 null。供 UI 判断胶囊显隐。 */
    fun getIfActive(sessionId: String?): BrowserManager? {
        if (sessionId == null) return null
        synchronized(lock) { return if (sessionId in _activeSessionIds.value) manager else null }
    }

    /** 注销指定会话的浏览器登记(会话删除时调用;不关闭共享实例)。 */
    fun closeSession(sessionId: String) {
        synchronized(lock) {
            _activeSessionIds.value = _activeSessionIds.value - sessionId
        }
    }

    /** 清空全部会话登记(进程退出/设置清理时调用;共享实例由 Koin 生命周期管理)。 */
    fun closeAll() {
        synchronized(lock) {
            _activeSessionIds.value = emptySet()
        }
    }
}