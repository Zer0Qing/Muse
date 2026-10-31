package io.zer0.muse.miniphone.data.moment

import android.net.Uri
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMomentSource
import io.zer0.muse.miniphone.data.db.dao.MpMomentCommentDao
import io.zer0.muse.miniphone.data.db.dao.MpMomentDao
import io.zer0.muse.miniphone.data.db.dao.MpMomentLikeDao
import io.zer0.muse.miniphone.data.db.entity.MpMomentCommentEntity
import io.zer0.muse.miniphone.data.db.entity.MpMomentEntity
import io.zer0.muse.miniphone.data.db.entity.MpMomentLikeEntity
import io.zer0.muse.miniphone.data.media.MpImageStore
import java.util.UUID

/** 一条动态最多九张图:再多一屏也看不过来,九宫格是上限。 */
internal const val MOMENT_MAX_IMAGES: Int = 9

/** 本机用户在各处的统一 id:动态作者、点赞人、评论人共用它。 */
const val MP_LOCAL_ACTOR_ID: String = "local"

/**
 * 朋友圈的读写口。
 *
 * 发布、点赞、评论、删除四件事的落库细节都在这里,上层只描述「谁对哪条动态做了什么」。
 * 三张表之间没有外键约束(Room 未开 foreignKeys),所以删动态时由本类负责把
 * 它的点赞与评论一起清掉 —— 留孤儿行会让点赞数在新动态复用 id 时凭空多出来。
 *
 * @param now 时间源,默认系统时钟;测试用它钉死 `createdAt`
 * @param newId 主键生成器,默认 UUID;测试用它让动态/评论 id 可预期
 */
