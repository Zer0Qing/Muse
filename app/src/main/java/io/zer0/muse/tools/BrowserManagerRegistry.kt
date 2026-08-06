package io.zer0.muse.tools

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 会话级浏览器管理器注册表。
 *
 * 每个会话(sessionId)一个独立的 [BrowserManager](WebView),互不串扰:
 *  - 会话 A 关闭浏览器不影响会话 B 的页面状态
 *  - 会话切换后胶囊只显示当前会话的浏览器状态
 *
 * 资源控制:最多保留 [MAX_INSTANCES] 个实例,超出时关闭最早创建且未活跃的实例。
 * 会话删除时由 ChatViewModel 调用 [closeSession] 显式释放。
 */
class BrowserManagerRegistry(private val context: Context) {

    private val lock = Any()
    private val instances = LinkedHashMap<String, BrowserManager>()

    // 已创建实例的会话 id 集合(驱动 UI 胶囊显示)
    private val _activeSessionIds = MutableStateFlow<Set<String>>(emptySet())
    /** 已创建浏览器实例的会话 id 集合。 */
    val activeSessionIds: StateFlow<Set<String>> = _activeSessionIds.asStateFlow()

    /**
     * 获取(或创建)指定会话的浏览器实例。
     *
     * 线程安全:BrowserManager 构造是轻量的(WebView 懒创建,且创建时有主线程校验),
     * 任何线程都可调用;实际 WebView 初始化由首次 navigate 在主线程完成。
     */
    fun getForSession(sessionId: String): BrowserManager {
        synchronized(lock) {
            instances[sessionId]?.let { return it }
        }
        val manager = BrowserManager(context)
        synchronized(lock) {
            instances[sessionId] = manager
            // 超上限淘汰:优先关掉最久未活跃的(LinkedHashMap 保序,头部最旧)
            while (instances.size > MAX_INSTANCES) {
                val oldest = instances.entries.firstOrNull() ?: break
                if (oldest.key == sessionId) break
                val victim = instances.remove(oldest.key)
                victim?.close()
            }
            _activeSessionIds.value = instances.keys.toSet()
        }
        return manager
    }

    /** 若会话已创建浏览器实例则返回(不创建),否则 null。供 UI 判断胶囊显隐。 */
    fun getIfActive(sessionId: String?): BrowserManager? {
        if (sessionId == null) return null
        synchronized(lock) { return instances[sessionId] }
    }

    /** 关闭并移除指定会话的浏览器实例(会话删除时调用)。 */
    fun closeSession(sessionId: String) {
        val victim = synchronized(lock) {
            instances.remove(sessionId)?.also {
                _activeSessionIds.value = instances.keys.toSet()
            }
        }
        victim?.close()
    }

    /** 关闭全部实例(进程退出/设置清理时调用)。 */
    fun closeAll() {
        val all = synchronized(lock) {
            instances.values.toList().also {
                instances.clear()
                _activeSessionIds.value = emptySet()
            }
        }
        all.forEach { it.close() }
    }

    companion object {
        /** 同时保留的会话浏览器实例上限。 */
        private const val MAX_INSTANCES = 4
    }
}
