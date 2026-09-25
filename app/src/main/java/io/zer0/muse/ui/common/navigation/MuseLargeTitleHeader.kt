package io.zer0.muse.ui.common.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.zer0.muse.R
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
fun MuseLargeTitleHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (onBack != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MusePaddings.chipInnerTight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MuseTopBarIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
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
fun MuseStickyBackButton(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MuseTopBarIconButton(
        icon = Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = stringResource(R.string.action_back),
        onClick = onBack,
        modifier = modifier,
        solid = true,
    )
}
