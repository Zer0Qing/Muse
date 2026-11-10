package io.zer0.muse.miniphone.ui.misc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.misc.MpRedPacketStore
import io.zer0.muse.miniphone.ui.launchGuarded
import org.koin.compose.koinInject

private const val TAG_REDPACKET = "MiniPhoneRedPacket"

/** 本机用户在红包里的固定身份：发送者与领取者都用它，金额只是玩法文案，不接任何真实账务。 */
private const val LOCAL_USER_ID = "me"

/**
 * 群红包的装配点：绑定一个会话 [threadId]，从容器取库，把纯渲染页面接上发/领动作。
 *
 * 发与领都走 [launchGuarded]；同一人不重复领、金额仅展示由门面保证。
 */
@Composable
internal fun MiniPhoneRedPacketFeature(threadId: String, onBack: () -> Unit) {
    val db = koinInject<MiniPhoneDb>()
    val store = remember(db) { MpRedPacketStore(db) }
    val scope = rememberCoroutineScope()
    MiniPhoneRedPacketScreen(
        packets = store.observeByThread(threadId),
        onSend = { amount, note ->
            scope.launchGuarded(TAG_REDPACKET, "发红包") { store.send(threadId, LOCAL_USER_ID, amount, note) }
        },
        onClaim = { id -> scope.launchGuarded(TAG_REDPACKET, "领红包") { store.claim(id, LOCAL_USER_ID) } },
        onBack = onBack,
    )
}
