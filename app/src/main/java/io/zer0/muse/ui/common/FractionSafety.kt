package io.zer0.muse.ui.common

/**
 * 比例值安全化 — 供 [androidx.compose.foundation.layout.fillMaxWidth] /
 * fillMaxHeight / fillMaxSize 以及 `animateFloatAsState` 等需要 0..1 比例值的 API 使用。
 *
 * 背景（真机崩溃防御）:
 * Kotlin 的 `Float.coerceIn` 对 NaN 无效 —— NaN 参与的任何比较恒为 false,
 * 于是 `x.coerceIn(0f, 1f)` 在 x 为 NaN 时原样返回 NaN。把 NaN 传给
 * `Modifier.fillMaxWidth(fraction)` 会抛:
 *   IllegalArgumentException: Expecting fraction between 0 and 1. Got NaN
 * 而 [androidx.compose.animation.core.animateFloatAsState] 拿到 NaN 目标值时
 * 动画值同样会变成 NaN,经 fillMaxHeight 传播后同样崩溃。
 *
 * 本函数把 NaN / Infinity 归一为 [fallback]（默认 0）,其余值收敛到 0..1。
 */
fun Float.safeFraction(fallback: Float = 0f): Float = if (isFinite()) coerceIn(0f, 1f) else fallback.coerceIn(0f, 1f)

/**
 * 带自定义下限的比例值安全化。
 *
 * 用于波形条这类"即使静音也要保留最小可见高度"的场景:
 * 先做 NaN 防御,再收敛到 [min]..1。
 */
fun Float.safeFraction(min: Float, fallback: Float): Float = if (isFinite()) coerceIn(min, 1f) else fallback.coerceIn(min, 1f)
