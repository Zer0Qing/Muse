package io.zer0.muse.automation.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import io.zer0.common.Logger

/**
 * 启动系统设置入口的最小安全边界。
 *
 * 某些厂商系统没有实现特定 Settings action,即使 Intent 构造成功,
 * startActivity 仍会抛 ActivityNotFoundException。逐个检查并捕获启动异常,
 * 保证权限页点击不会把整个 Compose Activity 弄崩。
 */
internal fun launchFirstResolvable(candidates: List<Intent>, canResolve: (Intent) -> Boolean, startActivity: (Intent) -> Unit): Boolean {
    candidates.forEach { candidate ->
        if (!runCatching { canResolve(candidate) }.getOrDefault(false)) return@forEach
        try {
            startActivity(candidate)
            return true
        } catch (error: ActivityNotFoundException) {
            Logger.w("AutomationSettings", "系统设置入口不可用: ${candidate.action}", error)
        } catch (error: SecurityException) {
            Logger.w("AutomationSettings", "系统设置入口无权限: ${candidate.action}", error)
        }
    }
    return false
}

/**
 * 从非 Activity Context 启动设置时,把 NEW_TASK 加到最终候选 Intent。
 *
 * 不再用 `resolveActivity() != null` 做前置门禁:targetSdk 30+ 的包可见性下,
 * 系统 Settings 活动常常 resolveActivity 返回 null(清单没为每个 action 声明 `<queries>`),
 * 于是候选被整条丢掉、`startActivity` 根本没被尝试——这正是"点了没反应/没有可用的设置页入口"的根因。
 * 改为逐个 `startActivity` 并依赖 [launchFirstResolvable] 的 ActivityNotFoundException 降级:
 * 能不能开由系统实际裁决,试不了才换下一个,最后一个都不行才回 false。
 */
internal fun openAutomationSettings(context: Context, candidates: List<Intent>, onUnavailable: () -> Unit = {}): Boolean {
    val started = launchFirstResolvable(
        candidates = candidates.map { Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
        canResolve = { true },
        startActivity = context::startActivity,
    )
    if (!started) onUnavailable()
    return started
}
