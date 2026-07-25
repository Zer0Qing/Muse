package io.zer0.muse.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp

/**
 * Muse 设计令牌 — 动画系统 (对齐 Kelivo iOS 触觉设计语言)。
 *
 * 提供全局统一的动画时长、缓动曲线、阴影规格与触觉反馈语义方法,
 * 与已有的 [MusePaddings] / [MuseCornerRadius] / [MuseElevation] / [MuseIconSizes] 互补,
 * 共同构成完整的设计令牌体系。
 *
 * 设计参考:
 *  - Kelivo (Flutter): iOS 触觉交互,200ms easeOutCubic 颜色渐变按压
 *  - Material 3 Expressive: 弹性动效 expressive motion scheme
 *  - Apple HIG: 触觉反馈分级 (light / medium / heavy)
 */
object MuseAnimation {

    // ── 时长令牌 ──────────────────────────────────────────────────────
    /** 极快过渡 (120ms): 微交互 — 图标切换 / 状态指示灯。 */
    const val FAST_MS = 120

    /** 快速过渡 (180ms): 简单状态切换 — 按钮颜色 / 开关。 */
    const val FAST_NORMAL_MS = 180

    /** 标准过渡 (240ms): 卡片展开 / 面板切换。 */
    const val NORMAL_MS = 240

    /** iOS 触觉标准 (200ms): 按压颜色渐变 (Kelivo 核心节奏)。 */
    const val TACTILE_MS = 200

    /** 慢速过渡 (320ms): 页面转场 / BottomSheet 弹出。 */
    const val SLOW_MS = 320

    /** 极慢过渡 (400ms): 大面积布局重组 / 主题切换渐变。 */
    const val EXTRA_SLOW_MS = 400

    // v1.0.16: 长动画档位(shimmer / pulse / loading 循环,超出 EXTRA_SLOW_MS 上限)
    /** 循环动画-标准 (600ms): 错峰 shimmer / 打字点 / 麦克风脉冲。 */
    const val LOOP_NORMAL_MS = 600

    /** 循环动画-慢 (800ms): 状态点脉冲 / 录音波形 / 委托链脉冲。 */
    const val LOOP_SLOW_MS = 800

    /** 循环动画-极慢 (1200ms): shimmer 占位 / 通话脉冲。 */
    const val LOOP_EXTRA_SLOW_MS = 1200

    // v1.0.16: 导航过渡档位(280-300ms,介于 NORMAL 与 SLOW 之间)
    /** 导航横向过渡 (280ms): NavGraph push/pop 水平滑动。 */
    const val NAV_HORIZONTAL_MS = 280

    /** 导航纵向进入 (300ms): NavGraph 垂直进入。 */
    const val NAV_VERTICAL_ENTER_MS = 300

    /** 导航纵向退出 (280ms): NavGraph 垂直退出。 */
    const val NAV_VERTICAL_EXIT_MS = 280

    // v1.0.16: 错峰入场递增步长
    /** 错峰入场步长 (120ms): 列表项逐条入场的 delay 递增值。 */
    const val STAGGER_STEP_MS = 120

    // ── 缓动曲线令牌 ──────────────────────────────────────────────────
    /**
     * iOS 标准缓动 (easeOutCubic): Kelivo 主力曲线,
     * 快入慢出,模拟 iOS 触觉交互的自然减速感。
     */
    val EaseOutCubic: Easing = CubicBezierEasing(0.33f, 1.0f, 0.68f, 1.0f)

    /**
     * 弹性回弹 (easeOutBack): 略微超过终点后回弹,
     * 用于卡片入场 / 元素弹出等需要"活力感"的场景。
     */
    val EaseOutBack: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1.0f)

    /**
     * Material 3 标准缓动: 通用过渡曲线,用于页面切换 / 元素移动。
     * 等同于 [FastOutSlowInEasing],此处命名统一令牌接口。
     */
    val Standard: Easing = FastOutSlowInEasing

    /**
     * 加速曲线 (easeInCubic): 慢入快出,用于元素离场 / 退出动画。
     */
    val EaseInCubic: Easing = CubicBezierEasing(0.32f, 0.0f, 0.67f, 0.0f)
}

