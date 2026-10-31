package io.zer0.muse.miniphone.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.zer0.muse.miniphone.api.MpActivityKind
import io.zer0.muse.miniphone.data.db.MpMessageStatus
import io.zer0.muse.miniphone.ui.shell.MiniPhoneTimeFormat
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlin.math.abs

/**
 * 一条消息的整行。
 *
 * 自己靠右、对方靠左,两头都带头像(与单聊习惯一致:一眼分得清是谁说的);
 * 系统提示不参与左右分栏,单独走一枚居中小字。正文只在文本消息里画:图片和语音
 * 各自换成自己的节点。行宽由列表算好后传进来,气泡自己不知道屏幕多宽。
 *
 * 长按气泡弹菜单(复制 / 删除 / 重发),删除要过一道确认;自己发的消息还会在气泡旁显示
 * 发送状态 —— 发送中一行浅字,失败一个可点的标记。
 *
 * @param self 自己那侧的名字与头像(画右侧头像的首字)
 * @param peer 对方那侧的名字与头像
 * @param senderLabel 群里这条消息是谁说的;私信传 null,不在气泡旁写名字
 * @param onOpenImage 点开图片
 * @param messageActions 重发与删除;具体做不做由菜单决定
 */
@Composable
internal fun ChatMessageRow(
    message: ChatMessageUi,
    self: ChatPersona,
    peer: ChatPersona,
    maxBubbleWidth: Dp,
    onOpenImage: (String) -> Unit,
    messageActions: ChatMessageActions = ChatMessageActions(),
    senderLabel: String? = null,
) {
    if (message.isSystem) {
        SystemNoticeRow(message = message)
        return
    }
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val clipboard = LocalClipboardManager.current
    val onResend = messageActions.onResend
    val onDelete = messageActions.onDelete
    var menuOpen by remember(message.id) { mutableStateOf(false) }
    var confirmingDelete by remember(message.id) { mutableStateOf(false) }
    val openMenu: () -> Unit = { menuOpen = true }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MiniPhoneChatTags.message(message.id))
            .padding(horizontal = dimens.pageMargin, vertical = 4.dp),
        horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!message.isMine) {
            MiniPhoneChatAvatar(name = peer.name, avatarUri = peer.avatarUri, size = dimens.avatarBubble)
            Spacer(Modifier.width(BubbleAvatarGap))
        }
        if (senderLabel != null && !message.isMine) {
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    text = senderLabel,
                    style = typography.timestamp.copy(color = MiniPhoneTheme.colors.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(MiniPhoneChatTags.senderName(message.id)),
                )
                Spacer(Modifier.height(SenderNameGap))
                MessageBubbleContent(
                    message = message,
                    maxBubbleWidth = maxBubbleWidth,
                    onOpenImage = onOpenImage,
                    onLongPress = openMenu,
                )
            }
        } else {
            MessageBubbleContent(
                message = message,
                maxBubbleWidth = maxBubbleWidth,
                onOpenImage = onOpenImage,
                onLongPress = openMenu,
            )
        }
        if (message.isMine) {
            if (message.status != MpMessageStatus.SENT) {
                Spacer(Modifier.width(StatusGap))
                ChatDeliveryState(message = message, onRetry = { onResend(message.id) })
            }
            Spacer(Modifier.width(BubbleAvatarGap))
            MiniPhoneChatAvatar(
                name = self.name,
                avatarUri = self.avatarUri,
                size = dimens.avatarBubble,
                modifier = Modifier.testTag(MiniPhoneChatTags.SELF_AVATAR),
            )
        }
        if (menuOpen) {
            ChatMessageMenu(
                message = message,
                onDismiss = { menuOpen = false },
                onAction = { action ->
                    when (action) {
                        ChatMenuAction.COPY -> clipboard.setText(AnnotatedString(message.content))
                        ChatMenuAction.DELETE -> confirmingDelete = true
                        ChatMenuAction.RESEND -> onResend(message.id)
                    }
                },
            )
        }
    }
    if (confirmingDelete) {
        ChatDeleteConfirmDialog(
            onDismiss = { confirmingDelete = false },
            onConfirm = {
                confirmingDelete = false
                onDelete(message.id)
            },
        )
    }
}

/**
 * 气泡旁的发送状态:发送中一行浅字,失败一枚可点的砖红标记。
 *
 * 两者都不拦正文 —— 发送中也要能看清自己刚写的那句话。
 */
