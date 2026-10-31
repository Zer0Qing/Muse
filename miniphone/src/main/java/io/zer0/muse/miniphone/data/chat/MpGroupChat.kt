package io.zer0.muse.miniphone.data.chat

import androidx.room.withTransaction
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.dao.MpMessageDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadMemberDao
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.data.db.entity.MpThreadMemberEntity
import java.util.UUID

/** 群名长度上限:再长顶栏就放不下了,超出的部分直接截掉。 */
internal const val GROUP_TITLE_MAX_LENGTH: Int = 20

/** 移完之后至少还要剩下的成员数:一个人都没有的群没人能开口。 */
internal const val MIN_GROUP_MEMBERS_AFTER_REMOVE: Int = 1

/**
 * 群名的写法:去掉首尾空白,超过 [GROUP_TITLE_MAX_LENGTH] 个字就截断。
 *
 * 全空时返回空串,由调用方当成「不许保存」—— 没有名字的群在列表里没法认。
 */
internal fun normalizeGroupTitle(raw: String): String = raw.trim().take(GROUP_TITLE_MAX_LENGTH)

/** 一个群至少要有两个角色:一个人不成群,那本来就该走私信。 */
internal const val MIN_GROUP_MEMBERS: Int = 2

/** 群名里名字之间的分隔符。 */
internal const val GROUP_TITLE_SEPARATOR: String = "、"

/** 群名里最多逐个列几个人名;超过这个数就收成「阿远、小满等 5 人」。 */
internal const val GROUP_TITLE_MAX_NAMES: Int = 3

/**
 * 群名:成员名字按选择顺序拼起来。
 *
 * 只拼角色名 —— 本机用户不是成员行,顶栏的「（N）」才是把「角色数 + 1」算进去的那个数,
 * 两套口径不要混在一起解释。
 * 人数多的时候不逐个列:超过 [GROUP_TITLE_MAX_NAMES] 个人就取前两个加「等 N 人」,
 * 这里的 N 是全部角色数(不含本机用户),与列表、建群页上选中的那几个人对得上。
 * 群名在建立那一刻定下来,不随后续成员变化重算。没有名字的成员直接跳过,
 * 不留下多余的分隔符;一个名字都没有时返回空串,由界面退化成「群聊」。
 */
internal fun groupTitleOf(memberNames: List<String>): String {
    val names = memberNames.map(String::trim).filter(String::isNotEmpty)
    if (names.isEmpty()) return ""
    if (names.size <= GROUP_TITLE_MAX_NAMES) return names.joinToString(GROUP_TITLE_SEPARATOR)
    return names.take(GROUP_TITLE_TAIL_NAMES).joinToString(GROUP_TITLE_SEPARATOR) + groupTitleTailOf(names.size)
}

/** 收口写法里的「等 N 人」——N 是全部角色数。 */
internal fun groupTitleTailOf(personCount: Int): String = "等 $personCount 人"

/** 收口时逐个列出来的名字个数。 */
private const val GROUP_TITLE_TAIL_NAMES: Int = 2

/** 「完成」键能不能按:选够 [MIN_GROUP_MEMBERS] 个角色才算数。 */
internal fun canCreateGroup(selectedCount: Int): Boolean = selectedCount >= MIN_GROUP_MEMBERS

/**
 * 建群。
 *
 * 抽成接口的理由与 [MpConversationActions] 一样:外壳 ViewModel 的测试不该为一次委托开一个 Room 库。
 */
interface MpGroupActions {
    /**
     * 建一个群会话并写入成员,返回新会话 id;选项不够两人时不建,返回 null。
     *
     * @param memberIds 成员角色 id,顺序就是群里的顺序
     * @param title 群名,由调用方按 [groupTitleOf] 拼好
     */
    suspend fun createGroup(memberIds: List<String>, title: String): String?

    /**
     * 改群名。空名不写,超长截断(见 [normalizeGroupTitle]);写入后这个群不再自动重算群名。
     *
     * @return 真的写进去了才为 true
     */
    suspend fun renameGroup(threadId: String, title: String): Boolean

    /**
     * 往群里加人。已经在群里的人自动跳过。
     *
     * @param autoTitle 群名还没被用户改过时要新拼的群名;已经改过就传 null,一个字不动
     * @return 真的加进去了才为 true
     */
    suspend fun addMembers(threadId: String, characterIds: List<String>, autoTitle: String?): Boolean

    /**
     * 把一个人移出群。只剩一个人时不允许移 —— 群里总得留一个能说话的角色。
     *
     * @param autoTitle 与 [addMembers] 同义:没改过名的群跟着成员重算,改过的保持不动
     * @return 真的移出去了才为 true
     */
    suspend fun removeMember(threadId: String, characterId: String, autoTitle: String?): Boolean

