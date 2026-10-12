package io.zer0.muse.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * v2.6.0: 从 Context 链中查找 Activity；回溯失败时回退到应用级追踪的当前 Activity。
 *
 * Compose 的 LocalContext 可能被 ContextThemeWrapper 等包裹（尤其页面内嵌在
 * 特殊主题容器时），直接 `context as? Activity` 会失败。这里先沿 baseContext
 * 链向上回溯；仍拿不到时用 [CurrentActivityTracker] 的当前 resumed Activity 兜底，
 * 解决真机上「无法获取 Activity 上下文」导致 OAuth Auth Code Flow 失败的问题。
 */
fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return CurrentActivityTracker.current()
}
