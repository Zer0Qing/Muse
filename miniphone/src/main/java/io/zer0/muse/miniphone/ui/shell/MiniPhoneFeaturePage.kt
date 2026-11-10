package io.zer0.muse.miniphone.ui.shell

import androidx.compose.runtime.Composable
import io.zer0.muse.miniphone.data.call.MpCallStore
import io.zer0.muse.miniphone.data.chat.MpInnerVoiceStore
import io.zer0.muse.miniphone.data.chat.MpMemoStore
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.room.MpRoomStore
import io.zer0.muse.miniphone.ui.call.MiniPhoneCallHistoryScreen
import io.zer0.muse.miniphone.ui.checkup.MiniPhoneCheckupFeature
import io.zer0.muse.miniphone.ui.forum.MiniPhoneForumFeature
import io.zer0.muse.miniphone.ui.innervoice.MiniPhoneInnerVoiceHistoryScreen
import io.zer0.muse.miniphone.ui.memo.MiniPhoneMemoScreen
import io.zer0.muse.miniphone.ui.misc.MiniPhoneHolidayFeature
import io.zer0.muse.miniphone.ui.misc.MiniPhoneRedPacketEntry
import io.zer0.muse.miniphone.ui.misc.MiniPhoneRingtoneFeature
import io.zer0.muse.miniphone.ui.misc.MiniPhoneWardrobeFeature
import io.zer0.muse.miniphone.ui.relation.MiniPhoneRelationFeature
import io.zer0.muse.miniphone.ui.roam.MiniPhoneRoamFeature
import io.zer0.muse.miniphone.ui.roleplay.MiniPhoneRoleplayFeature
import io.zer0.muse.miniphone.ui.room.RoomPage
import org.koin.compose.koinInject

/**
 * 发现页模块的分派表：一个 [MiniPhoneFeature] 对应一整页内容。
 *
 * 从外壳抽出来，是为了让「加一个模块」只改注册表和这里的一个分支，
 * 外壳本身不用跟着变长。store 在真正进页时才从容器派生，不打开这页就不依赖容器已启动。
 */
@Composable
internal fun MiniPhoneFeaturePage(
    feature: MiniPhoneFeature,
    state: ShellUiState,
    roomStore: MpRoomStore,
    momentsOnlyMine: Boolean,
    momentsContent: @Composable (onlyMine: Boolean, onBack: () -> Unit) -> Unit,
    onBack: () -> Unit,
    /** 从模块里发起私信：已有会话就返回它，没有就地开一条。没接就什么也不做。 */
    onOpenCharacterChat: suspend (String) -> String? = { null },
) {
    val namesById = state.contactSections
        .flatMap { it.contacts }
        .associate { it.id to it.name }
    when (feature) {
        MiniPhoneFeature.MOMENTS -> momentsContent(momentsOnlyMine, onBack)
        MiniPhoneFeature.ROOM -> RoomPage(
            store = roomStore,
            occupantNames = namesById,
            onBack = onBack,
        )
        MiniPhoneFeature.INNER_VOICE_HISTORY -> MiniPhoneInnerVoiceHistoryScreen(
            store = MpInnerVoiceStore(koinInject<MiniPhoneDb>().innerVoiceDao()),
            namesById = namesById,
            onBack = onBack,
        )
        MiniPhoneFeature.MEMO -> {
            val memoStore = MpMemoStore(koinInject<MiniPhoneDb>().memoDao())
            MiniPhoneMemoScreen(
                memoFlow = memoStore.observeAll(),
                onSave = { text ->
                    val now = System.currentTimeMillis()
                    memoStore.save(java.util.UUID.randomUUID().toString(), "", text, now, now)
                },
                onDelete = { id -> memoStore.delete(id) },
                onBack = onBack,
            )
        }
        MiniPhoneFeature.CHECKUP -> MiniPhoneCheckupFeature(
            characters = state.contactSections.flatMap { it.contacts },
            onBack = onBack,
        )
        MiniPhoneFeature.FORUM -> MiniPhoneForumFeature(
            characters = state.contactSections.flatMap { it.contacts },
            onOpenCharacterChat = onOpenCharacterChat,
            onBack = onBack,
        )
        MiniPhoneFeature.RELATION -> MiniPhoneRelationFeature(
            characters = state.contactSections.flatMap { it.contacts },
            onBack = onBack,
        )
        MiniPhoneFeature.ROAM -> MiniPhoneRoamFeature(
            characters = state.contactSections.flatMap { it.contacts },
            onBack = onBack,
        )
        MiniPhoneFeature.ROLEPLAY -> MiniPhoneRoleplayFeature(
            characters = state.contactSections.flatMap { it.contacts },
            onBack = onBack,
        )
        MiniPhoneFeature.WARDROBE -> MiniPhoneWardrobeFeature(
            characters = state.contactSections.flatMap { it.contacts },
            onBack = onBack,
        )
        MiniPhoneFeature.RED_PACKET -> MiniPhoneRedPacketEntry(threads = state.threads, onBack = onBack)
        MiniPhoneFeature.HOLIDAY -> MiniPhoneHolidayFeature(onBack = onBack)
        MiniPhoneFeature.RINGTONE -> MiniPhoneRingtoneFeature(
            characters = state.contactSections.flatMap { it.contacts },
            onBack = onBack,
        )
        MiniPhoneFeature.CALLS -> MiniPhoneCallHistoryScreen(
            history = MpCallStore(koinInject<MiniPhoneDb>().callSessionDao()).observeHistory(200),
            namesById = namesById,
            onBack = onBack,
        )
    }
}
