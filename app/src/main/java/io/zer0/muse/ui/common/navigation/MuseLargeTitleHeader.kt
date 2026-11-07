package io.zer0.muse.ui.common.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MusePaddings

/**
 * v2.0.1: 可滚动大标题头部（设置首页 / 设置子页共用）。
 *
 * 作为 LazyColumn 的首项，随内容滚动推出；吸顶搜索栏由页面 / 骨架另行提供
 * （见 SettingsScreen 与 SettingsSubPageScaffold 的 sticky 搜索栏）。
 *
 * 注意：本组件**不含**状态栏内边距——由外层滚动容器统一处理 statusBarsPadding，
 * 避免双重内边距。
 *
 * @param onBack 返回回调；为 null（如平板双列模式）时不渲染返回行。
 */
@Composable
fun MuseLargeTitleHeader(title: String, onBack: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (onBack != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MusePaddings.chipInnerTight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MuseTopBarIconButton(
                    icon = MuseIcons.arrowLeft,
                    contentDescription = stringResource(R.string.action_back),
                    onClick = onBack,
                    solid = false,
                )
                Spacer(Modifier.weight(1f))
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(
                horizontal = MusePaddings.messageGap,
                vertical = MusePaddings.tightGap,
            ),
        )
    }
}

/**
 * v2.0.1: 吸顶返回键 — 滚动头部推出后，左上角渐显的圆底返回按钮。
 *
 * 与 [MuseLargeTitleHeader] 配合使用：外层滚动容器判定推出状态后渐显本按钮，
 * 保证大标题（含返回行）滚出屏幕后仍可返回。默认尺寸与头部返回键对齐。
 */
@Composable
fun MuseStickyBackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    MuseTopBarIconButton(
        icon = MuseIcons.arrowLeft,
        contentDescription = stringResource(R.string.action_back),
        onClick = onBack,
        modifier = modifier,
        solid = true,
    )
}

/**
 * 双平面翻新：设置子页的紧凑标题头部 —— 圆底返回键 + 22sp 标题同排。
 *
 * 与首页 [MuseLargeTitleHeader]（34sp 大标题、返回键独占一行）区分层级：
 * 首页是「 destination 的大标题」，子页是「某组设置的紧凑标题」。子页不再借用大标题，
 * 避免每进一组都撑出一屏高的头部。标题复用现有 headlineMedium(22sp SemiBold)，不改字重体系。
 *
 * 同样**不含**状态栏内边距，由外层滚动容器统一处理。
 *
 * @param onBack 返回回调；为 null（平板双列右栏）时只渲染标题、不占返回键位。
 */
@Composable
fun MuseCompactTitleHeader(title: String, onBack: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.pageMargin, vertical = MusePaddings.tightGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            MuseTopBarIconButton(
                icon = MuseIcons.arrowLeft,
                contentDescription = stringResource(R.string.action_back),
                onClick = onBack,
                solid = true,
            )
            Spacer(Modifier.width(MusePaddings.contentGap))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
