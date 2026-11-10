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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.miniphone.R
import io.zer0.muse.miniphone.data.call.MpCallPhase
import io.zer0.muse.miniphone.data.call.MpCallSentinel
import io.zer0.muse.miniphone.data.call.MpCallSessionUi
import io.zer0.muse.miniphone.data.call.MpCallTurnUi
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.flow.Flow

/**
 * 通话进行中的页面：阶段、计时、谁说过的每一句，以及挂断。
 *
 * 纯渲染 —— 状态与动作都由 Flow + 回调驱动，因此这一页在测试里不需要真时间、也不需要真音频。
 * 文案全部走资源：阶段与降级原因在数据层是记号，不在数据层写成品中文。
 */
@Composable
internal fun MiniPhoneCallScreen(
    session: Flow<MpCallSessionUi?>,
    elapsedMs: Flow<Long>,
    onSay: (String) -> Unit,
    onHangUp: () -> Unit,
    onPlayVoice: (String) -> Unit,
    onBack: () -> Unit,
) {
    val current by session.collectAsStateWithLifecycle(initialValue = null)
    val minutes by elapsedMs.collectAsStateWithLifecycle(initialValue = 0L)
    var draft by remember { mutableStateOf("") }
    val colors = MiniPhoneTheme.colors
    val sessionValue = current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(MiniPhoneCallTags.LIVE_PAGE)
            .background(colors.background),
    ) {
        if (sessionValue == null) {
            Spacer(Modifier.size(24.dp))
            Text(
                text = stringResource(R.string.mp_call_none),
                style = MiniPhoneTheme.typography.caption,
                color = colors.textSecondary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.mp_call_back),
                style = MiniPhoneTheme.typography.body.copy(color = colors.accent),
                modifier = Modifier.fillMaxWidth().clip(CircleShape).clickable(onClick = onBack)
                    .padding(horizontal = 12.dp, vertical = 10.dp).testTag(MiniPhoneCallTags.BACK),
                textAlign = TextAlign.Center,
            )
            return
        }
        CallPhaseHeader(sessionValue, minutes)
        CallNote(sessionValue)
        if (sessionValue.turns.isEmpty()) {
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = MiniPhoneTheme.dimens.pageMargin, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(sessionValue.turns) { index, turn ->
                    CallTurnRow(turn = turn, index = index, onPlayVoice = onPlayVoice)
                }
            }
        }
        CallSayRow(
            draft = draft,
            onDraftChange = { draft = it },
            onSend = {
                val text = draft.trim()
                if (text.isNotEmpty()) {
                    onSay(text)
                    draft = ""
                }
            },
            enabled = sessionValue.phase == MpCallPhase.CONNECTED,
        )
        Text(
            text = stringResource(R.string.mp_call_hangup),
            style = MiniPhoneTheme.typography.body.copy(color = colors.onAccent),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MiniPhoneTheme.dimens.pageMargin, vertical = 16.dp)
                .clip(CircleShape)
                .background(colors.accent)
                .clickable(onClick = onHangUp)
                .padding(vertical = 12.dp)
                .testTag(MiniPhoneCallTags.LIVE_HANGUP),
        )
    }
}

@Composable
private fun CallPhaseHeader(session: MpCallSessionUi, elapsedMs: Long) {
    val colors = MiniPhoneTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(callPhaseLabelRes(session)),
            style = MiniPhoneTheme.typography.body.copy(color = colors.textPrimary),
            modifier = Modifier.testTag(MiniPhoneCallTags.LIVE_PHASE),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = stringResource(R.string.mp_call_duration, formatSeconds(elapsedMs)),
            style = MiniPhoneTheme.typography.caption.copy(color = colors.textSecondary),
            modifier = Modifier.testTag(MiniPhoneCallTags.LIVE_TIMER),
        )
    }
}

/** 降级与失败的原因只有记号能对上时才出话；未知记号留空，不拿中文硬猜。 */
@Composable
private fun CallNote(session: MpCallSessionUi) {
    // 「未接通」由阶段标题说，这里不再重复一句：两处都说的话，挂断的界面上会并排出现两行同样的字。
    val label = when (MpCallSentinel.of(session.rejectReason)) {
        MpCallSentinel.CAMERA_DENIED -> stringResource(R.string.mp_call_camera_denied)
        MpCallSentinel.DIAL_FAILED -> stringResource(R.string.mp_call_dial_failed)
        else -> null
    }
    if (label == null) return
    Text(
        text = label,
        style = MiniPhoneTheme.typography.caption,
        color = MiniPhoneTheme.colors.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(MiniPhoneCallTags.LIVE_NOTE),
    )
}

@Composable
private fun CallTurnRow(turn: MpCallTurnUi, index: Int, onPlayVoice: (String) -> Unit) {
    val colors = MiniPhoneTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().testTag(MiniPhoneCallTags.turn(index)),
    ) {
        Text(
            text = stringResource(if (turn.speaker == MpActorType.USER) R.string.mp_call_mine else R.string.mp_call_peer),
            style = MiniPhoneTheme.typography.caption.copy(color = colors.accent),
        )
        Text(
            text = turn.text,
            style = MiniPhoneTheme.typography.body.copy(color = colors.textPrimary),
            modifier = Modifier.padding(top = 2.dp),
        )
        val uri = turn.voiceUri
        if (uri != null) {
            Text(
                text = stringResource(R.string.mp_call_play_voice),
                style = MiniPhoneTheme.typography.caption.copy(color = colors.accent),
                modifier = Modifier.clip(CircleShape).clickable { onPlayVoice(uri) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * 通话里说一句：接通后才让输入，未接通时输入置灰。
 *
 * 「还没接通就能说话」会让用户以为自己已经连线，所以这里按阶段禁用，而不是只靠门面丢掉内容。
 */
@Composable
private fun CallSayRow(draft: String, onDraftChange: (String) -> Unit, onSend: () -> Unit, enabled: Boolean) {
    val colors = MiniPhoneTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MiniPhoneTheme.dimens.pageMargin, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text(stringResource(R.string.mp_call_say_hint)) },
            singleLine = true,
            enabled = enabled,
            keyboardActions = KeyboardActions(onSend = { if (enabled) onSend() }),
            modifier = Modifier.weight(1f).testTag(MiniPhoneCallTags.LIVE_SAY_INPUT),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.mp_call_say_send),
            style = MiniPhoneTheme.typography.body.copy(color = if (enabled) colors.accent else colors.textSecondary),
            modifier = Modifier.clip(CircleShape).clickable(enabled = enabled, onClick = onSend)
                .padding(horizontal = 10.dp, vertical = 8.dp).testTag(MiniPhoneCallTags.LIVE_SAY_SEND),
        )
    }
}

private fun callPhaseLabelRes(session: MpCallSessionUi): Int = when {
    session.phase == MpCallPhase.DIALING -> R.string.mp_call_dialing
    session.phase == MpCallPhase.RINGING -> R.string.mp_call_ringing
    session.phase == MpCallPhase.CONNECTED -> R.string.mp_call_connected
    session.phase == MpCallPhase.FAILED -> R.string.mp_call_failed
    session.noAnswer -> R.string.mp_call_no_answer
    else -> R.string.mp_call_ended
}

/** 秒数显示成 `mm:ss`；测试与界面都只认这一个格式。 */
private fun formatSeconds(elapsedMs: Long): String {
    val total = (elapsedMs / 1000L).coerceAtLeast(0L)
    return "%02d:%02d".format(total / 60L, total % 60L)
}
