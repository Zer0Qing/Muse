package io.zer0.muse.miniphone.ui.chat

import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.chat.MpGroupActions
import io.zer0.muse.miniphone.data.chat.groupTitleOf
import io.zer0.muse.miniphone.data.chat.normalizeGroupTitle
import io.zer0.muse.miniphone.data.db.dao.MpThreadDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadMemberDao
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.ui.shell.UNKNOWN_CHARACTER_NAME
import io.zer0.muse.miniphone.ui.launchGuarded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 群成员管理的四个写入口:改名、加人、移出、解散。
 *
 * 与发消息那条链并列,不混在一起:这里只改「群是谁的、叫什么」,不参与生成与发送。
 * 界面上的群名在写库成功之后才换 —— 写失败时界面不该先变一下再退回来。
 *
 * 「默认名」的判定就在这一层:群名没被用户改过(会话行上的 titleCustomized 为 0)时,
 * 加人/移人之后按「现在全部成员」重算;改过的群一个字都不动。
 *
 * @param characters 宿主给的角色名单;拼默认群名要拿名字
 */
// 日志里区分是哪个用户动作失败了（动作名写在调用处）。
private const val TAG_GROUP_ADMIN = "MiniPhoneGroupAdmin"

internal class GroupAdminController(
    private val threadId: String,
    private val threadDao: MpThreadDao,
    private val memberDao: MpThreadMemberDao,
    private val store: MpGroupActions,
    private val characters: () -> List<MpCharacter>,
    private val state: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope,
) {

    /** 改群名:空名不写,超长截断;写成功后界面上的群名立刻跟着换。 */
    fun renameGroup(title: String) {
        val normalized = normalizeGroupTitle(title)
        if (normalized.isEmpty()) return
        scope.launchGuarded(TAG_GROUP_ADMIN, "改群名") {
            if (!store.renameGroup(threadId, normalized)) return@launchGuarded
            state.update { it.copy(groupTitle = normalized) }
        }
    }

    /** 往群里加人:已经在群里的人自动跳过;默认名跟着新成员重算。 */
    fun addMembers(characterIds: List<String>) {
        val ids = characterIds.map(String::trim).filter(String::isNotEmpty).distinct()
        if (ids.isEmpty()) return
        scope.launchGuarded(TAG_GROUP_ADMIN, "往群里加人") {
            val thread = groupThread() ?: return@launchGuarded
            val existing = memberDao.membersOf(threadId).map { member -> member.characterId }
            val fresh = ids.filterNot { characterId -> characterId in existing }
            if (fresh.isEmpty()) return@launchGuarded
            val autoTitle = autoTitleOf(thread, existing + fresh)
            if (!store.addMembers(threadId, fresh, autoTitle)) return@launchGuarded
            if (autoTitle != null) state.update { it.copy(groupTitle = autoTitle) }
        }
    }

    /** 把一个人移出群:他说过的话留着;只剩一个人时写入层会拒绝。 */
    fun removeMember(characterId: String) {
        scope.launchGuarded(TAG_GROUP_ADMIN, "把一个人移出群") {
            val thread = groupThread() ?: return@launchGuarded
            val remaining = memberDao.membersOf(threadId)
                .map { member -> member.characterId }
                .filterNot { id -> id == characterId }
            val autoTitle = autoTitleOf(thread, remaining)
            if (!store.removeMember(threadId, characterId, autoTitle)) return@launchGuarded
            if (autoTitle != null) state.update { it.copy(groupTitle = autoTitle) }
        }
    }

    /** 解散:会话、成员、消息一起没。界面自己负责退出这一页。 */
    fun disbandGroup() {
        scope.launchGuarded(TAG_GROUP_ADMIN, "解散群") { store.disbandGroup(threadId) }
    }

    /** 私信走到这里一律不处理:这几个动作只对群有意义。 */
    private suspend fun groupThread(): MpThreadEntity? = threadDao.getThread(threadId)?.takeIf { thread -> thread.isGroup }

    /**
     * 群名没被手动改过时,按「现在的成员」拼一个;改过就返回 null(表示别动)。
     *
     * 名字取自宿主名单:角色被删了就退化成占位名,不因此把一个群变成无名群。
     */
    private fun autoTitleOf(thread: MpThreadEntity, memberIds: List<String>): String? {
        if (thread.titleCustomized) return null
        val known = characters()
        val names = memberIds.map { id ->
            known.firstOrNull { character -> character.id == id }?.name?.takeIf(String::isNotBlank)
                ?: UNKNOWN_CHARACTER_NAME
        }
        return groupTitleOf(names).takeIf(String::isNotEmpty)
    }
}
