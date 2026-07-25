package io.zer0.muse.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import io.zer0.muse.ui.theme.MuseShapes

/**
 * v0.34 / v1.0.26: 卡片分组组件设计(Muse UI Kit 重写)。
 *
 * 一个 [CardGroup] 内的多个 [item] 共享同一个圆角卡片容器:
 *  - 第一项顶部圆角 20dp,最后一项底部圆角 20dp,中间项直角拼接
 *  - 可选的分组标题(primary 色 + titleSmall 字体)
 *  - item 之间用 [MuseDivider] 分隔(56dp 缩进,对齐 iOS Settings)
 *
 * v1.0.26 重构:
 *  - 容器改用 [MuseSurface] 基元,统一 clip/color/shadow 处理
 *  - 列表项改用 [MuseListItem] 基元,统一 padding/字号/颜色
 *  - 分隔线改用 [MuseDivider] 基元,统一 thickness/color/缩进
 *  - 移除 ListItem 自带圆角动画(中间项不再 clip 圆角,由外层 MuseSurface 统一裁切)
 *
 * 用法(DSL 风格):
 * ```
 * CardGroup(
 *     title = { Text("通用") },
 *     modifier = Modifier.padding(horizontal = 16.dp),
 * ) {
 *     item(
 *         onClick = { navController.navigate(...) },
 *         leadingContent = { Icon(Icons.Outlined.Sun, null) },
 *         headlineContent = { Text("深色模式") },
 *         supportingContent = { Text("跟随系统") },
 *         trailingContent = { Switch(checked=..., onCheckedChange=...) },
 *     )
 * }
 * ```
 *
 * 适配 muse 的 Material Icons(而非 HugeIcons)和 warm-paper 主题。
 */
private data class CardGroupItem(
    val key: Any?,
    val onClick: (() -> Unit)?,
    val modifier: Modifier,
    val headlineContent: @Composable () -> Unit,
    val supportingContent: (@Composable () -> Unit)?,
    val leadingContent: (@Composable () -> Unit)?,
    val trailingContent: (@Composable () -> Unit)?,
)

@DslMarker
private annotation class CardGroupDsl

@CardGroupDsl
interface CardGroupScope {
    /**
     * 卡片分组内的一项。
     *
     * @param onClick 点击回调(null 则不可点击)
     * @param headlineContent 主标题(必填)
     * @param supportingContent 副标题(灰色小字,可选)
     * @param leadingContent 左侧内容(通常是图标,可选)
     * @param trailingContent 右侧内容(Switch / 箭头 / Select / Slider 等,可选)
     * @param modifier 该项的 Modifier(默认 Modifier)
     */
    fun item(
        key: Any? = null,
        onClick: (() -> Unit)? = null,
        modifier: Modifier = Modifier,
        supportingContent: (@Composable () -> Unit)? = null,
        leadingContent: (@Composable () -> Unit)? = null,
        trailingContent: (@Composable () -> Unit)? = null,
        headlineContent: @Composable () -> Unit,
    )
}

private class CardGroupScopeImpl : CardGroupScope {
    val items = mutableListOf<CardGroupItem>()

    override fun item(
        key: Any?,
        onClick: (() -> Unit)?,
        modifier: Modifier,
        supportingContent: (@Composable () -> Unit)?,
        leadingContent: (@Composable () -> Unit)?,
        trailingContent: (@Composable () -> Unit)?,
        headlineContent: @Composable () -> Unit,
    ) {
        items.add(
            CardGroupItem(
                key = key,
                onClick = onClick,
                modifier = modifier,
                headlineContent = headlineContent,
                supportingContent = supportingContent,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
            ),
        )
    }
}

/**
 * v0.34 / v1.0.26: 卡片分组容器(Muse UI Kit 重写)。
 *
 * 用法见文件顶部文档。所有 [content] 内的 [CardGroupScope.item] 调用会被收集,
 * 渲染为一个共享圆角背景的卡片组。
 *
 * @param modifier 整个卡片组(含标题)的 Modifier
 * @param title 可选的分组标题(primary 色,显示在卡片上方)
 * @param content DSL 内容块,内部用 [CardGroupScope.item] 添加项
 */
@Composable
fun CardGroup(
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    content: CardGroupScope.() -> Unit,
) {
    val scope = CardGroupScopeImpl()
    scope.content()

    Column(modifier = modifier) {
        // 标题在卡片外部,不被卡片 clip 影响
        // v1.0.27 修复:对齐 iOS 设置分组标题 — 灰色小字、紧凑间距
        if (title != null) {
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            ) {
                ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                    Box(
                        modifier = Modifier
                            // 标题左边缘与卡片内列表项文字左边缘对齐(56dp = 16dp 卡片内边距 + 24dp 图标 + 16dp 图标到文字间隙),
                            // 上下间距收紧到 6dp,更贴近 iOS 设置的分组标题节奏。
                            .padding(start = 56.dp, top = 6.dp, bottom = 6.dp)
                            .fillMaxWidth(),
                    ) {
                        title()
                    }
                }
            }
        }

        // 卡片容器:用 MuseSurface 基元统一 clip/color/shadow
        // v1.0.27 修复:背景改为 surface(纯白/暖白),取消阴影 — 根治"灰块拼接"错觉
        MuseSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = MuseShapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            elevation = 0.dp,
            tonalElevation = 0.dp,
            enablePressedFeedback = false, // 容器本身不响应按压,按压反馈由内部 MuseListItem 处理
        ) {
            Column {
                val count = scope.items.size
                scope.items.fastForEachIndexed { index, item ->
                    // 用 key 包裹,使带 key 的列表项在增删/重排时保留 remember 状态(如交互源、展开态)
                    key(item.key ?: index) {
                        MuseListItem(
                            onClick = item.onClick,
                            modifier = item.modifier,
                            headlineContent = item.headlineContent,
                            supportingContent = item.supportingContent,
                            leadingContent = item.leadingContent,
                            trailingContent = item.trailingContent,
                        )
                    }
                    // 用 MuseDivider 基元分隔 item,统一 thickness/color/缩进
                    if (index != count - 1) {
                        MuseDivider()
                    }
                }
            }
        }
    }
}
