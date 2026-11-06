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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.zer0.muse.miniphone.R
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.data.db.MINI_PHONE_DB_VERSION
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
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
    val context = LocalContext.current
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
                    text = profile.nickname.ifBlank { stringResource(R.string.mp_me_empty_nickname) },
                    style = typography.title.copy(color = colors.textPrimary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = profile.signature.orEmpty().ifBlank { stringResource(R.string.mp_me_empty_signature) },
                    style = typography.caption.copy(color = colors.textSecondary),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(SectionGap))
        MeEntryRow(
            title = stringResource(R.string.mp_me_my_moments),
            description = stringResource(R.string.mp_me_my_moments_desc),
            tag = MiniPhoneTags.MY_MOMENTS_ROW,
            onClick = actions.onOpenMyMoments,
        )
        Spacer(Modifier.height(SectionGap))
        MeSettingRow(
            title = stringResource(R.string.mp_me_auto_moments),
            description = stringResource(R.string.mp_me_auto_moments_desc),
            checked = autoMomentEnabled,
            onCheckedChange = actions.onToggleAutoMoment,
            switchTag = MiniPhoneTags.AUTO_MOMENT_SWITCH,
        )
        Spacer(Modifier.height(SectionGap))
        MeSettingRow(
            title = stringResource(R.string.mp_me_proactive),
            description = stringResource(R.string.mp_me_proactive_desc),
            checked = chatProactiveEnabled,
            onCheckedChange = actions.onToggleChatProactive,
            switchTag = MiniPhoneTags.CHAT_PROACTIVE_SWITCH,
        )
        Spacer(Modifier.height(SectionGap))
        MeEntryRow(
            title = stringResource(R.string.mp_me_clear_chats),
            description = stringResource(R.string.mp_me_clear_chats_desc),
            tag = MiniPhoneTags.CLEAR_CHATS_ROW,
            titleColor = colors.unreadDot,
            onClick = { confirmingClear = true },
        )
        Spacer(Modifier.height(SectionGap))
        MeEntryRow(
            title = stringResource(R.string.mp_me_version),
            description = miniPhoneVersionLabel(context, dbVersion = MINI_PHONE_DB_VERSION, appVersion = appVersionName),
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
internal fun miniPhoneVersionLabel(context: android.content.Context, dbVersion: Int, appVersion: String): String {
    val module = context.getString(R.string.mp_me_version_module, dbVersion)
    val app = appVersion.trim()
    if (app.isEmpty()) return module
    return context.getString(R.string.mp_me_version_label, dbVersion, app)
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
            Icon(
                imageVector = MiniPhoneIcons.Chevron,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(18.dp),
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
                text = stringResource(R.string.mp_me_clear_dialog_title),
                style = typography.title.copy(color = colors.textPrimary),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.mp_me_clear_dialog_hint),
                style = typography.caption.copy(color = colors.textSecondary),
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = stringResource(R.string.mp_cancel),
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
                    text = stringResource(R.string.mp_clear),
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


private val SectionGap = 12.dp
private val DialogWidth = 280.dp
private val DialogActionHeight = 36.dp
