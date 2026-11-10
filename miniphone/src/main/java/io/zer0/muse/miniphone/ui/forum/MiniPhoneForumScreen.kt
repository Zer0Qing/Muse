package io.zer0.muse.miniphone.ui.forum

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.miniphone.R
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.db.entity.MpForumCommentEntity
import io.zer0.muse.miniphone.data.db.entity.MpForumPostEntity
import io.zer0.muse.miniphone.data.forum.MpForumStore
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.flow.Flow

/**
 * 论坛页：一条时间线，能发帖、点赞、评论，也能只看某个作者发过的帖子。
 *
 * 状态与文案都在界面层按当前语言渲染，数据层只存原文与点赞名单：
 * 作者名在库里允许为空，空名在这里换成当前语言的「我 / 角色」，
 * 不让某一个语言的词冻进数据、换语言还挂在帖子上。
 *
 * 只有自己的帖子给出删除入口 —— 别人的帖子这一页不负责处置。
 * 看别人的主页时给一个「发私信」：论坛是认识人的地方，认出人之后要能接着聊。
 */
@Composable
internal fun MiniPhoneForumScreen(
    posts: Flow<List<MpForumPostEntity>>,
    commentsFor: (String) -> Flow<List<MpForumCommentEntity>>,
    currentUserId: String,
    characters: List<MpCharacter>,
    onPublish: (String) -> Unit,
    onComment: (String, String) -> Unit,
    onToggleLike: (String) -> Unit,
    onDelete: (String) -> Unit,
    onComposePost: (String) -> Unit,
    onMessageAuthor: (String) -> Unit = {},
    onBack: () -> Unit,
) {
    val colors = MiniPhoneTheme.colors
    val rows by posts.collectAsStateWithLifecycle(initialValue = emptyList())
    var authorId by remember { mutableStateOf<String?>(null) }
    val shown = if (authorId == null) rows else rows.filter { post -> post.authorId == authorId }
    Column(
        modifier = Modifier.fillMaxSize().testTag(MiniPhoneForumTags.PAGE).background(colors.background),
    ) {
        ForumHeader(onBack)
        ForumComposer(onPublish)
        if (characters.isNotEmpty()) ForumCharacterPanel(characters, onComposePost)
        val filteringBy = authorId
        if (filteringBy != null) {
            ForumFilterBar(
                authorId = filteringBy,
                currentUserId = currentUserId,
                onClear = { authorId = null },
                onMessage = onMessageAuthor,
            )
        }
        if (shown.isEmpty()) {
            ForumEmpty(filtered = authorId != null)
        } else {
            ForumPostList(
                rows = shown,
                currentUserId = currentUserId,
                commentsFor = commentsFor,
                onComment = onComment,
                onToggleLike = onToggleLike,
                onDelete = onDelete,
                onOpenAuthor = { id -> authorId = id },
            )
        }
    }
}

@Composable
private fun ForumHeader(onBack: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MiniPhoneIcons.Back,
            contentDescription = stringResource(R.string.mp_forum_back),
            tint = colors.textPrimary,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onBack)
                .padding(6.dp).size(22.dp).testTag(MiniPhoneForumTags.BACK),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.mp_forum_title),
            style = MiniPhoneTheme.typography.body.copy(color = colors.textPrimary),
        )
    }
}

/**
 * 发帖输入条：写完点发布，正文去掉首尾空白后交出去，输入框随即清空。
 *
 * 空白输入在提交前判空挡住（门面那一侧也挡一遍），按钮只把颜色压暗、不禁用点击 ——
 * 点了没反应与点不动是两件事，这里选前者，用户至少知道按钮还在。
 */
@Composable
private fun ForumComposer(onPublish: (String) -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    var draft by remember { mutableStateOf("") }

    fun commit() {
        val text = draft.trim()
        if (text.isEmpty()) return
        onPublish(text)
        draft = ""
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = dimens.pageMargin, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = { Text(stringResource(R.string.mp_forum_hint)) },
            singleLine = true,
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier.weight(1f).testTag(MiniPhoneForumTags.INPUT),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.mp_forum_publish),
            style = MiniPhoneTheme.typography.body.copy(
                color = if (draft.isBlank()) colors.textSecondary else colors.accent,
            ),
            modifier = Modifier.clip(CircleShape).clickable { commit() }
                .padding(horizontal = 12.dp, vertical = 10.dp).testTag(MiniPhoneForumTags.PUBLISH),
        )
    }
}

/** 让角色发帖：一点一个名字就请 TA 就最近的事发一条，内容全靠宿主生成。 */
@Composable
private fun ForumCharacterPanel(characters: List<MpCharacter>, onComposePost: (String) -> Unit) {
    val colors = MiniPhoneTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = MiniPhoneTheme.dimens.pageMargin, vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.mp_forum_compose_cd),
            style = MiniPhoneTheme.typography.timestamp,
            color = colors.textSecondary,
        )
        Spacer(Modifier.size(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            characters.forEach { character ->
                ForumChip(character.name, active = false, tag = MiniPhoneForumTags.character(character.id)) {
                    onComposePost(character.id)
                }
            }
        }
    }
}

/** 只在看某个作者的主页时才出现：点一下回到整条时间线。 */
@Composable
private fun ForumFilterBar(authorId: String, currentUserId: String, onClear: () -> Unit, onMessage: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MiniPhoneTheme.dimens.pageMargin),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ForumChip(stringResource(R.string.mp_forum_all), active = true, tag = MiniPhoneForumTags.ALL_AUTHORS, onClick = onClear)
        // 看自己的主页没有「私信自己」这回事，只在看别人时出现
        if (authorId != currentUserId) {
            ForumChip(
                label = stringResource(R.string.mp_forum_message_author),
                active = false,
                tag = MiniPhoneForumTags.MESSAGE_AUTHOR,
            ) { onMessage(authorId) }
        }
    }
}