/**
 * Muse 设计令牌 — 阴影系统 (iOS 风格柔和投影)。
 *
 * Kelivo 大量使用半透明柔和投影营造卡片"浮起"的层次感,
 * 与 Material 3 elevation 体系不同,iOS 风格投影更柔和、扩散更大、透明度更低。
 *
 * 使用方式:
 * ```
 * Modifier.shadow(
 *     elevation = MuseShadow.soft.elevation,
 *     shape = RoundedCornerShape(MuseCornerRadius.BUTTON.dp),
 * )
 * ```
 *
 * 注: Compose [Modifier.shadow] 不支持 offset / spread,此处仅提供 elevation + 语义规格。
 * 精确 offset 效果需用 [androidx.compose.ui.draw.drawBehind] 手动绘制。
 */
object MuseShadow {

    /** 阴影规格: elevation + 语义偏移量。 */
    data class ShadowSpec(
        val elevation: androidx.compose.ui.unit.Dp,
        val offsetY: androidx.compose.ui.unit.Dp = 0.dp,
    )

    /** 微投影: 紧贴表面,仅暗示层次 (按钮 / 标签)。 */
    val micro = ShadowSpec(elevation = 1.dp)

    /** 低投影: 列表项 / 输入框,轻微浮起感。 */
    val low = ShadowSpec(elevation = 2.dp, offsetY = 1.dp)

    /** 柔投影: 卡片主力阴影,Kelivo 风格 (black@5%, blur=18, offset=6dp)。 */
    val soft = ShadowSpec(elevation = 6.dp, offsetY = 6.dp)

    /** 高投影: 浮动按钮 / 弹出菜单。 */
    val high = ShadowSpec(elevation = 12.dp, offsetY = 8.dp)

    /** 模态投影: 对话框 / BottomSheet,最高层次。 */
    val modal = ShadowSpec(elevation = 24.dp, offsetY = 12.dp)
}

/**
 * Muse 设计令牌 — 触觉反馈语义封装。
 *
 * 将 Compose [HapticFeedback] 的原始 API 包装为语义化方法,
 * 调用方无需关心底层 [HapticFeedbackType] 选型,也无需关心开关读取。
 *
 * 用法:
 * ```
 * val haptic = LocalHapticFeedback.current
 * // 列表项点击
 * MuseHaptics.soft(haptic)
 * // 按钮 / 开关
 * MuseHaptics.light(haptic)
 * // 发送消息
 * MuseHaptics.medium(haptic)
 * // 删除 / 危险操作
 * MuseHaptics.heavy(haptic)
 * ```
 *
 * 偏好开关接入(v1.0.14):
 *  - [enabled] 字段由 [io.zer0.muse.MuseApp] 启动时订阅
 *    [io.zer0.muse.data.SettingsRepository.chatPreferencesFlow] 同步,
 *    用户在「设置 → 聊天行为 → 触感反馈」切换后,所有 [MuseHaptics] 调用立即生效。
 *  - 调用方无需自行读取开关,直接调用语义方法即可。
 *  - 线程安全:[enabled] 为 @Volatile,所有触觉调用都在 UI 线程,无并发风险。
 */
object MuseHaptics {
    /** 触觉总开关,默认 true(与 ChatPreferences.hapticFeedback 默认值一致)。 */
    @Volatile
    private var enabled: Boolean = true

    /** 由 MuseApp 启动时调用,把用户偏好同步到总开关。 */
    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /** 轻触: 列表项点击 / 卡片展开 / 滑块等日常交互。 */
    fun soft(haptic: HapticFeedback) {
        if (enabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    /** 轻击: 按钮点击 / 开关切换 / 菜单项选择。 */
    fun light(haptic: HapticFeedback) {
        if (enabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    /** 中等: 长按 / 发送消息 / 滑动触发删除/归档 / 确认操作。 */
    fun medium(haptic: HapticFeedback) {
        if (enabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /** 强击: 删除 / 危险操作确认(与 medium 同档,语义预留以便后续接入更强反馈)。 */
    fun heavy(haptic: HapticFeedback) {
        if (enabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
}
