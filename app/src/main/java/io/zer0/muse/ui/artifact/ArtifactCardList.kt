package io.zer0.muse.ui.artifact

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.zer0.muse.data.artifact.ArtifactEntity

/**
 * 产物卡片横向列表 —— 用于消息气泡底部展示该消息抽取出的全部产物。
 *
 * Phase 2: 超过 [MAX_VISIBLE_ARTIFACT_CARDS] 个时只展示前 5 个,
 * 尾部用 "+N" 卡片折叠,点击后展开全部(保证被折叠的产物仍可访问)。
 */
@Composable
fun ArtifactCardList(
    artifacts: List<ArtifactEntity>,
    onArtifactClick: (ArtifactEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 以首条产物 id 作为保存键:消息切换(列表内容变化)时重置展开态
    var expanded by rememberSaveable(artifacts.firstOrNull()?.id) { mutableStateOf(false) }
    val visible = if (expanded) artifacts else artifacts.take(MAX_VISIBLE_ARTIFACT_CARDS)
    val hiddenCount = artifacts.size - visible.size

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(
            items = visible,
            key = { it.id },
        ) { artifact ->
            ArtifactCard(
                artifact = artifact,
                onClick = { onArtifactClick(artifact) },
            )
        }
        if (hiddenCount > 0) {
            item(key = "artifact-overflow") {
                ArtifactOverflowCard(
                    hiddenCount = hiddenCount,
                    onClick = { expanded = true },
                )
            }
        }
    }
}
