package io.zer0.muse.miniphone.ui.innervoice

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
import io.zer0.muse.miniphone.data.chat.MpInnerVoiceStore
import io.zer0.muse.miniphone.data.db.entity.MpInnerVoiceEntity
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme

/**
 * 心声历史：把各会话里角色没说出口的内心独白汇成一条时间线，只读展示。
 *
 * 布局沿用 RoomPage 的「自绘页头（返回 + 标题）+ 内容区」范式，不引额外导航栈。
 */
@Composable
internal fun MiniPhoneInnerVoiceHistoryScreen(store: MpInnerVoiceStore, namesById: Map<String, String>, onBack: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    val entries by store.observeHistory(HISTORY_LIMIT).collectAsStateWithLifecycle(initialValue = emptyList())
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("mp-inner-voice-history-page")
            .background(colors.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
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
                    .testTag("mp-inner-voice-history-back"),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.mp_inner_voice_history_title),
                style = typography.body.copy(color = colors.textPrimary),
            )
        }
        if (entries.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.mp_inner_voice_history_empty),
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
                    InnerVoiceHistoryRow(
                        entry = entry,
                        name = namesById[entry.characterId] ?: entry.characterId,
                    )
                }
            }
        }
    }
}

@Composable
private fun InnerVoiceHistoryRow(entry: MpInnerVoiceEntity, name: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("mp-inner-voice-history-item-${entry.id}")
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = name,
            style = MiniPhoneTheme.typography.caption,
            color = MiniPhoneTheme.colors.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = entry.text,
            style = MiniPhoneTheme.typography.body,
            color = MiniPhoneTheme.colors.textSecondary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private const val HISTORY_LIMIT = 200
