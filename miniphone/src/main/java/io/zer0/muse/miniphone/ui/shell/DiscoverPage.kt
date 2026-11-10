package io.zer0.muse.miniphone.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme

/**
 * 发现页：应用网格。
 *
 * 入口来自 [MiniPhoneFeatureCatalog]，加一个模块不必再改本页。
 * 用可滚动的网格而不是固定列数的 Column —— 模块会一直加下去，
 * 不可滚动的排法会让排在后面的入口落到屏幕外，用户根本点不到。
 */
@Composable
internal fun DiscoverPage(modifier: Modifier = Modifier, onOpenFeature: (MiniPhoneFeature) -> Unit = {}) {
    val dimens = MiniPhoneTheme.dimens
    LazyVerticalGrid(
        columns = GridCells.Fixed(MiniPhoneApps.COLUMNS),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = dimens.pageMargin, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(MiniPhoneFeatureCatalog.entries, key = { it.id }) { feature ->
            DiscoverEntry(
                icon = MiniPhoneFeatureCatalog.iconOf(feature),
                label = stringResource(feature.labelRes),
                tag = feature.tag,
                onClick = { onOpenFeature(feature) },
            )
        }
    }
}

@Composable
private fun DiscoverEntry(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tag: String, onClick: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val shape = RoundedCornerShape(dimens.cardRadius)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .clickable(onClick = onClick)
            .testTag(tag)
            // 4 列网格里单个长单词（如 Relationships）会被按字符断成两行，
            // 收紧左右内边距把可用文字宽度还给标签。
            .padding(horizontal = 4.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = label,
            // 小一档的既有字号，让长标签在窄格里整体放下而不是逐字符断行。
            style = typography.timestamp.copy(color = colors.textPrimary),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
    }
}
