package io.zer0.muse.miniphone.ui.moments

import androidx.compose.runtime.Immutable
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMomentSource
import io.zer0.muse.miniphone.data.db.entity.MpMomentCommentEntity
import io.zer0.muse.miniphone.data.db.entity.MpMomentEntity
import io.zer0.muse.miniphone.data.db.entity.MpMomentLikeEntity
import io.zer0.muse.miniphone.data.moment.MP_LOCAL_ACTOR_ID
import io.zer0.muse.miniphone.data.moment.decodeMomentImages

/**
 * 朋友圈页要展示的全部状态。
 *
 * [isLoaded] 用来区分「还没读完」和「真的没有动态」:两者都是一份空列表,
 * 但只有后者该显示空态,前者应当安静地留白。
 */
@Immutable
data class MomentsUiState(
    val coverImageUri: String? = null,
    val selfName: String = "",
    val selfAvatarUri: String? = null,
    val moments: List<MomentUi> = emptyList(),
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoaded: Boolean = false,
)

/**
 * 一条动态在界面上的一行。
 *
 * [likes] 只保留点赞人名字(按点赞时间先后),界面把它们串成一行;
 * [comments] 已经是组装好的两级结构,顶层评论带各自的回复。
 */
@Immutable
data class MomentUi(
    val id: String,
    val authorId: String,
    val authorName: String,
    val authorAvatarUri: String?,
    val content: String,
    val images: List<String>,
    val createdAt: Long,
    val isMine: Boolean,
    val likes: List<String>,
    val likedByMe: Boolean,
    val comments: List<MomentCommentUi>,
    /** 可见范围原样:空串 = 所有人可见。本阶段只用来在卡片上标一句「仅自己可见」。 */
    val visibleToJson: String = "",
)

/** 一条顶层评论(直接评论动态的那些)。 */
@Immutable
data class MomentCommentUi(
    val id: String,
    val senderName: String,
    val content: String,
    val createdAt: Long,
    val replies: List<MomentReplyUi> = emptyList(),
    val isMine: Boolean = false,
)

/**
 * 顶层评论下面的一条回复。
 *
 * [replyToName] 只在「回复的是另一条回复」时给出:两级展示会把所有回复压到同一条顶层评论下,
 * 不标出上一层是谁,读者会以为它一直在跟楼主说话。
 */
@Immutable
data class MomentReplyUi(
    val id: String,
    val senderName: String,
    val replyToName: String?,
    val content: String,
    val createdAt: Long,
    val isMine: Boolean = false,
)

/** 评论输入框当前指向谁:回复一条评论时带上它的 id 与作者名,直接评论动态时为空。 */
@Immutable
data class CommentTarget(
    val commentId: String,
    val senderName: String,
)

/**
 * 库里的三份数据 → 界面上的一行。
 *
 * 作者头像与名字取自宿主给的角色名单:动态行里存的名字是发布当时的快照,
 * 角色后来改名不该改写历史正文,但头像可以跟着当前名单走。
 */
internal fun buildMomentUi(
    entity: MpMomentEntity,
    likes: List<MpMomentLikeEntity>,
    comments: List<MpMomentCommentEntity>,
    characters: Map<String, MpCharacter>,
): MomentUi = MomentUi(
    id = entity.id,
    authorId = entity.authorId,
    authorName = entity.authorName,
    authorAvatarUri = characters[entity.authorId]?.avatarUri?.takeIf(String::isNotBlank),
    content = entity.content,
    images = decodeMomentImages(entity.imagesJson),
    createdAt = entity.createdAt,
    isMine = entity.source == MpMomentSource.USER && entity.authorId == MP_LOCAL_ACTOR_ID,
    likes = likes.map { like -> like.likerName },
    likedByMe = likes.any { like -> like.likerType == MpActorType.USER && like.likerId == MP_LOCAL_ACTOR_ID },
    comments = buildCommentTree(comments),
    visibleToJson = entity.visibleToJson,
)

