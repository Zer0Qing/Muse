package io.zer0.muse.ui.common

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * v2.6.0: 应用级 Activity 追踪。
 *
 * 背景（用户反馈：OpenAI 一键登录报「无法获取 Activity 上下文」）：
 * 部分 Compose 容器下 `LocalContext` 既不指向 Activity，也可能被多层
 * ContextWrapper 包裹，单纯沿 baseContext 回溯仍可能拿不到 —— 而 Auth Code
 * Flow 必须用真实 Activity 启动浏览器 Intent（startActivity）。
 *
 * 因此在 Application 上注册生命周期回调，始终记住当前处于 resumed 的
 * Activity 作为兜底：`Context.findActivity()` 回溯失败时返回它。
 */
object CurrentActivityTracker : Application.ActivityLifecycleCallbacks {

    @Volatile
    private var resumed: WeakReference<Activity>? = null

    /** 最近一次 resumed 的 Activity（可能为 null，如全后台）。 */
    fun current(): Activity? = resumed?.get()

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
