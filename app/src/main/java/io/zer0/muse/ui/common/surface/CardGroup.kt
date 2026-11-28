package io.zer0.muse.ui.common.surface

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
import androidx.compose.ui.util.fastForEachIndexed
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.largeCard

/**
 * v0.34 / v1.0.26: 卡片分组组件设计(Muse UI Kit 重写)。
 *
 * 一个 [CardGroup] 内的多个 [item] 共享同一个圆角卡片容器:
 *  - 第一项顶部圆角 20dp,最后一项底部圆角 20dp,中间项直角拼接
 *  - 可选的分组标题(primary 色 + titleSmall 字体)
 *  - item 之间用 [MuseDivider] 分隔(缩进跟随内容左边线:有图标缩到文字列,无图标落行内边距)
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
 *         leadingContent = { Icon(MuseIcons.sun, null) },
 *         headlineContent = { Text("深色模式") },
 *         supportingContent = { Text("跟随系统") },
 *         trailingContent = { Switch(checked=..., onCheckedChange=...) },
 *     )
 * }
 * ```
 *
 * 适配 muse 的 Material Icons(而非 HugeIcons)和 warm-paper 主题。
 */
@PublishedApi
internal sealed interface CardGroupEntry {
    val key: Any?
    val hasLeading: Boolean

    data class ListRow(
        override val key: Any?,
        val onClick: (() -> Unit)?,
        val onLongClick: (() -> Unit)?,
        val modifier: Modifier,
        val headlineContent: @Composable () -> Unit,
        val supportingContent: (@Composable () -> Unit)?,
        val leadingContent: (@Composable () -> Unit)?,
        val trailingContent: (@Composable () -> Unit)?,
    ) : CardGroupEntry {
        override val hasLeading: Boolean get() = leadingContent != null
    }

    data class CustomContent(
        override val key: Any?,
        val content: @Composable () -> Unit,
    ) : CardGroupEntry {
        override val hasLeading: Boolean get() = false
    }
}

@DslMarker
private annotation class CardGroupDsl

@CardGroupDsl
interface CardGroupScope {
    /**
     * 卡片分组内的一项。
     *
     * @param onClick 点击回调(null 则不可点击)
     * @param onLongClick 长按回调(可选,与 onClick 同时传入时长按弹菜单)
     * @param headlineContent 主标题(必填)
     * @param supportingContent 副标题(灰色小字,可选)
     * @param leadingContent 左侧内容(通常是图标,可选)
     * @param trailingContent 右侧内容(Switch / 箭头 / Select / Slider 等,可选)
     * @param modifier 该项的 Modifier(默认 Modifier)
     */
    fun item(
        key: Any? = null,
        onClick: (() -> Unit)? = null,
        onLongClick: (() -> Unit)? = null,
        modifier: Modifier = Modifier,
        supportingContent: (@Composable () -> Unit)? = null,
        leadingContent: (@Composable () -> Unit)? = null,
        trailingContent: (@Composable () -> Unit)? = null,
        headlineContent: @Composable () -> Unit,
    )

    /**
     * 卡片分组内的自定义内容块（不包一层 [MuseListItem]，避免表单列 / 滑块行 / 分段控件出现双重内边距），
     * 仍享受统一的卡片圆角容器与自动 [MuseDivider] 分隔线。
     */
    fun content(key: Any? = null, content: @Composable () -> Unit)
}

@PublishedApi
internal class CardGroupScopeImpl : CardGroupScope {
    val items = mutableListOf<CardGroupEntry>()

    override fun item(
        key: Any?,
        onClick: (() -> Unit)?,
        onLongClick: (() -> Unit)?,
        modifier: Modifier,
        supportingContent: (@Composable () -> Unit)?,
        leadingContent: (@Composable () -> Unit)?,
        trailingContent: (@Composable () -> Unit)?,
        headlineContent: @Composable () -> Unit,
    ) {
        items.add(
            CardGroupEntry.ListRow(
                key = key,
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = modifier,
                headlineContent = headlineContent,
                supportingContent = supportingContent,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
            ),
        )
    }

    override fun content(key: Any?, content: @Composable () -> Unit) {
        items.add(CardGroupEntry.CustomContent(key = key, content = content))
    }
}

/**
 * 卡片分组容器（Muse UI Kit）。
 *
 * 必须声明为 `inline`（且 [content] 不得 `noinline`）：否则 Compose 会为 [content] 生成独立的
 * RecomposeScope，导致块内读取的 State（如 `switchItem` 的 `checked` 或 `if (enabled)` 条件项）
 * 变化时只重组 [content] 自身而不重组外层容器，界面卡死在初始状态。
 *
 * @param modifier 整个卡片组(含标题)的 Modifier
 * @param title 可选的分组标题(显示在卡片上方)
 * @param content DSL 内容块,内部用 [CardGroupScope.item] 添加项
 */
@Composable
inline fun CardGroup(
    modifier: Modifier = Modifier,
    noinline title: (@Composable () -> Unit)? = null,
    content: @Composable CardGroupScope.() -> Unit,
) {
    val scope = CardGroupScopeImpl()
    scope.content()
    CardGroupContainer(
        modifier = modifier,
        title = title,
        entries = scope.items,
    )
}

@PublishedApi
@Composable
internal fun CardGroupContainer(modifier: Modifier, title: (@Composable () -> Unit)?, entries: List<CardGroupEntry>) {
    Column(modifier = modifier) {
        // 标题在卡片外部,不被卡片 clip 影响
        if (title != null) {
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = MuseAlpha.titleMuted),
            ) {
                ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                    Box(
                        modifier = Modifier
                            // 分组标题对齐卡片内侧边线,不按假定图标宽度额外缩进。
                            .padding(
                                start = MusePaddings.screen,
                                end = MusePaddings.screen,
                                top = MusePaddings.cardGap,
                                bottom = MusePaddings.contentGap,
                            )
                            .fillMaxWidth(),
                    ) {
                        title()
                    }
                }
            }
        }

        // 卡片容器:用 MuseSurface 基元统一 clip/color/shadow
        MuseSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = MuseShapes.largeCard,
            color = MaterialTheme.colorScheme.surface,
            elevation = MuseElevation.micro,
            tonalElevation = MuseElevation.none,
            // 容器本身不响应按压,按压反馈由内部 MuseListItem 处理
            enablePressedFeedback = false,
        ) {
            Column {
                val count = entries.size
                // 分隔线起点跟随内容左边线——整组有 leading 图标时缩到图标右侧文字列,
                // 无图标卡落到行内边距。
                val dividerIndent = museDividerStartIndent(hasLeading = entries.any { it.hasLeading })
                entries.fastForEachIndexed { index, entry ->
                    // 用 key 包裹,使带 key 的列表项在增删/重排时保留 remember 状态(如交互源、展开态)
                    key(entry.key ?: index) {
                        when (entry) {
                            is CardGroupEntry.ListRow -> MuseListItem(
                                onClick = entry.onClick,
                                onLongClick = entry.onLongClick,
                                modifier = entry.modifier,
                                headlineContent = entry.headlineContent,
                                supportingContent = entry.supportingContent,
                                leadingContent = entry.leadingContent,
                                trailingContent = entry.trailingContent,
                            )
                            is CardGroupEntry.CustomContent -> entry.content()
                        }
                    }
                    // 用 MuseDivider 基元分隔 item,统一 thickness/color/缩进
                    if (index != count - 1) {
                        MuseDivider(startIndent = dividerIndent)
                    }
                }
            }
        }
    }
}