class MpMomentsRepository(
    private val momentDao: MpMomentDao,
    private val likeDao: MpMomentLikeDao,
    private val commentDao: MpMomentCommentDao,
    private val imageStore: MpImageStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    /**
     * 倒序取一页(新 → 旧)。
     *
     * 可见范围不在查询里过滤:这是本机自己的朋友圈,「仅自己可见」的动态在自己这一侧的
     * 列表里照常显示。以后真的要按读者裁剪,在这里加条件。
     */
    suspend fun page(limit: Int, offset: Int): List<MpMomentEntity> = momentDao.pageTimeline(limit, offset)

    /**
     * 「我的朋友圈」的一页:只要本机用户发的动态,新 → 旧。
     *
     * 作者条件是查询的一部分,不是拿整页回来再筛 —— 筛掉之后每页的有效条数会缩水,
     * 翻页游标(已加载条数)也跟着错位。
     */
    suspend fun pageMine(limit: Int, offset: Int): List<MpMomentEntity> = momentDao.pageTimelineByAuthor(MP_LOCAL_ACTOR_ID, limit, offset)

    /** 某条动态的点赞人,按点赞时间先后排。 */
    suspend fun likesOf(momentId: String): List<MpMomentLikeEntity> = likeDao.likesOf(momentId)

    /** 某条动态的评论,按时间正序;层级由上层按 `parentId` 组装。 */
    suspend fun commentsOf(momentId: String): List<MpMomentCommentEntity> = commentDao.commentsOf(momentId)

    /** 一页动态的点赞:一次查询后按 `momentId` 归组,没被赞的动态不出现在结果里。 */
    suspend fun likesByMomentIds(momentIds: List<String>): Map<String, List<MpMomentLikeEntity>> {
        if (momentIds.isEmpty()) return emptyMap()
        return likeDao.likesOfMomentIds(momentIds).groupBy { like -> like.momentId }
    }

    /** 一页动态的评论:一次查询后按 `momentId` 归组。 */
    suspend fun commentsByMomentIds(momentIds: List<String>): Map<String, List<MpMomentCommentEntity>> {
        if (momentIds.isEmpty()) return emptyMap()
        return commentDao.commentsOfMomentIds(momentIds).groupBy { comment -> comment.momentId }
    }

    /**
     * 发一条自己的动态。
     *
     * 选中的图先复制进模块私有目录,库里只存本地路径;某张读不到就跳过这一张,
     * 不让一张坏图带走整条动态。文字与图片都空时不落库,返回 null。
     *
     * [visibleTo] 留空即所有人可见;只给自己看时写本机用户的 id(逗号分隔可放多个 id,
     * 本阶段只会有自己一个)。本阶段只写不读,时间线照常把这条列出来。
     */
    suspend fun publish(content: String, imageUris: List<Uri>, authorName: String, visibleTo: String = ""): MpMomentEntity? {
        val text = content.trim()
        val images = imageUris.take(MOMENT_MAX_IMAGES).mapNotNull { uri -> imageStore.import(uri) }
        if (text.isEmpty() && images.isEmpty()) return null
        return insert(
            MpMomentEntity(
                id = newId(),
                authorId = MP_LOCAL_ACTOR_ID,
                authorName = authorName.ifBlank { DEFAULT_SELF_NAME },
                content = text,
                imagesJson = encodeMomentImages(images),
                visibleToJson = visibleTo.trim(),
                createdAt = now(),
                source = MpMomentSource.USER,
            ),
        )
    }

    /**
     * 落一条角色发的动态(宿主生成成功后调用)。
     *
     * 正文为空的生成结果不落库:一条什么也没说的动态不值得占时间线的一格。
     */
    suspend fun insertFromCharacter(character: MpCharacter, content: String): MpMomentEntity? {
        val text = content.trim()
        if (text.isEmpty()) return null
        return insert(
            MpMomentEntity(
                id = newId(),
                authorId = character.id,
                authorName = character.name.ifBlank { DEFAULT_CHARACTER_NAME },
                content = text,
                imagesJson = encodeMomentImages(emptyList()),
                visibleToJson = "",
                createdAt = now(),
                source = MpMomentSource.AI,
            ),
        )
    }

    /**
     * 点赞/取消点赞,返回操作之后「这个人是否赞了」。
     *
     * 复合主键 `(momentId, likerType, likerId)` 保证同一个人对同一条动态只有一行,
     * 所以状态只看存在与否:在就删、不在就插,连点两次必然回到未赞。
     */
    suspend fun toggleLike(momentId: String, likerType: MpActorType, likerId: String, likerName: String): Boolean {
        if (likeDao.isLiked(momentId, likerType, likerId)) {
            likeDao.delete(momentId, likerType, likerId)
            return false
        }
        likeDao.insert(
            MpMomentLikeEntity(
                momentId = momentId,
                likerType = likerType,
                likerId = likerId,
                likerName = likerName.ifBlank { DEFAULT_SELF_NAME },
                createdAt = now(),
            ),
        )
        return true
    }

    /**
     * 评论一条动态,或回复其中一条评论。
     *
     * [parentId] 为空表示直接评论动态;不为空表示回复那条评论。空白内容不落库。
     */
    suspend fun addComment(
        momentId: String,
        parentId: String?,
        senderType: MpActorType,
        senderId: String,
        senderName: String,
        content: String,
    ): MpMomentCommentEntity? {
        val text = content.trim()
        if (text.isEmpty()) return null
        val comment = MpMomentCommentEntity(
            id = newId(),
            momentId = momentId,
            parentId = parentId?.takeIf(String::isNotBlank),
            senderType = senderType,
            senderId = senderId,
            senderName = senderName.ifBlank { DEFAULT_SELF_NAME },
            content = text,
            createdAt = now(),
        )
        commentDao.insert(comment)
        return comment
    }

    /** 删一条动态,连它的点赞与评论一起清掉。 */
    suspend fun deleteMoment(momentId: String) {
        likeDao.deleteAllOf(momentId)
        commentDao.deleteAllOf(momentId)
        momentDao.delete(momentId)
    }

    /**
     * 删一条自己写的评论,它下面的回复一起删。
     *
     * 别人的评论在这里也会被拦下:界面不给入口,写入层再守一次 —— 调用方传错一个 id,
     * 不该能把别人说的话抹掉。
     */
    suspend fun deleteComment(momentId: String, commentId: String) {
        val comments = commentDao.commentsOf(momentId)
        val target = comments.firstOrNull { comment -> comment.id == commentId } ?: return
        if (target.senderType != MpActorType.USER || target.senderId != MP_LOCAL_ACTOR_ID) return
        commentDao.deleteByIds(commentSubtreeIds(commentId, comments))
    }

    private suspend fun insert(moment: MpMomentEntity): MpMomentEntity {
        momentDao.insert(moment)
        return moment
    }

    private companion object {
        const val DEFAULT_SELF_NAME: String = "我"
        const val DEFAULT_CHARACTER_NAME: String = "角色"
    }
}

/**
 * 一条评论及它下面所有回复的 id(含自己),按从父到子的顺序。
 *
 * 评论层数不固定([parentId] 可以一直指向上一层),所以用队列一层层往下收;
 * 数据里真出现环(父亲指向孩子)时靠 [visited] 收手,不会在这里转圈。
 */
internal fun commentSubtreeIds(commentId: String, comments: List<MpMomentCommentEntity>): List<String> {
    val childrenByParent = comments.filter { it.parentId != null }.groupBy { it.parentId.orEmpty() }
    val visited = mutableSetOf<String>()
    val ordered = mutableListOf<String>()
    val pending = ArrayDeque(listOf(commentId))
    while (pending.isNotEmpty()) {
        val id = pending.removeFirst()
        if (!visited.add(id)) continue
        ordered += id
        childrenByParent[id].orEmpty().forEach { child -> pending.addLast(child.id) }
    }
    return ordered
}
