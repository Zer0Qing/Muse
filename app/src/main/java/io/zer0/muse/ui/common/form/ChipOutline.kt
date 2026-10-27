package io.zer0.muse.ui.common.form

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge

/**
 * 给选择胶囊补一条细描边。
 *
 * 白底容器（弹窗、卡片）里，未选中的 [MuseChip] 与背景同为白色，几乎看不出
 * 可点击边界。这里用 Modifier 扩展补描边，不改 MuseChip 的参数列表
 * ——它已有 6 个参数，再加会超出静态检查阈值，也会影响既有调用方。
 *
 * 用法：`MuseChip(..., modifier = Modifier.chipOutline(selected = selected))`
 */
@Composable
fun Modifier.chipOutline(selected: Boolean): Modifier =
    if (selected) {
        this
    } else {
        this.border(
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = MuseShapes.semiLarge,
        )
    }
