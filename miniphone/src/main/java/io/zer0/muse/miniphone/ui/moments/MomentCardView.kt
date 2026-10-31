package io.zer0.muse.miniphone.ui.moments

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.zer0.muse.miniphone.ui.chat.ChatMenuPositionProvider
import io.zer0.muse.miniphone.ui.chat.MiniPhoneChatAvatar
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.shell.relativeTimeOf
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme

/**
 * 一条动态的卡片:作者、正文、图片、点赞、评论、评论输入。
 *
 * 只有被回复的那条评论名活在卡片里([replyTarget]),其余状态全部来自 [moment];
 * 发出去的评论走回调,由 ViewModel 落库后带着新的 [MomentUi] 回来重画,
 * 卡片自己不维护一份「本地评论列表」—— 那样一翻页就对不上了。
 * @param onDeleteComment 删自己写的一条评论;它的回复会跟着一起删,由上层落库
 */
@Composable
internal fun MomentCardView(
    moment: MomentUi,
    onToggleLike: () -> Unit,
    onOpenImage: (images: List<String>, index: Int) -> Unit,
    onComment: (parentId: String?, content: String) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onDeleteComment: (commentId: String) -> Unit = {},
) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    var replyTarget by remember(moment.id) { mutableStateOf<CommentTarget?>(null) }
    // 长按评论弹出的「删除」与它的确认框:两件事分开,菜单关掉、确认框还开着
    var commentMenuId by remember(moment.id) { mutableStateOf<String?>(null) }
    var pendingCommentDelete by remember(moment.id) { mutableStateOf<String?>(null) }
    val cardShape = RoundedCornerShape(dimens.cardRadius)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(colors.surface)
            .border(dimens.hairline, colors.divider, cardShape)
            .testTag(MiniPhoneMomentsTags.moment(moment.id))
            .padding(CardPadding),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            MiniPhoneChatAvatar(
                name = moment.authorName,
                avatarUri = moment.authorAvatarUri,
                size = dimens.avatarList,
                modifier = Modifier.testTag(MiniPhoneMomentsTags.author(moment.id)),
            )
            Spacer(Modifier.width(AvatarGap))
            Column(verticalArrangement = Arrangement.spacedBy(ContentGap)) {
                MomentHeader(moment)
                if (moment.content.isNotBlank()) MomentBody(moment)
                if (moment.images.isNotEmpty()) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        MomentImageGrid(
                            momentId = moment.id,
                            images = moment.images,
                            maxWidth = maxWidth,
                            onOpen = { index -> onOpenImage(moment.images, index) },
                        )
                    }
                }
                MomentActionRow(moment = moment, onToggleLike = onToggleLike, onDelete = onDelete)
                if (moment.likes.isNotEmpty()) MomentLikers(moment)
                if (moment.comments.isNotEmpty()) {
                    MomentComments(
                        comments = moment.comments,
                        menuFor = commentMenuId,
                        onReply = { target -> replyTarget = target },
                        onAskMenu = { commentId -> commentMenuId = commentId },
                        onDismissMenu = { commentMenuId = null },
                        onDeleteRequested = { commentId ->
                            commentMenuId = null
                            pendingCommentDelete = commentId
                        },
                    )
                }
                MomentCommentInput(
                    momentId = moment.id,
                    replyTargetName = replyTarget?.senderName,
                    onSend = { content ->
                        onComment(replyTarget?.commentId, content)
                        replyTarget = null
                    },
                )
            }
        }
    }
    val pending = pendingCommentDelete
    if (pending != null) {
        CommentDeleteConfirmDialog(
            onDismiss = { pendingCommentDelete = null },
            onConfirm = {
                pendingCommentDelete = null
                onDeleteComment(pending)
            },
        )
    }
}

/** 作者名与时间:时间按「刚刚 / N 分钟前 / HH:mm」算,和会话列表同一套口径。 */
@Composable
private fun MomentHeader(moment: MomentUi) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = moment.authorName,
            style = typography.caption.copy(color = colors.accent),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = relativeTimeOf(moment.createdAt),
            style = typography.timestamp.copy(color = colors.textSecondary),
            modifier = Modifier.testTag(MiniPhoneMomentsTags.time(moment.id)),
        )
    }
}