/**
 * 平铺的评论 → 两级结构。
 *
 * 顶层是 `parentId` 为空(或指向一条已不存在的评论)的那些,按时间正序;
 * 其余一律挂到它所在那条顶层评论下 —— 回复的回复也压回顶层,深度不超过两级,
 * 一行评论里读得完。
 */
internal fun buildCommentTree(comments: List<MpMomentCommentEntity>): List<MomentCommentUi> {
    if (comments.isEmpty()) return emptyList()
    val byId = comments.associateBy { it.id }
    val repliesByRoot = groupRepliesByRoot(comments, byId)
    return comments
        .filter { comment -> comment.parentId == null || comment.parentId !in byId }
        .map { root ->
            MomentCommentUi(
                id = root.id,
                senderName = root.senderName,
                content = root.content,
                createdAt = root.createdAt,
                replies = repliesByRoot[root.id].orEmpty(),
                isMine = root.isMine,
            )
        }
}

private fun groupRepliesByRoot(
    comments: List<MpMomentCommentEntity>,
    byId: Map<String, MpMomentCommentEntity>,
): Map<String, List<MomentReplyUi>> {
    val grouped = mutableMapOf<String, MutableList<MomentReplyUi>>()
    comments.forEach { comment ->
        val parentId = comment.parentId
        if (parentId == null || parentId !in byId) return@forEach
        val rootId = rootIdOf(comment, byId)
        grouped.getOrPut(rootId) { mutableListOf() } += MomentReplyUi(
            id = comment.id,
            senderName = comment.senderName,
            replyToName = byId[parentId]?.senderName?.takeIf { comment.parentId != rootId },
            content = comment.content,
            createdAt = comment.createdAt,
            isMine = comment.isMine,
        )
    }
    return grouped
}

/** 顺着 `parentId` 往上找到顶层评论;链太长或成环时停在当时那一条,不跟着转圈。 */
private fun rootIdOf(comment: MpMomentCommentEntity, byId: Map<String, MpMomentCommentEntity>): String {
    var current = comment
    repeat(MAX_COMMENT_DEPTH) {
        val parent = current.parentId?.let(byId::get) ?: return current.id
        current = parent
    }
    return current.id
}

/** 往上找顶层的最大步数:正常的对话两三级就够,超过就当数据坏了。 */
private const val MAX_COMMENT_DEPTH: Int = 8

/** 一条评论是不是本机用户自己写的 —— 只有自己能删。 */
private val MpMomentCommentEntity.isMine: Boolean
    get() = senderType == MpActorType.USER && senderId == MP_LOCAL_ACTOR_ID

/** 点赞名单最多逐个列几个名字,再多就收口成「等 N 人」。 */
internal const val LIKERS_INLINE_MAX: Int = 10

/** 收口后逐个列出来的名字个数。 */
internal const val LIKERS_SHOWN_WHEN_FOLDED: Int = 9

/**
 * 点赞名单那一行:一行写完,不在框里滚动。
 *
 * 三个人就是「❤️ 阿远，小满，老陈」;超过 [LIKERS_INLINE_MAX] 个时只列前
 * [LIKERS_SHOWN_WHEN_FOLDED] 个,后面接「等 N 人」——N 是全部点赞人数,不是省略掉的人数。
 * 名字之间用中文逗号,与正文的顿号区分开。
 */
internal fun likersLineOf(names: List<String>): String {
    val shown = names.map(String::trim).filter(String::isNotEmpty)
    if (shown.isEmpty()) return ""
    val folded = shown.size > LIKERS_INLINE_MAX
    val parts = shown.take(if (folded) LIKERS_SHOWN_WHEN_FOLDED else shown.size)
    val namesText = parts.joinToString(LIKERS_SEPARATOR)
    return if (folded) "$LIKERS_MARK$namesText${LIKERS_SEPARATOR}等 ${shown.size} 人" else "$LIKERS_MARK$namesText"
}

private const val LIKERS_MARK: String = "❤️ "
private const val LIKERS_SEPARATOR: String = "，"
