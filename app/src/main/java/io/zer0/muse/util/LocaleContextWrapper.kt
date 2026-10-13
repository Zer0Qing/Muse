package io.zer0.muse.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import io.zer0.common.Logger

/**
 * 语言包装 Context —— 保留 locale 覆盖能力，同时保证 Activity 启动不崩。
 *
 * ## 背景（用户反馈的崩溃）
 *
 * 为了做应用内语言热切换，MainActivity 的 RuntimeLocaleProvider 用
 * `applicationContext.createConfigurationContext(config)` 覆盖 Compose 的
 * `LocalContext`（让 `stringResource` 读到目标语言）。但该 context 是
 * **Application context 派生**，不是 Activity。
 *
 * Compose 的文本选择菜单（选中文字 → 系统浮动条 → 「处理文字」项，实现于
 * `androidx.compose.foundation.text.contextmenu.ProcessText_androidKt`）
 * 从 `LocalContext` 取 context 直接 `startActivity`，**不带
 * FLAG_ACTIVITY_NEW_TASK** → Android 抛
 * `AndroidRuntimeException: Calling startActivity() from outside of an Activity
 * context requires the FLAG_ACTIVITY_NEW_TASK flag`（用户实测崩溃）。
 *
 * ## 修法
 *
 * 不改语言逻辑，只把 locale context 包一层 [ContextWrapper]：
 *  - `base` 仍是那个带目标 locale 的 context → `getResources()` / `stringResource`
 *    行为完全不变；
 *  - 重写 [startActivity] / [startActivity] 两个重载：**非 Activity 场景补
 *    FLAG_ACTIVITY_NEW_TASK**，与 [ShareIntentHelper] 同一套思路。
 *
 * 这样一次修掉**所有**"从 `LocalContext` 启动 Activity"的隐患（不止 ProcessText），
 * 且不牺牲任何功能——「处理文字」仍可正常把选中文字发给其他 App。
 *
 * 为什么不直接改 Compose：那是库代码；且同类问题在 Muse 侧的统一根因就是
 * "LocalContext 被换成了非 Activity context"，在源头包装最省事。
 */
internal class LocaleContextWrapper(
    base: Context,
) : ContextWrapper(base) {

    /**
     * 非 Activity 场景补 NEW_TASK 后启动。
     *
     * Activity 场景（wrapper 包着 Activity 时）保持原样，避免改变既有跳转语义。
     */
    override fun startActivity(intent: Intent) {
        super.startActivity(withNewTaskIfNeeded(intent))
    }

    /** Android 16+ 的带 options 重载，同样需要补 flag。 */
    override fun startActivity(intent: Intent, options: Bundle?) {
        super.startActivity(withNewTaskIfNeeded(intent), options)
    }

    private fun withNewTaskIfNeeded(intent: Intent): Intent = when {
        // wrapper 自身永不可能是 Activity（它继承 ContextWrapper）；
        // 只判断被包装的 base 是否 Activity。
        baseContext is Activity -> intent
        intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0 -> intent
        else -> Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        private const val TAG = "LocaleContextWrapper"

        /**
         * 包装一个 locale context，使其可安全启动 Activity。
         *
         * 幂等：已包装过的直接返回，避免重复嵌套。
         */
        fun wrap(base: Context): Context {
            if (base is LocaleContextWrapper) return base
            Logger.d(TAG, "LocaleContextWrapper 已应用（非 Activity context 启动 Activity 时补 NEW_TASK）")
            return LocaleContextWrapper(base)
        }
    }
}
