package io.zer0.muse.miniphone.ui.misc

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.muse.miniphone.R
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.shell.MiniPhoneThreadUi
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme

/**
 * 群红包的入口：先选一个群，再看那个群的红包。
 *
 * 发现页没有「当前会话」，所以不能随手拿第一个群顶上 —— 那会让用户以为红包发到了别的群。
 * 一个群都没有时明确说出来，而不是给一个空列表让人以为坏了。
 */
@Composable
internal fun MiniPhoneRedPacketEntry(threads: List<MiniPhoneThreadUi>, onBack: () -> Unit) {
    val groups = threads.filter { it.isGroup }
    var picked by remember { mutableStateOf<String?>(null) }
    val chosen = picked
    if (chosen != null) {
        MiniPhoneRedPacketFeature(threadId = chosen, onBack = { picked = null })
        return
    }
    val colors = MiniPhoneTheme.colors
    Column(
        modifier = Modifier.fillMaxSize().testTag("mp-redpacket-entry").background(colors.background),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = MiniPhoneIcons.Back,
                contentDescription = stringResource(R.string.mp_room_back),
                tint = colors.textPrimary,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onBack)
                    .padding(6.dp).size(22.dp).testTag("mp-redpacket-entry-back"),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.mp_discover_redpacket),
                style = MiniPhoneTheme.typography.body.copy(color = colors.textPrimary),
            )
        }
        if (groups.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp).testTag("mp-redpacket-no-group"),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.mp_redpacket_no_group),
                    style = MiniPhoneTheme.typography.caption,
                    color = colors.textSecondary,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = MiniPhoneTheme.dimens.pageMargin, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(groups, key = { it.id }) { group ->
                    Text(
                        text = group.name,
                        style = MiniPhoneTheme.typography.body.copy(color = colors.textPrimary),
                        modifier = Modifier.fillMaxWidth().clip(CircleShape)
                            .clickable { picked = group.id }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("mp-redpacket-group-${group.id}"),
                    )
                }
            }
        }
    }
}