/** 正文。 */
@Composable
private fun MomentBody(moment: MomentUi) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Text(
        text = moment.content,
        style = typography.body.copy(color = colors.textPrimary),
        modifier = Modifier.testTag(MiniPhoneMomentsTags.content(moment.id)),
    )
}

/** 点赞与删除:删除只出现在自己发的动态上。 */
@Composable
private fun MomentActionRow(moment: MomentUi, onToggleLike: () -> Unit, onDelete: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(ActionCorner))
                .clickable(onClick = onToggleLike)
                .testTag(MiniPhoneMomentsTags.likeButton(moment.id))
                .padding(horizontal = ActionPaddingH, vertical = ActionPaddingV),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = MiniPhoneIcons.Heart,
                contentDescription = "点赞",
                tint = if (moment.likedByMe) colors.unreadDot else colors.textSecondary,
                modifier = Modifier.size(ActionIconSize),
            )
            Spacer(Modifier.width(ActionIconGap))
            Text(
                text = moment.likes.size.toString(),
                style = typography.timestamp.copy(color = colors.textSecondary),
                modifier = Modifier.testTag(MiniPhoneMomentsTags.likeCount(moment.id)),
            )
        }
        if (moment.isMine) {
            Spacer(Modifier.width(ActionGap))
            Text(
                text = DELETE_LABEL,
                style = typography.timestamp.copy(color = colors.textSecondary),
                modifier = Modifier
                    .clip(RoundedCornerShape(ActionCorner))
                    .clickable(onClick = onDelete)
                    .testTag(MiniPhoneMomentsTags.delete(moment.id))
                    .padding(horizontal = ActionPaddingH, vertical = ActionPaddingV),
            )
        }
        // 可见范围只写了不读,但得让作者看得见自己选了「仅自己」
        if (moment.isMine && moment.visibleToJson.isNotBlank()) {
            Spacer(Modifier.width(ActionGap))
            Text(
                text = ONLY_SELF_LABEL,
                style = typography.timestamp.copy(color = colors.textSecondary),
                modifier = Modifier.testTag(MiniPhoneMomentsTags.visibleSelf(moment.id)),
            )
        }
    }
}

/**
 * 点赞人一行:按点赞时间先后串起来,一行写完,不滚动。
 *
 * 人多了就收口成「❤️ A，B，…，等 12 人」,不在卡片里堆成一大块。
 */
@Composable
private fun MomentLikers(moment: MomentUi) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Text(
        text = likersLineOf(moment.likes),
        style = typography.timestamp.copy(color = colors.textSecondary),
        softWrap = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MiniPhoneMomentsTags.likers(moment.id)),
    )
}

/**
 * 评论区:顶层评论一行,它的回复缩一格接在下面。
 *
 * 点任意一行把输入框切成「回复 TA」;自己写的评论还能长按弹出「删除」,
 * 别人的评论不给这个入口 —— 所以那一刻的动作列表是空的。
 * 回复的回复也只压一层,前缀里点明上一层是谁。
 */
@Composable
private fun MomentComments(
    comments: List<MomentCommentUi>,
    menuFor: String?,
    onReply: (CommentTarget) -> Unit,
    onAskMenu: (String) -> Unit,
    onDismissMenu: () -> Unit,
    onDeleteRequested: (String) -> Unit,
) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    val shape = RoundedCornerShape(CommentCorner)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.background)
            .border(MiniPhoneTheme.dimens.hairline, colors.divider, shape)
            .padding(CommentBlockPadding),
        verticalArrangement = Arrangement.spacedBy(CommentGap),
    ) {
        comments.forEach { comment ->
            Box {
                CommentLine(
                    line = CommentLineModel(
                        id = comment.id,
                        text = "${comment.senderName}：${comment.content}",
                        createdAt = comment.createdAt,
                        indent = 0.dp,
                        canDelete = comment.isMine,
                        isReply = false,
                    ),
                    onReply = { onReply(CommentTarget(commentId = comment.id, senderName = comment.senderName)) },
                    onAskDelete = { onAskMenu(comment.id) },
                )
                if (menuFor == comment.id) {
                    CommentDeleteMenu(onDismiss = onDismissMenu, onDelete = { onDeleteRequested(comment.id) })
                }
            }
            comment.replies.forEach { reply ->
                Box {
                    CommentLine(
                        line = CommentLineModel(
                            id = reply.id,
                            text = replyText(reply),
                            createdAt = reply.createdAt,
                            indent = ReplyIndent,
                            canDelete = reply.isMine,
                            isReply = true,
                        ),
                        onReply = { onReply(CommentTarget(commentId = reply.id, senderName = reply.senderName)) },
                        onAskDelete = { onAskMenu(reply.id) },
                    )
                    if (menuFor == reply.id) {
                        CommentDeleteMenu(onDismiss = onDismissMenu, onDelete = { onDeleteRequested(reply.id) })
                    }
                }
            }
        }
    }
}

