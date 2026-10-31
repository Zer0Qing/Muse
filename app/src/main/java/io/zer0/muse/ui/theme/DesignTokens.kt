package io.zer0.muse.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * Muse 设计令牌 — 动画系统。
 *
 * 提供全局统一的动画时长、缓动曲线、阴影规格与触觉反馈语义方法,
 * 与已有的 [MusePaddings] / [MuseCornerRadius] / [MuseElevation] / [MuseIconSizes] 互补,
 * 共同构成完整的设计令牌体系。
 *
 * 设计说明:
 *  - 触觉交互:按压时 200ms easeOutCubic 颜色渐变
 *  - Material 3 Expressive: 弹性动效 expressive motion scheme
 *  - Apple HIG: 触觉反馈分级 (light / medium / heavy)
 */
object MuseAnimation {

    // ── 形态指示器 ──────────────────────────────────────────────────
    /**
     * 黇形一个完整形态循环的时长：叶 → 星 → 环 → 回叶。
     *
     * 3.6s 是三个形态各占到足够停顿、又不至于让等待显得漫的长度。
     */
    const val GLYPH_CYCLE_MS = 3600

    /** 星芒形态下游走光绕轮廓一周的时长。独立于形态循环，两者周期成非整数比。 */
    const val GLYPH_TRAVEL_MS = 1600

    // ── 时长令牌 ──────────────────────────────────────────────────────
    /** 极快过渡 (120ms): 微交互 — 图标切换 / 状态指示灯。 */
    const val FAST_MS = 120

    /** 快速过渡 (180ms): 简单状态切换 — 按钮颜色 / 开关。 */
    const val FAST_NORMAL_MS = 180

    /** 标准过渡 (240ms): 卡片展开 / 面板切换。 */
    const val NORMAL_MS = 240

    /** 按压反馈标准 (200ms): 按压颜色渐变。 */
    const val TACTILE_MS = 200

    /** 慢速过渡 (320ms): 页面转场 / BottomSheet 弹出。 */
    const val SLOW_MS = 320

    /** 极慢过渡 (400ms): 大面积布局重组 / 主题切换渐变。 */
    const val EXTRA_SLOW_MS = 400

    // v2.0: 全局循环动效整体减速 1.5x(更大的毫秒值 = 更慢的转动/闪烁),
    // 覆盖加载转圈、思考点、shimmer、录音/群聊/委托脉冲;有限交互过渡不变。
    /** 循环动画-标准 (900ms): 打字点 / 麦克风脉冲。 */
    const val LOOP_NORMAL_MS = 900

    /** 循环动画-慢 (1200ms): 状态点脉冲 / 录音波形 / 委托链脉冲。 */
    const val LOOP_SLOW_MS = 1200

    /** 循环动画-极慢 (1800ms): shimmer 占位 / 通话脉冲 / 旋转加载。 */
    const val LOOP_EXTRA_SLOW_MS = 1800

    // v1.0.16: 导航过渡档位(280-300ms,介于 NORMAL 与 SLOW 之间)
    /** 导航横向过渡 (280ms): NavGraph push/pop 水平滑动。 */
    const val NAV_HORIZONTAL_MS = 280

    /** 导航纵向进入 (300ms): NavGraph 垂直进入。 */
    const val NAV_VERTICAL_ENTER_MS = 300

    /** 导航纵向退出 (280ms): NavGraph 垂直退出。 */
    const val NAV_VERTICAL_EXIT_MS = 280

    // ── v2.6.7 动效重置: 进场/退场时长分离 ─────────────────────────────
    // 对标 kelivo: 进场 360ms / 退场 280ms。退场必须比进场短 ——
    // 用户不想等一个已经决定离开的东西慢慢消失。
    /** 页面入场 (360ms): 比退场长，让新内容有时间 "站定"。 */
    const val ENTER_MS = 360

    /** 页面退场 (280ms): 短于入场，离开不该拖沓。 */
    const val EXIT_MS = 280

    /** 内容块入场 (260ms): 列表项 / 卡片 / 提示条。 */
    const val CONTENT_ENTER_MS = 260

    /** 内容块退场 (200ms): 短于入场。 */
    const val CONTENT_EXIT_MS = 200

