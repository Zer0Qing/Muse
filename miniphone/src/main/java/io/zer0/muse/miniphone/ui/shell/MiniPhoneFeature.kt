package io.zer0.muse.miniphone.ui.shell

import androidx.compose.ui.graphics.vector.ImageVector
import io.zer0.muse.miniphone.R
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons

/**
 * 发现页里的功能模块。
 *
 * 一功能一项：新增模块时只需在 [MiniPhoneFeatureCatalog.entries] 登记一项，
 * 由发现页渲染入口、外壳按 id 分派页面，不必再逐个加参数与布尔状态。
 * id 是稳定标识，进程重建后靠它还原当前打开的模块；改文案不动 id。
 */
enum class MiniPhoneFeature(
    val id: String,
    val labelRes: Int,
    val tag: String,
) {
    MOMENTS("moments", R.string.mp_discover_moments, MiniPhoneTags.MOMENTS_ENTRY),
    ROOM("room", R.string.mp_room_entry, MiniPhoneTags.ROOM_ENTRY),
    INNER_VOICE_HISTORY("inner_voice_history", R.string.mp_discover_inner_voice, "mp-inner-voice-history-entry"),
    MEMO("memo", R.string.mp_discover_memo, MiniPhoneTags.MEMO_ENTRY),
    CALLS("calls", R.string.mp_discover_calls, MiniPhoneTags.CALLS_ENTRY),
    CHECKUP("checkup", R.string.mp_discover_checkup, MiniPhoneTags.CHECKUP_ENTRY),
    FORUM("forum", R.string.mp_discover_forum, MiniPhoneTags.FORUM_ENTRY),
    RELATION("relation", R.string.mp_discover_relation, MiniPhoneTags.RELATION_ENTRY),
    ROAM("roam", R.string.mp_discover_roam, MiniPhoneTags.ROAM_ENTRY),
    ROLEPLAY("roleplay", R.string.mp_discover_roleplay, MiniPhoneTags.ROLEPLAY_ENTRY),
    WARDROBE("wardrobe", R.string.mp_discover_wardrobe, MiniPhoneTags.WARDROBE_ENTRY),
    RED_PACKET("red_packet", R.string.mp_discover_redpacket, MiniPhoneTags.RED_PACKET_ENTRY),
    HOLIDAY("holiday", R.string.mp_discover_holiday, MiniPhoneTags.HOLIDAY_ENTRY),
    RINGTONE("ringtone", R.string.mp_discover_ringtone, MiniPhoneTags.RINGTONE_ENTRY),
}

object MiniPhoneFeatureCatalog {
    val entries: List<MiniPhoneFeature> = MiniPhoneFeature.entries.toList()

    fun fromId(id: String): MiniPhoneFeature? = entries.firstOrNull { it.id == id }

    // 每个入口给一个能对应其语义的图标；此前除朋友圈外全部用指南针，
    // 14 个格子看起来一样，用户无法凭图标分辨功能。
    // 红包/节日暂时没有语义贴切的现成图标，保留 Discover 占位，不用错图标误导。
    fun iconOf(feature: MiniPhoneFeature): ImageVector = when (feature) {
        MiniPhoneFeature.MOMENTS -> MiniPhoneIcons.Moments
        MiniPhoneFeature.ROOM -> MiniPhoneIcons.Home
        MiniPhoneFeature.INNER_VOICE_HISTORY -> MiniPhoneIcons.Heart
        MiniPhoneFeature.MEMO -> MiniPhoneIcons.Pen
        MiniPhoneFeature.CALLS -> MiniPhoneIcons.Call
        MiniPhoneFeature.CHECKUP -> MiniPhoneIcons.Check
        MiniPhoneFeature.FORUM -> MiniPhoneIcons.Message
        MiniPhoneFeature.RELATION -> MiniPhoneIcons.Contacts
        MiniPhoneFeature.ROAM -> MiniPhoneIcons.Discover
        MiniPhoneFeature.ROLEPLAY -> MiniPhoneIcons.Play
        MiniPhoneFeature.WARDROBE -> MiniPhoneIcons.Sticker
        MiniPhoneFeature.RED_PACKET -> MiniPhoneIcons.Discover
        MiniPhoneFeature.HOLIDAY -> MiniPhoneIcons.Discover
        MiniPhoneFeature.RINGTONE -> MiniPhoneIcons.Mic
    }
}
