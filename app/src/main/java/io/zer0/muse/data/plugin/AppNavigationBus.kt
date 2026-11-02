package io.zer0.muse.data.plugin

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 插件让宿主**跳转到某个页面**（`app.navigate` 能力的落地通道）。
 *
 * 为什么走深链而不是直接调导航：深链这套入口本来就有（通知、小组件、外部链接都在用），
 * 复用它意味着"插件能跳的地方"和"别人能跳的地方"是**同一份白名单** ——
 * 不会出现"通知能跳、插件不能"或反过来这种两套规则。
 *
 * 校验分成两处，不是重复：
 *  - 插件侧（[PluginAppNavigation]）先拒掉写错的形状，给出作者能看懂的原因；
 *  - 宿主侧复用既有深链解析，再拒一次 —— 插件代码是外部输入，不能假设它只发合法目标。
 */
object AppNavigationBus {

    private val events = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 宿主（MainActivity）订阅这个流来执行跳转。 */
    val requests = events.asSharedFlow()

    /** 当前有几个订阅者。0 表示应用还没有前台界面可以执行跳转。 */
    val subscriberCount: Int get() = events.subscriptionCount.value

    /**
     * 请求跳转。
     *
     * @return 是否**真的**送到了；没有订阅者时返回 false。
     *
     * 注意不能直接用 `tryEmit` 的返回值：`MutableSharedFlow` 带缓冲时，
     * 即使一个订阅者都没有，`tryEmit` 也会返回 true（事件进缓冲区然后被丢掉）。
     * 那会让插件收到 `{"ok":true}` 而用户什么都没看到 —— 正是要避免的假成功。
     * 所以先看订阅者数，再投递。
     */
    fun request(uri: String): Boolean {
        if (subscriberCount == 0) return false
        return events.tryEmit(uri)
    }
}
