package io.zer0.muse.ui.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS 风格优雅提示组件 — 替代原生 Toast。
 *
 * 全局单例,任何地方调用 [show] 即可触发,
 * 由 [MuseToastHost] Composable 观察并渲染。
 *
 * 用法:
 * ```kotlin
 * MuseToast.show("已复制")           // 短显示(2s)
 * MuseToast.show("保存成功", 3000)   // 自定义时长
 * ```
 */
object MuseToast {

    // L-MT1: 用 MutableStateFlow 单值承载当前 toast,无内部队列。
    // 连续调用 show() 会直接覆盖上一条(不排队),旧 toast 的剩余显示时长被丢弃。
    // 这是有意为之:toast 用于轻量即时反馈,避免队列积压导致提示滞后用户操作。
    // 若未来需要依次展示多条,应改用 Channel + 列表 StateFlow 实现排队。
    private val _message = MutableStateFlow<ToastData?>(null)
    val message: StateFlow<ToastData?> = _message.asStateFlow()

    /**
     * 显示提示。
     * @param text 提示文本
     * @param durationMillis 显示时长(毫秒),默认 2000
     */
    fun show(text: String, durationMillis: Long = 2000) {
        _message.value = ToastData(text = text, durationMillis = durationMillis)
    }

    /** 清除当前提示(动画消失后调用)。 */
    internal fun clear() {
        _message.value = null
    }
}

/** Toast 数据。 */
data class ToastData(
    val text: String,
    val durationMillis: Long,
)
