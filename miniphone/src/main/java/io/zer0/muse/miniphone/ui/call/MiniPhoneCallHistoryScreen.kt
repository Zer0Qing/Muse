package io.zer0.muse.miniphone.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.miniphone.R
import io.zer0.muse.miniphone.data.call.MpCallKind
import io.zer0.muse.miniphone.data.db.entity.MpCallSessionEntity
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme

/**
 * 通话记录：把语音与视频通话按时间倒序列成一条时间线，只读展示。
 *
 * 通话本身由引擎负责，这里只呈现挂断时落下的那一行；
 * 时长为 0 表示没接通，不显示成「通话 0 秒」。
 */
@Composable
internal fun MiniPhoneCallHistoryScreen(
    history: kotlinx.coroutines.flow.Flow<List<MpCallSessionEntity>>,
    namesById: Map<String, String>,
    onBack: () -> Unit,
    timeLabel: (Long) -> String = { it.toString() },
) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    val entries by history.collectAsStateWithLifecycle(initialValue = emptyList())
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(MiniPhoneCallTags.PAGE)
            .background(colors.background),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = MiniPhoneIcons.Back,
                contentDescription = stringResource(R.string.mp_room_back),
                tint = colors.textPrimary,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .padding(6.dp)
                    .size(22.dp)
                    .testTag(MiniPhoneCallTags.BACK),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.mp_calls_title),
                style = typography.body.copy(color = colors.textPrimary),
            )
        }
        if (entries.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp).testTag(MiniPhoneCallTags.EMPTY),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.mp_calls_empty),
                    style = typography.caption,
                    color = colors.textSecondary,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = MiniPhoneTheme.dimens.pageMargin, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    CallHistoryRow(entry = entry, name = namesById[entry.characterId] ?: entry.characterId, timeLabel = timeLabel)
                }
            }
        }
    }
}

@Composable
private fun CallHistoryRow(entry: MpCallSessionEntity, name: String, timeLabel: (Long) -> String) {
    val typography = MiniPhoneTheme.typography
    val colors = MiniPhoneTheme.colors
    val kindLabel = when (MpCallKind.fromWire(entry.kind)) {
        MpCallKind.VOICE -> stringResource(R.string.mp_calls_voice)
        MpCallKind.VIDEO -> stringResource(R.string.mp_calls_video)
    }
    val detail = if (entry.durationMs == 0L) {
        stringResource(R.string.mp_calls_missed)
    } else {
        kindLabel
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MiniPhoneCallTags.row(entry.id))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = typography.body.copy(color = colors.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = typography.caption.copy(color = colors.textSecondary),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = timeLabel(entry.startedAt),
            style = typography.caption,
            color = colors.textSecondary,
        )
    }
}
