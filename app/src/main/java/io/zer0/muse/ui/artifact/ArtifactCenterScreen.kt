package io.zer0.muse.ui.artifact

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.data.artifact.ArtifactRepository
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.theme.MusePaddings
import org.koin.compose.koinInject

/**
 * v2.0.1: 产物中心 — 汇总所有会话的产物（全宽大卡列表）。
 *
 * 点击卡片打开：富内容（HTML/SVG/Chart/Mermaid）进全屏浏览器形态，
 * 其余（代码/文本）走弹窗查看器（共用 [ArtifactOpenHost]）。
 * 入口：工作区页顶栏。
 */
@Composable
fun ArtifactCenterScreen(
    onBack: () -> Unit,
) {
    val repository: ArtifactRepository = koinInject()
    val artifacts by repository.observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var opened by remember { mutableStateOf<ArtifactEntity?>(null) }

    MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.artifact_center_title),
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        // v2.0.1 fix（真机崩溃防御）: 同 ConnectionCenter — 有限高度收口后再挂网格。
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val boundedHeight = io.zer0.muse.ui.settings.boundedSettingsScrollHeight(
                parentMaxHeight = maxHeight,
                windowHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp,
            )
            if (artifacts.isEmpty()) {
                MuseEmptyState(
                    title = stringResource(R.string.artifact_center_empty),
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                // v2.0.1: Library 网格 — 两列缩略浏览（大卡在网格内自适应收窄）
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = boundedHeight),
                    contentPadding = PaddingValues(
                        start = MusePaddings.screen,
                        end = MusePaddings.screen,
                        top = 12.dp,
                        bottom = 24.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(count = artifacts.size, key = { artifacts[it].id }) { idx ->
                        val artifact = artifacts[idx]
                        ArtifactCard(
                            artifact = artifact,
                            onClick = { opened = artifact },
                        )
                    }
                }
            }
        }
    }
    ArtifactOpenHost(artifact = opened, onDismiss = { opened = null })
}
