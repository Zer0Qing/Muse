package io.zer0.muse.miniphone.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.data.db.MINI_PHONE_DB_VERSION
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme

/**
 * 我页:本机用户的名片 + 入口 + 手机设置。
 *
 * 昵称与签名来自 [MpProfile];任一项缺失就给一句兜底文案,不留空白行。
 * 入口三项:我的朋友圈(只看自己发的)、清空聊天记录(二次确认)、版本信息。
 * 设置区两项:「自动发朋友圈」与「角色主动私信」,开关状态由外壳状态带进来。
 *
 * @param appVersionName 宿主版本名;空串表示宿主没报,版本信息里就只写模块自己的版本
 */
@Composable
internal fun MePage(
    profile: MpProfile,
    appVersionName: String = "",
    autoMomentEnabled: Boolean = true,
    chatProactiveEnabled: Boolean = false,
    actions: MePageActions = MePageActions(),
    modifier: Modifier = Modifier,
) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val shape = RoundedCornerShape(dimens.cardRadius)
    // 清空是覆盖全部会话的动作,点一下就弹确认
    var confirmingClear by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            // 卡片一多就装不下一屏:整页可滚,底部两行(清空 / 版本)才不会被裁掉
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.pageMargin, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.surface)
                .border(dimens.hairline, colors.divider, shape)
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniPhoneAvatar(name = profile.nickname, size = dimens.avatarLarge)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = profile.nickname.ifBlank { EMPTY_NICKNAME },
                    style = typography.title.copy(color = colors.textPrimary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = profile.signature.orEmpty().ifBlank { EMPTY_SIGNATURE },
                    style = typography.caption.copy(color = colors.textSecondary),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(SectionGap))
        MeEntryRow(
            title = MY_MOMENTS_TITLE,
            description = MY_MOMENTS_DESCRIPTION,
            tag = MiniPhoneTags.MY_MOMENTS_ROW,
            onClick = actions.onOpenMyMoments,
        )
        Spacer(Modifier.height(SectionGap))
        MeSettingRow(
            title = AUTO_MOMENT_TITLE,
            description = AUTO_MOMENT_DESCRIPTION,
            checked = autoMomentEnabled,
            onCheckedChange = actions.onToggleAutoMoment,
            switchTag = MiniPhoneTags.AUTO_MOMENT_SWITCH,
        )
        Spacer(Modifier.height(SectionGap))
        MeSettingRow(
            title = CHAT_PROACTIVE_TITLE,
            description = CHAT_PROACTIVE_DESCRIPTION,
            checked = chatProactiveEnabled,
            onCheckedChange = actions.onToggleChatProactive,
            switchTag = MiniPhoneTags.CHAT_PROACTIVE_SWITCH,
        )
        Spacer(Modifier.height(SectionGap))
        MeEntryRow(
            title = CLEAR_CHATS_TITLE,
            description = CLEAR_CHATS_DESCRIPTION,
            tag = MiniPhoneTags.CLEAR_CHATS_ROW,
            titleColor = colors.unreadDot,
            onClick = { confirmingClear = true },
        )
        Spacer(Modifier.height(SectionGap))
        MeEntryRow(
            title = VERSION_TITLE,
            description = miniPhoneVersionLabel(dbVersion = MINI_PHONE_DB_VERSION, appVersion = appVersionName),
            tag = MiniPhoneTags.VERSION_ROW,
            labelTag = MiniPhoneTags.VERSION_LABEL,
            onClick = null,
        )
    }
    if (confirmingClear) {
        ClearChatsConfirmDialog(
            onDismiss = { confirmingClear = false },
            onConfirm = {
                actions.onClearConversations()
                confirmingClear = false
            },
        )
    }
}

/**
 * 版本信息那一行显示什么。
 *
 * 模块自己的库版本写在常量里,宿主版本是运行时的值 —— 两边都不在这里写死,
 * 宿主没报版本时只留前半段,不补一个假版本号。
 */
internal fun miniPhoneVersionLabel(dbVersion: Int, appVersion: String): String {
    val module = "小手机数据 v$dbVersion"
    val app = appVersion.trim()
    return if (app.isEmpty()) module else "$module / 应用 $app"
}

