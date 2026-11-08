package io.zer0.muse.ui.markdown.card

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 36：内联卡片的视口判据（纯函数，不碰 WebView / Compose，能单测）。
 *
 * 为什么需要它：长卡片此前被撑到「内容满高 + 卡内不滚」，本意是躲开卡内滚与外层聊天抢手势
 * （39② 的老问题），结果超过约一屏时外层也拿不到手势 → 变成滚不动的死区。
 * 这里把它拆成可测的三问：内联该多高、有没有溢出、聚焦后这一下竖滑归谁。
 *
 * 安全底线：**未聚焦一律 PassToChat**，所以引入这套逻辑不会回退 39②——只有「已聚焦 且
 * 内容确实比可视区高」才让卡自己滚，短卡或没聚焦时行为与今天完全一致。
 */

/** 内联卡片高度 = min(内容测量高, 上限高)。测量未到（<=0）时按 0 占位，避免先撑大盒再缩。 */
fun effectiveInlineHeightPx(measuredPx: Int, capPx: Int): Int =
    if (measuredPx <= 0) 0 else minOf(measuredPx, capPx)

/** 内容测量高是否超过可视上限（超过才有卡内可滚的东西）。测量未到一律 false。 */
fun hasInlineOverflow(measuredPx: Int, capPx: Int): Boolean =
    measuredPx > 0 && measuredPx > capPx

enum class CardScrollMode {
    /** 竖滑交回外层聊天列表（默认；未聚焦或没有可滚内容时）。 */
    PassToChat,

    /** 卡内部滚动（已聚焦且内容溢出可视上限时）。 */
    InternalScroll,
}

/** 滚动归属：只有「聚焦 且 溢出」才卡内滚，其余一律交回列表。 */
fun resolveCardScrollMode(focused: Boolean, hasOverflow: Boolean): CardScrollMode =
    if (focused && hasOverflow) CardScrollMode.InternalScroll else CardScrollMode.PassToChat

/**
 * 全局卡片焦点协调：同一时刻最多一张卡处于「可卡内滚动」的聚焦态。
 *
 * 点新卡自动松开旧卡，点外部/返回清除。这是 HANA 式 tap-to-focus 的状态源；
 * 与手势的具体接线分开，便于对「谁能被聚焦」这条不变式做单测。
 */
class CardFocusController {
    private val focusedId = MutableStateFlow<String?>(null)
    val state: StateFlow<String?> = focusedId.asStateFlow()

    fun focus(cardId: String) {
        focusedId.value = cardId
    }

    fun clearFocus() {
        focusedId.value = null
    }

    fun isFocused(cardId: String): Boolean = focusedId.value == cardId
}

/** 内联 Auto 卡片聚焦后可卡内滚的高度上限（dp）；超过它才需要内部滚动。 */
internal const val MAX_INLINE_CARD_DP = 420

/**
 * 卡片焦点的 CompositionLocal，默认共享一份（聊天与产物查看器共用）。
 * 点新卡自动松开旧卡；未提供时也用这份默认，故无需逐层穿线。
 */
val LocalCardFocusController = staticCompositionLocalOf { CardFocusController() }
