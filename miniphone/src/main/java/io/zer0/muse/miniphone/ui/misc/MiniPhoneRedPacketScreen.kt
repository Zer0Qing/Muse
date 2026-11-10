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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.OutlinedTextField
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.miniphone.R
import io.zer0.muse.miniphone.data.db.entity.MpRedPacketEntity
import io.zer0.muse.miniphone.data.misc.MpRedPacketStore
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.flow.Flow

/**
 * 群红包页：填金额与祝福语就能发一个红包，列表里能领取、看到已领人数。
 *
 * 纯渲染页面，状态与动作都由 Flow + 回调驱动，不碰 Room。
 * 金额只是角色扮演的展示文案 —— 页面顶部就把这句话讲明白，不让用户误以为在动真钱。
 * 「领取」只负责把动作交出去，同一人不重复领由门面保证。
 */
@Composable
internal fun MiniPhoneRedPacketScreen(
    packets: Flow<List<MpRedPacketEntity>>,
    onSend: (amount: String, note: String) -> Unit,
    onClaim: (id: String) -> Unit,
    onBack: () -> Unit,
) {
    val colors = MiniPhoneTheme.colors
    val rows by packets.collectAsStateWithLifecycle(emptyList())
    var amountDraft by remember { mutableStateOf("") }
    var noteDraft by remember { mutableStateOf("") }
    val canSend = amountDraft.isNotBlank()

    Column(modifier = Modifier.fillMaxSize().testTag(MiniPhoneMiscTags.RedPacket.PAGE).background(colors.background)) {
        MiscHeader(stringResource(R.string.mp_redpacket_title), MiniPhoneMiscTags.RedPacket.BACK, onBack)
        Text(
            text = stringResource(R.string.mp_redpacket_rp_hint),
            style = MiniPhoneTheme.typography.caption.copy(color = colors.textSecondary),
            modifier = Modifier.padding(horizontal = MiniPhoneTheme.dimens.pageMargin),
        )
        Spacer(Modifier.size(6.dp))
        RedPacketSendPanel(amountDraft, noteDraft, canSend, onSend = { amount, note ->
            amountDraft = ""
            noteDraft = ""
            onSend(amount, note)
        }, onAmountChange = { amountDraft = it }, onNoteChange = { noteDraft = it })
        if (rows.isEmpty()) {
            MiscEmpty(stringResource(R.string.mp_redpacket_empty), MiniPhoneMiscTags.RedPacket.EMPTY)
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = MiniPhoneTheme.dimens.pageMargin, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { it.id }) { packet ->
                    RedPacketRow(packet, onClaim = { onClaim(packet.id) })
                }
            }
        }
    }
}

@Composable
private fun RedPacketSendPanel(
    amount: String,
    note: String,
    canSend: Boolean,
    onSend: (amount: String, note: String) -> Unit,
    onAmountChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
) {
    val colors = MiniPhoneTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = MiniPhoneTheme.dimens.pageMargin, vertical = 4.dp)) {
        OutlinedTextField(
            value = amount,
            onValueChange = onAmountChange,
            placeholder = { Text(stringResource(R.string.mp_redpacket_amount_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(MiniPhoneMiscTags.RedPacket.AMOUNT_INPUT),
        )
        Spacer(Modifier.size(6.dp))
        OutlinedTextField(
            value = note,
            onValueChange = onNoteChange,
            placeholder = { Text(stringResource(R.string.mp_redpacket_note_hint)) },
            singleLine = true,
            keyboardActions = KeyboardActions(onDone = { if (canSend) onSend(amount, note) }),
            modifier = Modifier.fillMaxWidth().testTag(MiniPhoneMiscTags.RedPacket.NOTE_INPUT),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = stringResource(R.string.mp_redpacket_send),
            style = MiniPhoneTheme.typography.body.copy(color = if (canSend) colors.accent else colors.textSecondary),
            modifier = Modifier.clip(CircleShape).clickable(enabled = canSend) { onSend(amount, note) }
                .padding(horizontal = 12.dp, vertical = 8.dp).testTag(MiniPhoneMiscTags.RedPacket.SEND),
        )
    }
}

@Composable
private fun RedPacketRow(packet: MpRedPacketEntity, onClaim: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    val claimers = MpRedPacketStore.decodeClaimers(packet.claimedByJson)
    Column(
        modifier = Modifier.fillMaxWidth().testTag(MiniPhoneMiscTags.RedPacket.packet(packet.id))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = packet.amount, style = typography.title.copy(color = colors.accent), modifier = Modifier.weight(1f))
            Text(text = redPacketStateLabel(packet.state), style = typography.caption.copy(color = colors.textSecondary))
        }
        if (packet.note.isNotBlank()) {
            Text(
                text = packet.note,
                style = typography.caption.copy(color = colors.textPrimary),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text(
                text = stringResource(R.string.mp_redpacket_claim_count, claimers.size),
                style = typography.caption.copy(color = colors.textSecondary),
                modifier = Modifier.weight(1f),
            )
            MiscAction(
                label = stringResource(R.string.mp_redpacket_claim),
                tag = MiniPhoneMiscTags.RedPacket.claim(packet.id),
                enabled = packet.state == MpRedPacketStore.STATE_OPEN,
            ) { onClaim() }
        }
    }
}

@Composable
private fun redPacketStateLabel(state: String): String = when (state) {
    MpRedPacketStore.STATE_CLAIMED -> stringResource(R.string.mp_redpacket_state_claimed)
    MpRedPacketStore.STATE_EXPIRED -> stringResource(R.string.mp_redpacket_state_expired)
    else -> stringResource(R.string.mp_redpacket_state_open)
}
