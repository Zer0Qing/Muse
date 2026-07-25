package io.zer0.muse.ui.artifact

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.zer0.muse.data.artifact.ArtifactEntity

/**
 * 产物卡片横向列表 —— 用于消息气泡底部展示该消息抽取出的全部产物。
 */
@Composable
fun ArtifactCardList(
    artifacts: List<ArtifactEntity>,
    onArtifactClick: (ArtifactEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(
            items = artifacts,
            key = { it.id },
        ) { artifact ->
            ArtifactCard(
                artifact = artifact,
                onClick = { onArtifactClick(artifact) },
            )
        }
    }
}