/** 设置区的一行:左边标题 + 说明,右边开关。两张设置卡长得一样,只是文案与回调不同。 */
@Composable
private fun MeSettingRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, switchTag: String) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val shape = RoundedCornerShape(dimens.cardRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(dimens.hairline, colors.divider, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = typography.title.copy(color = colors.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                style = typography.caption.copy(color = colors.textSecondary),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(switchTag),
        )
    }
}

/**
 * 入口行:标题 + 说明,右侧一个指向下一页的箭头。
 *
 * [onClick] 为 null 时整行不可点(版本信息就是纯展示),右侧也不再画箭头 ——
 * 画了箭头点不动,比不画更让人困惑。
 */
@Composable
private fun MeEntryRow(
    title: String,
    description: String,
    tag: String,
    onClick: (() -> Unit)?,
    titleColor: Color? = null,
    labelTag: String? = null,
) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val shape = RoundedCornerShape(dimens.cardRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(dimens.hairline, colors.divider, shape)
            .let { base -> if (onClick != null) base.clickable(onClick = onClick) else base }
            .testTag(tag)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = typography.title.copy(color = titleColor ?: colors.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                style = typography.caption.copy(color = colors.textSecondary),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = if (labelTag != null) Modifier.testTag(labelTag) else Modifier,
            )
        }
        if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = CHEVRON_TEXT,
                style = typography.body.copy(color = colors.textSecondary),
            )
        }
    }
}

/** 清空聊天记录前的确认:说清删的是什么、不能恢复、朋友圈不受影响。 */
@Composable
private fun ClearChatsConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val shape = RoundedCornerShape(dimens.cardRadius)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .testTag(MiniPhoneTags.CLEAR_CHATS_DIALOG)
                .width(DialogWidth)
                .clip(shape)
                .background(colors.surface)
                .border(dimens.hairline, colors.divider, shape)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                text = CLEAR_CHATS_DIALOG_TITLE,
                style = typography.title.copy(color = colors.textPrimary),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = CLEAR_CHATS_DIALOG_HINT,
                style = typography.caption.copy(color = colors.textSecondary),
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = CANCEL_LABEL,
                    style = typography.body.copy(color = colors.textSecondary),
                    modifier = Modifier
                        .testTag(MiniPhoneTags.CLEAR_CHATS_CANCEL)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onDismiss)
                        .heightIn(min = DialogActionHeight)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = CLEAR_LABEL,
                    style = typography.body.copy(color = colors.unreadDot),
                    modifier = Modifier
                        .testTag(MiniPhoneTags.CLEAR_CHATS_CONFIRM)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onConfirm)
                        .heightIn(min = DialogActionHeight)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private const val EMPTY_NICKNAME: String = "还没有昵称"
private const val EMPTY_SIGNATURE: String = "这个人还没写签名"
private const val AUTO_MOMENT_TITLE: String = "自动发朋友圈"
private const val AUTO_MOMENT_DESCRIPTION: String = "关掉之后角色不再自己在后台发动态"
private const val CHAT_PROACTIVE_TITLE: String = "角色主动私信"
private const val CHAT_PROACTIVE_DESCRIPTION: String = "角色会偶尔主动找你说话，只在 08:00–22:00 之间，默认关闭"
private const val MY_MOMENTS_TITLE: String = "我的朋友圈"
private const val MY_MOMENTS_DESCRIPTION: String = "只看自己发过的动态,也可以接着发"
private const val CLEAR_CHATS_TITLE: String = "清空聊天记录"
private const val CLEAR_CHATS_DESCRIPTION: String = "删掉全部会话与消息,朋友圈不受影响"
private const val VERSION_TITLE: String = "版本信息"
private const val CLEAR_CHATS_DIALOG_TITLE: String = "清空聊天记录?"
private const val CLEAR_CHATS_DIALOG_HINT: String = "所有会话和消息会被删除,无法恢复。朋友圈动态不会动。"
private const val CANCEL_LABEL: String = "取消"
private const val CLEAR_LABEL: String = "清空"
private const val CHEVRON_TEXT: String = "›"

private val SectionGap = 12.dp
private val DialogWidth = 280.dp
private val DialogActionHeight = 36.dp
