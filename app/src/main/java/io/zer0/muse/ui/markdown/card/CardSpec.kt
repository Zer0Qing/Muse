package io.zer0.muse.ui.markdown.card

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.markdown.CardAction

/**
 * 卡片要渲染成什么 —— 纯数据，不依赖 Compose / Android / WebView。
 *
 * 重写的第一条规矩：渲染所需的一切在进入容器之前就已经确定。
 * 旧实现把「源码、尺寸、绑定数据、是否可交互」散在七八个参数里，
 * 容器内部再层层推导，最后谁决定什么说不清。
 */
internal data class CardSpec(
    /** 源码语言，决定走哪条渲染路径。 */
    val language: CardLanguage,
    /** 源码正文。 */
    val code: String,
    /** 尺寸意图。默认跟随内容。 */
    val size: CardSize = CardSize.Auto,
    /** 宿主绑定的 JSON 数据（show_card 的 data 参数），null 表示没有。 */
    val data: String? = null,
)

/** 卡片渲染路径。 */
internal enum class CardLanguage { HTML, SVG, CHART, MERMAID, PLAIN }

/**
 * 尺寸意图。
 *
 * 只有三态，没有第四种 —— 旧实现里「固定高度 + 卡内滚动 + 测量跟随」可以任意叠加，
 * 叠加的结果是任何一方都能改高度。这里把它收敛成互斥的选择。
 */
internal sealed interface CardSize {
    /** 跟随内容：高度 = 内容高度，卡片自身不滚动（没有可滚的东西）。 */
    data object Auto : CardSize

    /** 固定高度：超出部分在卡片内滚动。 */
    data class Fixed(val height: Dp) : CardSize

    /** 宽高比：宽度撑满，高度按比例，内容可能超出 → 卡内滚动。 */
    data class Ratio(val ratio: Float) : CardSize
}

/**
 * 解析 show_card 的 height 参数。
 *
 * 容错而非报错：模型写成 `520`、`520dp`、`520 px`、`16:9`、`16：9` 都认；
 * 认不出的回 Auto —— 一个尺寸写歪不该让整张卡片不显示。
 */
internal fun parseCardSize(raw: String?): CardSize {
    val text = raw?.trim()?.lowercase().orEmpty()
    if (text.isEmpty() || text == "auto") return CardSize.Auto
    return parseRatio(text) ?: parseFixed(text)
}

/** `16:9` / `16：9` / `4x3`。 */
private fun parseRatio(text: String): CardSize.Ratio? {
    val parts = text.split(':', '：', 'x').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.size != 2) return null
    val w = parts[0].toFloatOrNull()
    val h = parts[1].toFloatOrNull()
    val usable = w != null && h != null && w > 0f && h > 0f
    return if (usable) CardSize.Ratio(w / h) else null
}

/** `520` / `520dp` / `520 px`。数值按 dp —— 模型不可能知道目标设备的像素密度。 */
private fun parseFixed(text: String): CardSize {
    val digits = text.removeSuffix("dp").removeSuffix("px").trim()
    val value = digits.toFloatOrNull()
    return if (value != null && value > 0f) CardSize.Fixed(value.dp) else CardSize.Auto
}

/** 根据源码嗅探语言（代码围栏没写语言时用）。 */
internal fun detectCardLanguage(code: String): CardLanguage =
    when {
        code.trimStart().startsWith("<svg", ignoreCase = true) -> CardLanguage.SVG
        code.trimStart().startsWith("<?xml", ignoreCase = true) -> CardLanguage.SVG
        else -> CardLanguage.HTML
    }

/**
 * 宿主侧提供给卡片的全部能力，打包成一个对象。
 *
 * 分开传会和尺寸、语言混在一起，参数一路涨到十几个；而且「这张卡能不能回传」
 * 是一个整体判断，不该由调用方逐项声明。
 */
internal data class CardHost(
    /** 交互回传回调；null 表示无桥（工件查看、任务卡步骤等只读场景）。 */
    val onAction: ((CardAction) -> Unit)? = null,
    /** 按 cardId 读绑定数据；null 表示不提供数据。 */
    val readData: ((String) -> String?)? = null,
    /** 卡片身份。只由宿主产生（消息 id / 卡片实例 id），工具不参与。 */
    val cardId: String? = null,
)
