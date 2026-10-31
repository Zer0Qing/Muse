package io.zer0.muse.miniphone.ui.moments

/**
 * 朋友圈页元素的稳定标签,供自动化测试定位。
 *
 * 与文案分开:改标题、换提示语都不会让测试失效。
 */
internal object MiniPhoneMomentsTags {
    const val ROOT = "mp-moments-root"
    const val BACK_BUTTON = "mp-moments-back"
    const val COMPOSE_ENTRY = "mp-moments-compose-entry"
    const val LIST = "mp-moments-list"
    const val COVER = "mp-moments-cover"
    const val SELF_NAME = "mp-moments-self-name"
    const val SELF_AVATAR = "mp-moments-self-avatar"
    const val EMPTY = "mp-moments-empty"
    const val VIEWER = "mp-moments-viewer"
    const val PUBLISH_SCREEN = "mp-moments-publish"
    const val PUBLISH_INPUT = "mp-moments-publish-input"
    const val PUBLISH_CONFIRM = "mp-moments-publish-confirm"
    const val PUBLISH_CLOSE = "mp-moments-publish-close"
    const val PUBLISH_PICK = "mp-moments-publish-pick"
    const val PUBLISH_VISIBLE_SELF = "mp-moments-publish-visible-self"

    fun moment(id: String): String = "mp-moment-$id"

    fun author(id: String): String = "mp-moment-author-$id"

    fun content(id: String): String = "mp-moment-content-$id"

    fun time(id: String): String = "mp-moment-time-$id"

    fun image(momentId: String, index: Int): String = "mp-moment-image-$momentId-$index"

    fun likeButton(id: String): String = "mp-moment-like-$id"

    fun visibleSelf(id: String): String = "mp-moment-visible-self-$id"

    fun likeCount(id: String): String = "mp-moment-like-count-$id"

    fun likers(id: String): String = "mp-moment-likers-$id"

    fun delete(id: String): String = "mp-moment-delete-$id"

    fun commentInput(id: String): String = "mp-moment-comment-input-$id"

    fun commentSend(id: String): String = "mp-moment-comment-send-$id"

    fun commentRow(commentId: String): String = "mp-moment-comment-$commentId"

    fun replyRow(replyId: String): String = "mp-moment-reply-$replyId"

    fun commentTime(commentId: String): String = "mp-moment-comment-time-$commentId"

    fun replyTime(replyId: String): String = "mp-moment-reply-time-$replyId"

    /** 只有自己写的评论才有的「删除」入口与它的确认框。 */
    const val COMMENT_MENU_ACTION = "mp-moment-comment-menu-delete"
    const val COMMENT_DELETE_DIALOG = "mp-moment-comment-delete-dialog"
    const val COMMENT_DELETE_CONFIRM = "mp-moment-comment-delete-confirm"
    const val COMMENT_DELETE_CANCEL = "mp-moment-comment-delete-cancel"
}
