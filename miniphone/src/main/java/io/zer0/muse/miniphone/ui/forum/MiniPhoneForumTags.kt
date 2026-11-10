package io.zer0.muse.miniphone.ui.forum

/** 论坛页的稳定定位标签，供自动化测试使用。 */
internal object MiniPhoneForumTags {
    const val PAGE = "mp-forum-page"
    const val EMPTY = "mp-forum-empty"
    const val BACK = "mp-forum-back"
    const val INPUT = "mp-forum-input"
    const val PUBLISH = "mp-forum-publish"
    const val ALL_AUTHORS = "mp-forum-all-authors"
    const val MESSAGE_AUTHOR = "mp-forum-message-author"

    fun character(id: String): String = "mp-forum-character-$id"

    fun postRow(id: String): String = "mp-forum-post-$id"

    fun author(id: String): String = "mp-forum-author-$id"

    fun like(id: String): String = "mp-forum-like-$id"

    fun comments(id: String): String = "mp-forum-comments-$id"

    fun delete(id: String): String = "mp-forum-delete-$id"

    fun commentRow(id: String): String = "mp-forum-comment-$id"

    fun commentInput(id: String): String = "mp-forum-comment-input-$id"

    fun commentSend(id: String): String = "mp-forum-comment-send-$id"
}