@Composable
private fun ChatDeliveryState(message: ChatMessageUi, onRetry: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    if (message.isSending) {
        Text(
            text = SENDING_TEXT,
            style = typography.timestamp.copy(color = colors.textSecondary),
            modifier = Modifier.testTag(MiniPhoneChatTags.status(message.id)),
        )
        return
    }
    Row(
        modifier = Modifier
            .testTag(MiniPhoneChatTags.status(message.id))
            .clip(RoundedCornerShape(SendFailRadius))
            .clickable(onClick = onRetry)
            .semantics { contentDescription = SEND_FAILED_TEXT }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = SEND_FAILED_MARK, style = typography.caption.copy(color = colors.unreadDot))
        Spacer(Modifier.width(2.dp))
        Text(text = SEND_FAILED_TEXT, style = typography.timestamp.copy(color = colors.unreadDot))
    }
}

/**
 * 气泡本体:图片、语音、文本各一条分支。
 *
 * 单独抽出来是因为群里要在它上面多一行发言人名字 —— 两种布局共用同一份气泡,
 * 否则「有名字」和「没名字」会各有一份渲染代码。
 */
@Composable
private fun MessageBubbleContent(message: ChatMessageUi, maxBubbleWidth: Dp, onOpenImage: (String) -> Unit, onLongPress: () -> Unit) {
    if (message.isImage) {
        ChatImageBubble(message = message, maxWidth = maxBubbleWidth, onOpen = onOpenImage, onLongPress = onLongPress)
    } else if (message.isVoice) {
        // 语音条自己定宽(按时长分档),不看气泡可用宽度;用户侧还不会发语音,渲染分支先留好
        ChatVoiceBubble(message = message, onLongPress = onLongPress)
    } else {
        ChatBubble(message = message, maxWidth = maxBubbleWidth, onLongPress = onLongPress)
    }
}

/** 气泡本体:圆角统一 10dp,对方多一圈 1dp 边框把白底从纸色里分出来。 */
@Composable
private fun ChatBubble(message: ChatMessageUi, maxWidth: Dp, onLongPress: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val mine = message.isMine
    val shape = RoundedCornerShape(dimens.bubbleRadius)
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val collapsible = message.content.length > COLLAPSE_THRESHOLD
    val shown = if (collapsible && !expanded) {
        message.content.take(COLLAPSE_THRESHOLD).trimEnd() + ELLIPSIS
    } else {
        message.content
    }
    Column(
        modifier = Modifier
            .testTag(MiniPhoneChatTags.bubble(message.id))
            .widthIn(max = maxWidth)
            .clip(shape)
            .background(if (mine) colors.bubbleSelf else colors.bubbleOther)
            .then(if (mine) Modifier else Modifier.border(dimens.hairline, colors.bubbleOtherBorder, shape))
            // 单击展开/收起(只对折叠过的长文本有意义),长按弹菜单
            .combinedClickable(
                onClick = { if (collapsible) expanded = !expanded },
                onLongClick = onLongPress,
            )
            .padding(horizontal = BubbleHorizontalPadding, vertical = BubbleVerticalPadding),
    ) {
        Text(
            text = shown,
            style = typography.body.copy(color = if (mine) colors.bubbleSelfText else colors.bubbleOtherText),
        )
        if (collapsible) {
            Text(
                text = if (expanded) COLLAPSE_ACTION else EXPAND_ACTION,
                style = typography.caption.copy(
                    color = if (mine) colors.bubbleSelfText.copy(alpha = 0.85f) else colors.accent,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { expanded = !expanded }
                    .testTag(MiniPhoneChatTags.expand(message.id)),
            )
        }
    }
}

/** 系统提示:居中小字,纸色底片把正文和它分开。 */
@Composable
private fun SystemNoticeRow(message: ChatMessageUi) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MiniPhoneChatTags.message(message.id))
            .padding(horizontal = 32.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message.content,
            style = typography.timestamp.copy(color = colors.textSecondary),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(SystemNoticeRadius))
                .background(colors.surface)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** 时间分隔:一行居中的时间文本。 */
@Composable
internal fun ChatTimeDividerRow(at: Long) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MiniPhoneChatTags.timeDivider(at))
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = MiniPhoneTimeFormat.separator(at),
            style = typography.timestamp.copy(color = colors.textSecondary),
        )
    }
}

