package io.zer0.muse.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode

/**
 * 全局无涟漪按压指示器 — 按下时没有任何视觉覆盖。
 *
 * 背景:此前全局使用 Material3 默认 ripple(圆形扩散遮罩)。其半径按"触摸点到组件
 * 最远角"计算,在整行宽卡片/列表行上点一下会扩散出覆盖大半张卡片的大灰圆,
 * 视觉噪音过大。产品决策:去掉所有圆形按压遮罩,按压反馈统一交给组件自绘
 * (例如弹窗按钮的按压缩放动画)。
 *
 * 由 MuseTheme 通过 `LocalIndication provides MuseNoRippleIndication` 全局注入;
 * M3 组件内部的 ripple 由同一层的 `LocalRippleConfiguration provides null` 关闭。
 */
object MuseNoRippleIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = NoOpIndicationNode()

    // IndicationNodeFactory 把 equals/hashCode 声明为抽象成员(相等性参与 Compose 优化):
    // 本工厂是单例,恒等比较;hashCode 用固定常量满足"同一工厂恒定"的契约。
    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = 0x4D757365 // "Muse"
}

/**
 * 空实现 — 不绘制任何内容,仅占位满足 indication 协议。
 *
 * 必须是每次 [MuseNoRippleIndication.create] 新建的实例:同一个 Modifier.Node
 * 不能被多处 attach(会违反 Node 生命周期契约)。
 */
private class NoOpIndicationNode : Modifier.Node()
