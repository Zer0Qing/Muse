package io.zer0.muse.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * v2.6.0: 从 Context 链中查找 Activity。
 *
 * Compose 的 LocalContext 可能被 ContextThemeWrapper 等包裹（尤其页面内嵌在
 * 特殊主题容器时），直接 `context as? Activity` 会失败。沿 baseContext 链
 * 向上回溯找到最近的 Activity；找不到返回 null。
 *
 * 原 Theme.kt 中有同名 private 实现（供窗口操作使用），此处为公共版本，
 * 供 OAuth Auth Code Flow 等需要真实 Activity 启动浏览器 Intent 的场景。
 */
fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