/** 「对方正在输入…」:对方一侧的气泡里三个跳动的点。 */
@Composable
internal fun ChatTypingRow(peerName: String, peerAvatarUri: String?, showName: Boolean = false) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val shape = RoundedCornerShape(dimens.bubbleRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MiniPhoneChatTags.TYPING)
            .semantics { contentDescription = TYPING_TEXT }
            .padding(horizontal = dimens.pageMargin, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        MiniPhoneChatAvatar(name = peerName, avatarUri = peerAvatarUri, size = dimens.avatarBubble)
        Spacer(Modifier.width(BubbleAvatarGap))
        Column(horizontalAlignment = Alignment.Start) {
            // 群里写名字:同一轮里换一个人打字时,这一行跟着换,不会两句话都归到同一个人头上
            if (showName && peerName.isNotBlank()) {
                Text(
                    text = peerName,
                    style = typography.timestamp.copy(color = colors.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(MiniPhoneChatTags.TYPING_NAME),
                )
                Spacer(Modifier.height(SenderNameGap))
            }
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(colors.bubbleOther)
                    .border(dimens.hairline, colors.bubbleOtherBorder, shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                TypingDots()
            }
        }
    }
}

/**
 * 「对方正在翻找…」:生成中显示在气泡上方的一行。
 *
 * 只写一句人话,不带任何工具痕迹;文本落地或失败后由列表把它整行收起。
 */
@Composable
internal fun ChatActivityRow(kind: MpActivityKind) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MiniPhoneChatTags.ACTIVITY)
            .padding(horizontal = dimens.pageMargin, vertical = ActivityRowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = MiniPhoneActivityText.of(kind),
            style = typography.timestamp.copy(color = colors.textSecondary),
        )
    }
}

/** 三个点依次亮起并上浮,一个周期内走完一遍。 */
@Composable
private fun TypingDots() {
    val ink = MiniPhoneTheme.colors.textSecondary
    val transition = rememberInfiniteTransition(label = TYPING_LABEL)
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = DOT_CYCLE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = TYPING_PHASE_LABEL,
    )
    Canvas(Modifier.size(width = DotsWidth, height = DotsHeight)) {
        val radius = DotRadius.toPx()
        val gap = DotGap.toPx()
        val startX = size.width / 2f - gap
        val centerY = size.height / 2f
        val liftHeight = DotLift.toPx()
        repeat(DOT_COUNT) { index ->
            val progress = (phase + index.toFloat() / DOT_COUNT) % 1f
            val lift = 1f - abs(progress - 0.5f) * 2f
            drawCircle(
                color = ink.copy(alpha = DOT_BASE_ALPHA + (1f - DOT_BASE_ALPHA) * lift),
                radius = radius,
                center = Offset(startX + index * gap, centerY - liftHeight * lift),
            )
        }
    }
}

/** 头像与气泡之间的固定间距。 */
private val BubbleAvatarGap = 8.dp

/** 群里发言人名字与气泡之间的间距。 */
private val SenderNameGap = 2.dp
private val StatusGap = 6.dp
private val SendFailRadius = 4.dp
private val ActivityRowPadding = 6.dp
private val BubbleHorizontalPadding = 12.dp
private val BubbleVerticalPadding = 8.dp
private val SystemNoticeRadius = 6.dp

/** 超过这个字数折叠,折叠后给一个「展开」。 */
private const val COLLAPSE_THRESHOLD: Int = 300

private const val EXPAND_ACTION: String = "展开"
private const val COLLAPSE_ACTION: String = "收起"
private const val ELLIPSIS: String = "…"
private const val SENDING_TEXT: String = "发送中"
private const val SEND_FAILED_MARK: String = "!"
private const val SEND_FAILED_TEXT: String = "未发出,重试"
private const val TYPING_TEXT: String = "对方正在输入…"
private const val TYPING_LABEL: String = "mp-typing"
private const val TYPING_PHASE_LABEL: String = "mp-typing-phase"

private const val DOT_CYCLE_MS: Int = 1_200
private const val DOT_COUNT: Int = 3
private const val DOT_BASE_ALPHA: Float = 0.3f
private val DotRadius = 2.5.dp
private val DotGap = 9.dp
private val DotLift = 2.dp
private val DotsWidth = 32.dp
private val DotsHeight = 14.dp