    /**
     * 解散群:成员行、这个会话的全部消息、会话本身一起删掉,同一个事务。
     *
     * @return 真的删了才为 true
     */
    suspend fun disbandGroup(threadId: String): Boolean
}

/**
 * 建群的写入:一条群会话 + 若干成员行,同一个事务。
 *
 * 两样东西必须一起出现:只有会话没有成员,群名对不上人;只有成员没有会话,列表里看不见。
 * 会话行的 characterId 落第一个成员 —— 群聊要找人开口时用的就是它。
 *
 * @param now 时间源,默认取系统时钟;测试用它钉死入群时间
 * @param newId 会话 id 生成器,默认 UUID
 */
class MpGroupChatStore(
    private val db: MiniPhoneDb,
    private val threadDao: MpThreadDao,
    private val memberDao: MpThreadMemberDao,
    private val messageDao: MpMessageDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : MpGroupActions {

    override suspend fun createGroup(memberIds: List<String>, title: String): String? {
        val members = memberIds.map(String::trim).filter(String::isNotEmpty).distinct()
        if (!canCreateGroup(members.size)) return null
        val threadId = newId()
        val createdAt = now()
        db.withTransaction {
            threadDao.upsert(
                MpThreadEntity(
                    id = threadId,
                    characterId = members.first(),
                    lastMessageAt = createdAt,
                    isGroup = true,
                    title = title,
                ),
            )
            // joinedAt 逐行加一:同一批入群的人按选择顺序排队,成员页的顺序因此稳定
            memberDao.upsertAll(
                members.mapIndexed { index, characterId ->
                    MpThreadMemberEntity(threadId = threadId, characterId = characterId, joinedAt = createdAt + index)
                },
            )
        }
        return threadId
    }

    override suspend fun renameGroup(threadId: String, title: String): Boolean {
        val normalized = normalizeGroupTitle(title)
        if (normalized.isEmpty()) return false
        if (groupThreadOrNull(threadId) == null) return false
        threadDao.updateTitle(threadId, normalized, customized = true)
        return true
    }

    override suspend fun addMembers(threadId: String, characterIds: List<String>, autoTitle: String?): Boolean {
        if (groupThreadOrNull(threadId) == null) return false
        val existingRows = memberDao.membersOf(threadId)
        val existing = existingRows.map { member -> member.characterId }.toSet()
        val fresh = characterIds.map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .filterNot { characterId -> characterId in existing }
        if (fresh.isEmpty()) return false
        // 新人排到最后:入群时间要比现有成员都晚,否则成员页的顺序会乱
        val joinedAt = maxOf(now(), (existingRows.maxOfOrNull { member -> member.joinedAt } ?: 0L) + 1)
        db.withTransaction {
            memberDao.upsertAll(
                fresh.mapIndexed { index, characterId ->
                    MpThreadMemberEntity(threadId = threadId, characterId = characterId, joinedAt = joinedAt + index)
                },
            )
            if (autoTitle != null) threadDao.updateTitle(threadId, autoTitle, customized = false)
        }
        return true
    }

    override suspend fun removeMember(threadId: String, characterId: String, autoTitle: String?): Boolean {
        val thread = groupThreadOrNull(threadId) ?: return false
        val members = memberDao.membersOf(threadId)
        // 移完至少还得留一个人:一个人都没有的群,没人能开口
        if (members.size <= MIN_GROUP_MEMBERS_AFTER_REMOVE) return false
        if (members.none { member -> member.characterId == characterId }) return false
        val remaining = members.filterNot { member -> member.characterId == characterId }
        db.withTransaction {
            memberDao.deleteMember(threadId, characterId)
            // 被移出的正好是「找他开口」的那个:换成剩下的第一个,群里才有回话的人
            if (thread.characterId == characterId) {
                threadDao.updateCharacter(threadId, remaining.first().characterId)
            }
            if (autoTitle != null) threadDao.updateTitle(threadId, autoTitle, customized = false)
        }
        return true
    }

    override suspend fun disbandGroup(threadId: String): Boolean {
        if (groupThreadOrNull(threadId) == null) return false
        db.withTransaction {
            messageDao.deleteInThread(threadId)
            memberDao.deleteInThread(threadId)
            threadDao.delete(threadId)
        }
        return true
    }

    /** 只有群会话能改、能加、能踢、能解散;私信走到这里一律当没这回事。 */
    private suspend fun groupThreadOrNull(threadId: String): MpThreadEntity? =
        threadDao.getThread(threadId)?.takeIf { thread -> thread.isGroup }
}
