package io.zer0.muse.ui.common

import android.content.Context
import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/**
 * 生命周期感知 WebView。
 *
 * v1.88 已知限制修复: 实现 [LifecycleEventObserver] 接口,观察宿主 [LifecycleOwner] 的
 * ON_PAUSE / ON_RESUME / ON_DESTROY 事件,自动调用 WebView 的 [onPause] / [onResume] /
 * [pauseTimers] / [resumeTimers] / [destroy],解决 Activity 后台时 JS 定时器/动画继续
 * 运行导致耗电的问题。
 *
 * 行为说明:
 *  - ON_PAUSE → [onPause] + [pauseTimers]:暂停 WebView 内所有 JS 定时器与动画;
 *  - ON_RESUME → [onResume] + [resumeTimers]:恢复执行;
 *  - ON_DESTROY → [destroy] + 注销观察者:销毁 WebView 并解除生命周期绑定,避免泄漏。
 *
 * 使用方式:
 *  - 推荐:在 Compose 中通过 [LifecycleAwareWebViewContainer] 使用,自动注册/注销;
 *  - 手动:通过 [LifecycleAwareWebViewFactory.create] 创建实例,会自动注册到传入的 LifecycleOwner;
 *  - 离开组合时(Composable dispose 但宿主未销毁)需手动调用 [destroy] + removeObserver。
 *
 * @param context Android 上下文
 */
class LifecycleAwareWebView(
    context: Context,
) : WebView(context), LifecycleEventObserver {

    /**
     * 宿主生命周期事件回调。
     *
     * 在 LifecycleOwner 抛出 ON_PAUSE / ON_RESUME / ON_DESTROY 时被调用,
     * 转发到 WebView 对应的生命周期方法。
     *
     * @param source 触发事件的 LifecycleOwner
     * @param event 生命周期事件类型
     */
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            // 后台时暂停 WebView 与所有 JS 定时器,避免无意义耗电
            Lifecycle.Event.ON_PAUSE -> {
                onPause()
                pauseTimers()
            }
            // 回到前台时恢复
            Lifecycle.Event.ON_RESUME -> {
                onResume()
                resumeTimers()
            }
            // 宿主销毁时清理资源并解除观察者绑定
            Lifecycle.Event.ON_DESTROY -> {
                destroy()
                source.lifecycle.removeObserver(this)
            }
            else -> Unit
        }
    }
}

/**
 * [LifecycleAwareWebView] 工厂对象。
 *
 * 提供统一的创建入口,封装"创建实例 + 注册到 LifecycleOwner"两步操作,
 * 避免调用方遗漏注册逻辑。
 */
object LifecycleAwareWebViewFactory {

    /**
     * 创建 [LifecycleAwareWebView] 并绑定到 [lifecycleOwner]。
     *
     * 创建后 WebView 会自动监听 [lifecycleOwner] 的 ON_PAUSE / ON_RESUME / ON_DESTROY 事件,
     * 在后台时暂停 JS 定时器/动画,在前台时恢复。
     *
     * 调用方仍需在 Composable 离开组合时(非宿主销毁场景)手动 destroy + removeObserver,
     * 或直接使用 [LifecycleAwareWebViewContainer] 由容器自动管理。
     *
     * @param context Android 上下文
     * @param lifecycleOwner 宿主生命周期所有者(通常为 Activity / Fragment)
     * @return 已注册生命周期观察的 [LifecycleAwareWebView] 实例
     */
    fun create(context: Context, lifecycleOwner: LifecycleOwner): LifecycleAwareWebView {
        return LifecycleAwareWebView(context).also { webView ->
            lifecycleOwner.lifecycle.addObserver(webView)
        }
    }
}