    // ── v2.6.7 动效重置: 位移距离 ─────────────────────────────────────
    // 旧实现让页面走满整屏(比例 1.0)或半屏(0.5)，配 easeOutCubic 就是
    // "先猛冲一步再拖回来"，读感是 "抽出来"。kelivo 全仓位移在 2%~8%。
    /** 页面转场位移比例（1/12 ≈ 8%）: 横向 push/pop 用。 */
    const val SLIDE_FRACTION_PAGE = 1f / 12f

    /** 次要层位移比例（1/16 ≈ 6%）: 被覆盖的根页退场、pop 回场用。 */
    const val SLIDE_FRACTION_SECONDARY = 1f / 16f

    /** 内容块位移比例（1/20 = 5%）: 提示条 / 卡片 / 浮动元素。 */
    const val SLIDE_FRACTION_CONTENT = 1f / 20f

    // v1.0.16: 错峰入场递增步长
    //
    // v2.6.7: 180ms → 28ms，并且必须封顶（见 [STAGGER_MAX_INDEX]）。
    // 旧值的问题: 180ms 是 kelivo（16~20ms）的一个数量级以上，
    // 且调用点普遍写 `index * STAGGER_STEP_MS` 不封顶 ——
    // 第 10 项要等 1.8s，第 20 项要等 3.6s，表现为 "越往下越卡"。
    /** 错峰入场步长 (28ms): 列表项逐条入场的 delay 递增值。 */
    const val STAGGER_STEP_MS = 28

    /** 错峰参与的最大序号: 超过此值的项共用同一 delay，避免深列表等待。 */
    const val STAGGER_MAX_INDEX = 6

    // ── 缓动曲线令牌 ──────────────────────────────────────────────────
    /**
     * 标准缓动 (easeOutCubic):
     * 快入慢出,模拟触觉交互的自然减速感。
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

    /**
     * v2.x: Material 3 Expressive 强调曲线 — 大位移/强调过渡场景
     * (motion-scheme 的 emphasized 规格,供面板/大容器转场引用)。
     */
    val Emphasized: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

    /**
     * v2.6.7: "先长出来再稳住" 曲线 — 对标 kelivo 的 `Cubic(0.2, 0.8, 0.2, 1)`。
     *
     * 前 20% 时间走完 80% 路程，剩余时间缓慢收敛。用于尺寸变化
     * （[androidx.compose.animation.animateContentSize]）与展开类动效：
     * 读感是内容 "生长" 出来，而不是被瞬间撞开。
     *
     * 与 [EaseOutCubic] 的区别: EaseOutCubic 中后段仍有明显推进，
     * 适合位移；本曲线末段几乎静止，适合 "尺寸/裁切" 这种
     * 终点必须稳住的场景。
     */
    val GrowEase: Easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1.0f)

    /**
     * v2.x: 弹性弹簧规格沉淀 — 按压缩放/卡片回弹场景的物理参数
     * (原散落各处手写 spring(MediumBouncy, StiffnessMedium);新代码从这里取)。
     */
    fun bouncySpring(): SpringSpec<Float> = SpringSpec(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium,
    )
}

/**
 * Muse 设计令牌 — 阴影统一说明 (CMP-01)。
 *
 * 阴影/高程只保留一套语义刻度 [MuseElevation](tonalElevation 与 shadowElevation 共用),
 * 各档用途:
 *  - none(0) / micro(0.5): 无层次 / 紧贴表面
 *  - low(1) / card(1):     列表项 / 卡片默认
 *  - medium(2):            输入岛 / 浮动条 shadowElevation
 *  - high(4):              选中项 / 强调卡片
 *  - modal(8):             对话框 / BottomSheet
 * 历史两套同名不同值的 [MuseShadow] 已删除,调用方统一改用 [MuseElevation]。
 */

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

    /** 强击: 删除 / 危险操作确认。
     * v1.0.74 fix (前端审计 3.3): 原与 medium 同为 LongPress,危险操作与普通点击
     * 触觉无差别。改用 Confirm(Android 13+ 有明确确认感,低版本系统自动降级)。
     */
    fun heavy(haptic: HapticFeedback) {
        // v1.0.74 fix (前端审计 3.3): 当前 Compose 版本可用触觉档位有限
        // (仅 TextHandleMove/LongPress),heavy 用双次 LongPress 模拟更强确认感,
        // 与 medium 单次 LongPress 形成差异。
        if (enabled) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
}
