package io.zer0.muse.ui.startup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.session.MigrationPhase
import io.zer0.muse.data.session.MigrationProgressBus

/**
 * v2.7.x(S3): 迁移进行时的全屏提示层。
 *
 * 只在真有迁移在跑时出现(总线 phase 为 RUNNING/FAILED);平时**一个节点都不生成** ——
 * 冷启动多一层 Surface 就多一次首帧成本,而这条路径绝大多数启动都不会走到。
 *
 * 说「第几步 / 共几步」而不是画一个不确定的转圈:用户能据此判断还要等多久,
 * 报障时也能说清「卡在 7/9」。「共几步」是本次要跑的步数,不是整条链的长度(126)。
 */
@Composable
fun MigrationProgressOverlay(modifier: Modifier = Modifier) {
    val progress by MigrationProgressBus.progress.collectAsState()
    if (!progress.visible) return

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 360.dp)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (progress.phase == MigrationPhase.FAILED) {
                        stringResource(R.string.migration_failed)
                    } else {
                        stringResource(
                            R.string.migration_running,
                            (progress.completedSteps + 1).coerceAtMost(progress.totalSteps),
                            progress.totalSteps,
                        )
                    },
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                if (progress.phase == MigrationPhase.RUNNING && progress.currentToVersion > 0) {
                    Text(
                        text = stringResource(
                            R.string.migration_step,
                            progress.currentFromVersion,
                            progress.currentToVersion,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    progress = {
                        if (progress.totalSteps <= 0) {
                            0f
                        } else {
                            progress.completedSteps.toFloat() / progress.totalSteps.toFloat()
                        }
                    },
                )
            }
        }
    }
}