/**
 * 一行评论或回复:正文占满一行,句中带一句相对时间。
 *
 * 只有自己能删的那几行能长按:别人的评论不绑长按,按住什么也不会发生 ——
 * 入口不存在比弹一个只能取消的菜单更直接。
 */
@Composable
private fun CommentLine(line: CommentLineModel, onReply: () -> Unit, onAskDelete: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = line.indent)
            .combinedClickable(
                onClick = onReply,
                onLongClick = if (line.canDelete) onAskDelete else null,
            )
            .testTag(line.rowTag()),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = line.text,
            style = typography.caption.copy(color = colors.textPrimary),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = relativeTimeOf(line.createdAt),
            style = typography.timestamp.copy(color = colors.textSecondary),
            modifier = Modifier
                .padding(start = CommentTimeGap)
                .testTag(line.timeTag()),
        )
    }
}

/** 一行评论要画的东西:正文、时间、缩进、能不能删、是不是回复。 */
@Immutable
private data class CommentLineModel(
    val id: String,
    val text: String,
    val createdAt: Long,
    val indent: Dp,
    val canDelete: Boolean,
    val isReply: Boolean,
) {
    fun rowTag(): String = if (isReply) MiniPhoneMomentsTags.replyRow(id) else MiniPhoneMomentsTags.commentRow(id)

    fun timeTag(): String = if (isReply) MiniPhoneMomentsTags.replyTime(id) else MiniPhoneMomentsTags.commentTime(id)
}

/** 长按自己的评论弹出的动作浮层:只有一项「删除」。 */
@Composable
private fun CommentDeleteMenu(onDismiss: () -> Unit, onDelete: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val density = LocalDensity.current
    val gapPx = with(density) { MenuGap.roundToPx() }
    val marginPx = with(density) { dimens.pageMargin.roundToPx() }
    val positionProvider = remember(gapPx, marginPx) {
        ChatMenuPositionProvider(alignEnd = false, gapPx = gapPx, marginPx = marginPx)
    }
    val shape = RoundedCornerShape(dimens.cardRadius)
    Popup(
        onDismissRequest = onDismiss,
        popupPositionProvider = positionProvider,
        properties = PopupProperties(focusable = true),
    ) {
        Text(
            text = DELETE_COMMENT_LABEL,
            style = typography.body.copy(color = colors.unreadDot),
            modifier = Modifier
                .testTag(MiniPhoneMomentsTags.COMMENT_MENU_ACTION)
                .clip(shape)
                .background(colors.surface)
                .border(dimens.hairline, colors.divider, shape)
                .clickable {
                    onDelete()
                    onDismiss()
                }
                .padding(horizontal = 18.dp, vertical = 11.dp),
        )
    }
}

