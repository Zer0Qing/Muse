package io.zer0.muse.automation

/**
 * 自动化运行时对界面暴露的最小通道。
 *
 * 为什么需要它：虚拟屏客户端与服务管理器是在 `AutomationInitializer` 里按运行环境构造的，
 * 没有进 DI；而"预览 / 触摸接管"这类界面功能必须能拿到它们。
 * 与其把整条构造链塞进 Koin（会连带影响初始化时序与失败处理），不如只暴露三个动作：
 * 截图、注入点击、销毁。
 *
 * 约定：**没安装就是不可用**（返回 null/false），调用方据此显示"虚拟屏服务未就绪"，
 * 而不是静默什么都不做。
 */
object AutomationRuntime {

    @Volatile
    private var screenshotFn: (suspend (Int) -> ByteArray?)? = null

    @Volatile
    private var tapFn: (suspend (displayId: Int, x: Int, y: Int) -> Boolean)? = null

    @Volatile
    private var destroyFn: (suspend (Int) -> Boolean)? = null

    /** 由 [AutomationInitializer] 在虚拟屏组件就绪后调用一次。 */
    fun install(
        screenshot: suspend (Int) -> ByteArray?,
        tap: suspend (displayId: Int, x: Int, y: Int) -> Boolean,
        destroy: suspend (Int) -> Boolean,
    ) {
        screenshotFn = screenshot
        tapFn = tap
        destroyFn = destroy
    }

    /** 虚拟屏通道是否可用（界面据此决定显不显示预览入口）。 */
    val isReady: Boolean get() = screenshotFn != null

    suspend fun screenshot(displayId: Int): ByteArray? = screenshotFn?.invoke(displayId)

    suspend fun tap(displayId: Int, x: Int, y: Int): Boolean = tapFn?.invoke(displayId, x, y) ?: false

    suspend fun destroy(displayId: Int): Boolean = destroyFn?.invoke(displayId) ?: false

    /** 仅测试用：清空通道，避免跨用例串状态。 */
    internal fun resetForTests() {
        screenshotFn = null
        tapFn = null
        destroyFn = null
    }
}
