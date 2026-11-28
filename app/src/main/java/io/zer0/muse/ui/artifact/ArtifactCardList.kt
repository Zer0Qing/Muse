package io.zer0.muse.ui.artifact

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.ui.theme.MusePaddings

/**
 * 产物卡片列表 —— 用于消息气泡底部展示该消息抽取出的独立产物。
 *
 * 超过 [MAX_VISIBLE_ARTIFACT_CARDS] 个时折叠展示尾部 "+N" 卡片，点击后展开全部。
 */
@Composable
fun ArtifactCardList(artifacts: List<ArtifactEntity>, onArtifactClick: (ArtifactEntity) -> Unit, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(artifacts.firstOrNull()?.id) { mutableStateOf(false) }
    val visible = if (expanded) artifacts else artifacts.take(MAX_VISIBLE_ARTIFACT_CARDS)
    val hiddenCount = artifacts.size - visible.size

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        visible.forEach { artifact ->
            ArtifactCard(
                artifact = artifact,
                onClick = { onArtifactClick(artifact) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (hiddenCount > 0) {
            ArtifactOverflowCard(
                hiddenCount = hiddenCount,
                onClick = { expanded = true },
            )
        }
    }
}