@Composable
private fun ForumEmpty(filtered: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(MiniPhoneForumTags.EMPTY),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(if (filtered) R.string.mp_forum_author_empty else R.string.mp_forum_empty),
            style = MiniPhoneTheme.typography.caption,
            color = MiniPhoneTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun ForumPostList(
    rows: List<MpForumPostEntity>,
    currentUserId: String,
    commentsFor: (String) -> Flow<List<MpForumCommentEntity>>,
    onComment: (String, String) -> Unit,
    onToggleLike: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenAuthor: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = MiniPhoneTheme.dimens.pageMargin, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(rows, key = { it.id }) { post ->
            ForumPostRow(
                post = post,
                currentUserId = currentUserId,
                commentsFor = commentsFor,
                onComment = onComment,
                onToggleLike = onToggleLike,
                onDelete = onDelete,
                onOpenAuthor = onOpenAuthor,
            )
        }
    }
}

@Composable
private fun ForumPostRow(
    post: MpForumPostEntity,
    currentUserId: String,
    commentsFor: (String) -> Flow<List<MpForumCommentEntity>>,
    onComment: (String, String) -> Unit,
    onToggleLike: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenAuthor: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    val likes = MpForumStore.decodeLikeIds(post.likeIdsJson)
    val likedByMe = likes.contains(currentUserId)
    Column(
        modifier = Modifier.fillMaxWidth().testTag(MiniPhoneForumTags.postRow(post.id)),
    ) {
        Text(
            text = forumAuthorName(post.authorId, post.authorName, currentUserId),
            style = typography.caption.copy(color = colors.textSecondary),
            modifier = Modifier.clip(CircleShape).clickable { onOpenAuthor(post.authorId) }
                .padding(horizontal = 4.dp, vertical = 2.dp).testTag(MiniPhoneForumTags.author(post.authorId)),
        )
        Text(
            text = post.content,
            style = typography.body.copy(color = colors.textPrimary),
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            ForumChip(
                label = stringResource(if (likedByMe) R.string.mp_forum_liked else R.string.mp_forum_like),
                active = likedByMe,
                tag = MiniPhoneForumTags.like(post.id),
            ) { onToggleLike(post.id) }
            if (likes.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.mp_forum_like_count, likes.size),
                    style = typography.timestamp,
                    color = colors.textSecondary,
                )
            }
            ForumChip(stringResource(R.string.mp_forum_comment), expanded, MiniPhoneForumTags.comments(post.id)) {
                expanded = !expanded
            }
            if (post.authorId == currentUserId) {
                ForumChip(stringResource(R.string.mp_forum_delete), false, MiniPhoneForumTags.delete(post.id)) {
                    onDelete(post.id)
                }
            }
        }
        if (expanded) ForumCommentThread(post.id, currentUserId, commentsFor, onComment)
    }
}

@Composable
private fun ForumCommentThread(
    postId: String,
    currentUserId: String,
    commentsFor: (String) -> Flow<List<MpForumCommentEntity>>,
    onComment: (String, String) -> Unit,
) {
    val rows by commentsFor(postId).collectAsStateWithLifecycle(initialValue = emptyList())
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (rows.isEmpty()) {
            Text(
                text = stringResource(R.string.mp_forum_no_comments),
                style = typography.caption,
                color = colors.textSecondary,
            )
        }
        rows.forEach { comment ->
            Column(modifier = Modifier.testTag(MiniPhoneForumTags.commentRow(comment.id))) {
                Text(
                    text = forumAuthorName(comment.authorId, comment.authorName, currentUserId),
                    style = typography.timestamp,
                    color = colors.textSecondary,
                )
                Text(text = comment.content, style = typography.caption, color = colors.textPrimary)
            }
        }
        ForumCommentComposer(postId = postId, onComment = onComment)
    }
}

@Composable
private fun ForumCommentComposer(postId: String, onComment: (String, String) -> Unit) {
    val colors = MiniPhoneTheme.colors
    var draft by remember { mutableStateOf("") }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty()) return
        onComment(postId, text)
        draft = ""
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = { Text(stringResource(R.string.mp_forum_comment_hint)) },
            singleLine = true,
            keyboardActions = KeyboardActions(onDone = { send() }),
            modifier = Modifier.weight(1f).testTag(MiniPhoneForumTags.commentInput(postId)),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.mp_forum_comment_send),
            style = MiniPhoneTheme.typography.caption.copy(
                color = if (draft.isBlank()) colors.textSecondary else colors.accent,
            ),
            modifier = Modifier.clip(CircleShape).clickable { send() }
                .padding(horizontal = 10.dp, vertical = 8.dp).testTag(MiniPhoneForumTags.commentSend(postId)),
        )
    }
}

@Composable
private fun ForumChip(label: String, active: Boolean, tag: String, onClick: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    Text(
        text = label,
        style = MiniPhoneTheme.typography.caption.copy(
            color = if (active) colors.accent else colors.textSecondary,
        ),
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp).testTag(tag),
    )
}

/** 作者显示名：库里存的空名在这里按当前语言补齐。 */
@Composable
private fun forumAuthorName(authorId: String, authorName: String, currentUserId: String): String {
    if (authorName.isNotBlank()) return authorName
    val res = if (authorId == currentUserId) R.string.mp_forum_self_name else R.string.mp_forum_character_name
    return stringResource(res)
}
