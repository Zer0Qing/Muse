package io.zer0.muse.miniphone.ui.shell

import androidx.compose.runtime.Immutable
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity

/**
 * 外壳要展示的全部状态。
 *
 * 界面只读这一份数据,不碰 DAO / 宿主接口 —— 谁去查库、怎么合并,是 ViewModel 的事。
 */
@Immutable
data class ShellUiState(
    val threads: List<MiniPhoneThreadUi> = emptyList(),
    val contactSections: List<MiniPhoneContactSection> = emptyList(),
    val profile: MpProfile = MpProfile(nickname = ""),
    /** 「自动发朋友圈」开关:关着时后台 Worker 整轮跳过。默认开。 */
    val autoMomentEnabled: Boolean = true,
    /** 「角色主动私信」开关:关着时后台 Worker 整轮跳过。默认关。 */
    val chatProactiveEnabled: Boolean = false,
    /** 宿主应用的版本名,由 [io.zer0.muse.miniphone.api.MiniPhoneHost] 提供;空串表示宿主没报。 */
    val appVersionName: String = "",
)

/**
 * 会话列表的一行。
 *
 * [lastMessageAt] 保留原始时间戳,展示成什么文本由界面按当前时间算,
 * 这样状态本身与「现在几点」无关,也不会因为跨分钟而整表重建。
 * [isGroup] 只用于区分群里与私信。
 *
 * 三份"没有名字"的情况在这里一律留空([name]/[preview] 不填占位词),
 * 由界面在渲染时才决定那句提示——界面语言随时可切,词不能提前烘进状态里。
 * [isCharacterMissing] 记下"私信对应对手已不在名单"这一事实,与"名字恰好为空"区分开。
 */
@Immutable
data class MiniPhoneThreadUi(
    val id: String,
    val name: String,
    val preview: String,
    val lastMessageAt: Long,
    val unreadCount: Int,
    val pinned: Boolean,
    val isGroup: Boolean = false,
    val isCharacterMissing: Boolean = false,
)

/** 通讯录的一组:首字母 + 该组的角色。 */
@Immutable
data class MiniPhoneContactSection(
    val key: String,
    val contacts: List<MpCharacter>,
)

/**
 * 非拉丁首字母归到这一组。
 *
 * 中文名在拿到拼音之前无法可靠映射到拉丁字母,统一落到 `#`,排在字母组之后,
 * 比按笔画或编码乱猜更稳定。
 */
internal const val OTHER_SECTION_KEY: String = "#"

/** 取名字的分组键:拉丁首字母大写,其余一律 `#`。 */
internal fun sectionKeyOf(name: String): String {
    val first = name.trim().firstOrNull() ?: return OTHER_SECTION_KEY
    val upper = first.uppercaseChar()
    return if (upper in 'A'..'Z') upper.toString() else OTHER_SECTION_KEY
}

/** 把角色按首字母分组:字母组按 A→Z 排,`#` 收尾。 */
internal fun contactSectionsOf(characters: List<MpCharacter>): List<MiniPhoneContactSection> {
    val grouped = characters
        .filter { it.name.isNotBlank() }
        .groupBy { sectionKeyOf(it.name) }
    val byKey = compareBy<Map.Entry<String, List<MpCharacter>>> { it.key == OTHER_SECTION_KEY }.thenBy { it.key }
    return grouped.entries
        .sortedWith(byKey)
        .map { entry -> MiniPhoneContactSection(key = entry.key, contacts = entry.value.sortedBy { it.name }) }
}

/**
 * 合并会话、角色、资料三份数据。
 *
 * 会话的排序完全交给 DAO(置顶在前、其余按最后消息倒序),这里只做「角色 id → 名字」的补全:
 * 角色已被删除的会话仍然列出,名字退化成一句提示,而不是从列表里消失。
 * 群会话不走角色名:它是[title]自己那份群名。群里与私信在同一张列表里怎么分开,
 * 交给界面按语言加标记——这里只把事实(是不是群、名字是否为空、人还在不在名单)写进状态。
 */
internal fun assembleShellState(threads: List<MpThreadEntity>, characters: List<MpCharacter>, profile: MpProfile): ShellUiState {
    val namesById = characters.associateBy { it.id }
    return ShellUiState(
        threads = threads.map { thread ->
            val characterName = if (thread.isGroup) {
                null
            } else {
                namesById[thread.characterId]?.name?.takeIf { it.isNotBlank() }
            }
            MiniPhoneThreadUi(
                id = thread.id,
                name = if (thread.isGroup) thread.title else characterName.orEmpty(),
                preview = thread.lastMessagePreview.orEmpty(),
                lastMessageAt = thread.lastMessageAt,
                unreadCount = thread.unreadCount.coerceAtLeast(0),
                pinned = thread.pinned,
                isGroup = thread.isGroup,
                isCharacterMissing = !thread.isGroup && characterName == null,
            )
        },
        contactSections = contactSectionsOf(characters),
        profile = profile,
    )
}