/** 删评论前的确认:一句话写明回复会跟着走。 */
@Composable
private fun CommentDeleteConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val shape = RoundedCornerShape(dimens.cardRadius)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .testTag(MiniPhoneMomentsTags.COMMENT_DELETE_DIALOG)
                .width(CommentDialogWidth)
                .clip(shape)
                .background(colors.surface)
                .border(dimens.hairline, colors.divider, shape)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(text = COMMENT_DELETE_TITLE, style = typography.title.copy(color = colors.textPrimary))
            Spacer(Modifier.height(8.dp))
            Text(text = COMMENT_DELETE_HINT, style = typography.caption.copy(color = colors.textSecondary))
            Spacer(Modifier.height(18.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    text = COMMENT_DELETE_CANCEL_LABEL,
                    style = typography.body.copy(color = colors.textSecondary),
                    modifier = Modifier
                        .testTag(MiniPhoneMomentsTags.COMMENT_DELETE_CANCEL)
                        .clip(RoundedCornerShape(CancelCorner))
                        .clickable(onClick = onDismiss)
                        .heightIn(min = DialogActionHeight)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = DELETE_COMMENT_LABEL,
                    style = typography.body.copy(color = colors.unreadDot),
                    modifier = Modifier
                        .testTag(MiniPhoneMomentsTags.COMMENT_DELETE_CONFIRM)
                        .clip(RoundedCornerShape(CancelCorner))
                        .clickable(onClick = onConfirm)
                        .heightIn(min = DialogActionHeight)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** 回复的正文;回复的是另一条回复时,前缀补一句「回复谁」。 */
internal fun replyText(reply: MomentReplyUi): String {
    val to = reply.replyToName
    return if (to == null) {
        "${reply.senderName}：${reply.content}"
    } else {
        "${reply.senderName} 回复 $to：${reply.content}"
    }
}

/** 评论输入:一行输入框加一个发送键,空白内容不发。 */
@Composable
private fun MomentCommentInput(momentId: String, replyTargetName: String?, onSend: (String) -> Unit) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    val shape = RoundedCornerShape(CommentCorner)
    var draft by rememberSaveable(momentId, replyTargetName) { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(shape)
                .background(colors.background)
                .border(MiniPhoneTheme.dimens.hairline, colors.divider, shape)
                .padding(horizontal = InputPaddingH, vertical = InputPaddingV),
        ) {
            if (draft.isEmpty()) {
                Text(
                    text = replyTargetName?.let { "回复 $it" } ?: COMMENT_PLACEHOLDER,
                    style = typography.timestamp.copy(color = colors.textSecondary),
                )
            }
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = typography.caption.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(MiniPhoneMomentsTags.commentInput(momentId)),
            )
        }
        Spacer(Modifier.width(CommentSendGap))
        Box(
            modifier = Modifier
                .size(CommentSendSize)
                .clip(RoundedCornerShape(CommentCorner))
                .clickable(enabled = draft.isNotBlank()) {
                    onSend(draft)
                    draft = ""
                }
                .testTag(MiniPhoneMomentsTags.commentSend(momentId)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MiniPhoneIcons.Send,
                contentDescription = "发送评论",
                tint = if (draft.isNotBlank()) colors.accent else colors.textSecondary,
                modifier = Modifier.size(CommentSendIcon),
            )
        }
    }
}

private val CardPadding = 12.dp
private val AvatarGap = 10.dp
private val ContentGap = 6.dp
private val ActionCorner = 8.dp
private val ActionPaddingH = 2.dp
private val ActionPaddingV = 2.dp
private val ActionGap = 12.dp
private val ActionIconSize = 14.dp
private val ActionIconGap = 4.dp
private val CommentCorner = 8.dp
private val CommentBlockPadding = 8.dp
private val CommentGap = 4.dp
private val ReplyIndent = 10.dp
private val InputPaddingH = 10.dp
private val InputPaddingV = 6.dp
private val CommentSendGap = 8.dp
private val CommentSendSize = 24.dp
private val CommentSendIcon = 16.dp
private val CommentTimeGap = 8.dp
private val MenuGap = 6.dp
private val CommentDialogWidth = 280.dp
private val CancelCorner = 8.dp
private val DialogActionHeight = 36.dp

private const val DELETE_COMMENT_LABEL: String = "删除"
private const val COMMENT_DELETE_TITLE: String = "删除这条评论?"
private const val COMMENT_DELETE_HINT: String = "这条评论和它下面的回复会一起删除。"
private const val COMMENT_DELETE_CANCEL_LABEL: String = "取消"

private const val DELETE_LABEL: String = "删除"
private const val ONLY_SELF_LABEL: String = "仅自己可见"
private const val COMMENT_PLACEHOLDER: String = "说点什么"
