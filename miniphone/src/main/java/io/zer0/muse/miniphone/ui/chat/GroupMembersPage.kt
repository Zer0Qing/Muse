package io.zer0.muse.miniphone.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.chat.GROUP_TITLE_MAX_LENGTH
import io.zer0.muse.miniphone.data.chat.MIN_GROUP_MEMBERS_AFTER_REMOVE
import io.zer0.muse.miniphone.data.chat.normalizeGroupTitle
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.shell.GroupPickHeader
import io.zer0.muse.miniphone.ui.shell.GroupPickList
import io.zer0.muse.miniphone.ui.shell.MiniPhoneContactSection
import io.zer0.muse.miniphone.ui.shell.contactSectionsOf
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme

/**
 * 群成员页能做的四件事:改群名、加人、移出某人、解散并退出。
 *
 * 页面本身不认识库,也不判断权限 —— 能进来就说明这是群会话(私信的顶栏根本不带这个入口),
 * 每个动作交给 ViewModel,写完由成员表与会话行的流把界面带回来。
 */
@Immutable
internal class GroupAdminActions(
    val onRename: (title: String) -> Unit = {},
    val onAddMembers: (characterIds: List<String>) -> Unit = {},
    val onRemoveMember: (characterId: String) -> Unit = {},
    val onDisband: () -> Unit = {},
)

/** 成员行上能做的事。 */
internal enum class GroupMemberAction(
    val label: String,
) {
    REMOVE("移出群聊"),
}

/**
 * 成员行上的动作项,空列表表示这一行没有可做的事。
 *
 * 只剩一个人时什么都不给:群里总得留一个能开口的角色,删到空的话这个群就再也说不了话。
 */
internal fun groupMemberActionsFor(memberCount: Int): List<GroupMemberAction> =
    if (memberCount <= MIN_GROUP_MEMBERS_AFTER_REMOVE) emptyList() else listOf(GroupMemberAction.REMOVE)

/** 还能加进来的人:宿主给的全部角色里排掉已经在群里的,没名字的也不列。 */
internal fun addableCharactersOf(characters: List<MpCharacter>, members: List<ChatMemberUi>): List<MpCharacter> {
    val inGroup = members.map { member -> member.characterId }.toSet()
    return characters.filter { character -> character.id !in inGroup && character.name.isNotBlank() }
}

/**
 * 群成员页:群名、成员列表与四个管理动作。
 *
 * 顺序就是库里那份入群先后(建群时按选择顺序),这里不再排一次 ——
 * 同一批人在群成员页与建群页上的顺序一致,才看得出「这群就是刚选的那几个人」。
 *
 * @param members 群成员(只含角色),已按入群先后排好
 * @param title 当前群名;改完之后由上层带着新名字回来重画
 * @param characters 宿主的全部角色,「还能加谁」从这里算
 * @param onBack 返回聊天页;解散之后也要回到消息列表,由调用方决定
 */
@Composable
internal fun GroupMembersPage(
    members: List<ChatMemberUi>,
    title: String,
    characters: List<MpCharacter>,
    onBack: () -> Unit,
    actions: GroupAdminActions = GroupAdminActions(),
    modifier: Modifier = Modifier,
) {
    val colors = MiniPhoneTheme.colors
    var editingTitle by rememberSaveable { mutableStateOf(false) }
    var addingMembers by rememberSaveable { mutableStateOf(false) }
    // 加人页的选中集合:按点击先后,加入顺序因此稳定
    var addSelection by rememberSaveable { mutableStateOf(listOf<String>()) }
    var menuFor by remember(members) { mutableStateOf<String?>(null) }
    var pendingRemove by remember { mutableStateOf<ChatMemberUi?>(null) }
    var confirmingDisband by rememberSaveable { mutableStateOf(false) }
    val removable = groupMemberActionsFor(members.size).isNotEmpty()

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(MiniPhoneChatTags.GROUP_MEMBERS_PAGE)
            .background(colors.background),
    ) {
        Column(Modifier.fillMaxSize()) {
            GroupMembersHeader(count = members.size + 1, onBack = onBack)
            GroupTitleRow(title = title, onClick = { editingTitle = true })
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(items = members, key = { member -> member.characterId }) { member ->
                    Box {
                        GroupMemberRow(
                            member = member,
                            onLongPress = if (removable) {
                                { menuFor = member.characterId }
                            } else {
                                null
                            },
                        )
                        if (menuFor == member.characterId) {
                            MemberRowMenu(
                                onDismiss = { menuFor = null },
                                onRemove = {
                                    menuFor = null
                                    pendingRemove = member
                                },
                            )
                        }
                    }
                }
            }
            MemberAdminFooter(
                onAdd = {
                    addSelection = emptyList()
                    addingMembers = true
                },
                onDisband = { confirmingDisband = true },
            )
        }
        if (editingTitle) {
            GroupTitleEditDialog(
                initialTitle = title,
                onDismiss = { editingTitle = false },
                onSave = { newTitle ->
                    editingTitle = false
                    actions.onRename(newTitle)
                },
            )
        }
        if (addingMembers) {
            GroupAddMembersPage(
                sections = contactSectionsOf(addableCharactersOf(characters, members)),
                selectedIds = addSelection.toSet(),
                onToggle = { characterId ->
                    addSelection = if (characterId in addSelection) {
                        addSelection - characterId
                    } else {
                        addSelection + characterId
                    }
                },
                onBack = {
                    addingMembers = false
                    addSelection = emptyList()
                },
                onDone = {
                    actions.onAddMembers(addSelection)
                    addingMembers = false
                    addSelection = emptyList()
                },
            )
        }
        val removing = pendingRemove
        if (removing != null) {
            MemberRemoveDialog(
                name = removing.name,
                onDismiss = { pendingRemove = null },
                onConfirm = {
                    pendingRemove = null
                    actions.onRemoveMember(removing.characterId)
                },
            )
        }
        if (confirmingDisband) {
            DisbandGroupDialog(
                onDismiss = { confirmingDisband = false },
                onConfirm = {
                    confirmingDisband = false
                    actions.onDisband()
                },
            )
        }
    }
}

/** 顶栏:返回 + 「群成员」+ 人数。 */
@Composable
private fun GroupMembersHeader(count: Int, onBack: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(dimens.pageHeaderHeight)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(GroupMembersButtonSize)
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .testTag(MiniPhoneChatTags.GROUP_MEMBERS_BACK),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiniPhoneIcons.Back,
                    contentDescription = "返回",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(GroupMembersIconSize),
                )
            }
            Text(
                text = GROUP_MEMBERS_TITLE,
                style = typography.title.copy(color = colors.textPrimary),
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = memberCountLabelOf(count),
                style = typography.timestamp.copy(color = colors.textSecondary),
                modifier = Modifier.testTag(MiniPhoneChatTags.GROUP_MEMBERS_COUNT),
            )
        }
        Box(Modifier.fillMaxWidth().height(dimens.hairline).background(colors.divider))
    }
}

/** 群名一行:点一下改名。右侧那句「点击修改」是把入口说出来,不用人猜。 */
@Composable
private fun GroupTitleRow(title: String, onClick: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MiniPhoneChatTags.GROUP_TITLE_ROW)
            .clickable(onClick = onClick)
            .padding(horizontal = dimens.pageMargin, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = GROUP_TITLE_LABEL,
            style = typography.timestamp.copy(color = colors.textSecondary),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = title.ifBlank { UNTITLED_GROUP_NAME },
            style = typography.body.copy(color = colors.textPrimary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .testTag(MiniPhoneChatTags.GROUP_TITLE_VALUE),
        )
        Text(
            text = GROUP_TITLE_EDIT_HINT,
            style = typography.timestamp.copy(color = colors.accent),
        )
    }
}

/** 一行成员:头像 + 名字;能不能移出由 [onLongPress] 有没有给出来决定。 */
@Composable
private fun GroupMemberRow(member: ChatMemberUi, onLongPress: (() -> Unit)?) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MiniPhoneChatTags.member(member.characterId))
            .combinedClickable(
                onClick = { },
                onLongClick = onLongPress,
            )
            .padding(horizontal = dimens.pageMargin, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        MiniPhoneChatAvatar(name = member.name, avatarUri = member.avatarUri, size = dimens.avatarList)
        Spacer(Modifier.width(12.dp))
        Text(
            text = member.name,
            style = typography.body.copy(color = colors.textPrimary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 长按成员弹出的动作浮层:只有一项「移出群聊」。 */
@Composable
private fun MemberRowMenu(onDismiss: () -> Unit, onRemove: () -> Unit) {
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
            text = GroupMemberAction.REMOVE.label,
            style = typography.body.copy(color = colors.unreadDot),
            modifier = Modifier
                .testTag(MiniPhoneChatTags.MEMBER_MENU_REMOVE)
                .clip(shape)
                .background(colors.surface)
                .border(dimens.hairline, colors.divider, shape)
                .clickable {
                    onRemove()
                    onDismiss()
                }
                .padding(horizontal = 18.dp, vertical = 11.dp),
        )
    }
}

/** 页脚两个动作:加人,以及解散这个群。 */
@Composable
private fun MemberAdminFooter(onAdd: () -> Unit, onDisband: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Box(Modifier.fillMaxWidth().height(dimens.hairline).background(colors.divider))
        Text(
            text = ADD_MEMBER_LABEL,
            style = typography.body.copy(color = colors.accent),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(MiniPhoneChatTags.GROUP_ADD_ENTRY)
                .clickable(onClick = onAdd)
                .padding(horizontal = dimens.pageMargin, vertical = 14.dp),
        )
        Box(Modifier.fillMaxWidth().height(dimens.hairline).background(colors.divider))
        Text(
            text = DISBAND_LABEL,
            style = typography.body.copy(color = colors.unreadDot),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(MiniPhoneChatTags.GROUP_DISBAND_ENTRY)
                .clickable(onClick = onDisband)
                .padding(horizontal = dimens.pageMargin, vertical = 14.dp),
        )
    }
}

/** 改群名:一个小弹层,里面一个输入框。空名不保存,超长截断。 */
@Composable
private fun GroupTitleEditDialog(initialTitle: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val shape = RoundedCornerShape(dimens.cardRadius)
    var draft by rememberSaveable(initialTitle) { mutableStateOf(initialTitle) }
    val normalized = normalizeGroupTitle(draft)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .testTag(MiniPhoneChatTags.GROUP_TITLE_EDIT_DIALOG)
                .width(GroupDialogWidth)
                .clip(shape)
                .background(colors.surface)
                .border(dimens.hairline, colors.divider, shape)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(text = GROUP_TITLE_EDIT_TITLE, style = typography.title.copy(color = colors.textPrimary))
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(WindowCorner))
                    .background(colors.background)
                    .border(dimens.hairline, colors.divider, RoundedCornerShape(WindowCorner))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = typography.caption.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accent),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(MiniPhoneChatTags.GROUP_TITLE_EDIT_FIELD),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = titleHintOf(normalized.length),
                style = typography.timestamp.copy(color = colors.textSecondary),
            )
            Spacer(Modifier.height(14.dp))
            DialogActions(
                confirmColor = colors.accent,
                confirmTag = MiniPhoneChatTags.GROUP_TITLE_EDIT_SAVE,
                confirmEnabled = normalized.isNotEmpty(),
                confirmLabel = SAVE_LABEL,
                cancelTag = MiniPhoneChatTags.GROUP_TITLE_EDIT_CANCEL,
                onCancel = onDismiss,
                onConfirm = { onSave(normalized) },
            )
        }
    }
}

/** 移出成员前的确认:说清他说过的话不会跟着消失。 */
@Composable
private fun MemberRemoveDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ConfirmDialog(
        tag = MiniPhoneChatTags.MEMBER_REMOVE_DIALOG,
        title = "把${name}移出群聊?",
        hint = REMOVE_HINT,
        confirmTag = MiniPhoneChatTags.MEMBER_REMOVE_CONFIRM,
        cancelTag = MiniPhoneChatTags.MEMBER_REMOVE_CANCEL,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

/** 解散前的确认:整群都会没,文案里必须写明不可恢复。 */
@Composable
private fun DisbandGroupDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ConfirmDialog(
        tag = MiniPhoneChatTags.GROUP_DISBAND_DIALOG,
        title = DISBAND_TITLE,
        hint = DISBAND_HINT,
        confirmTag = MiniPhoneChatTags.GROUP_DISBAND_CONFIRM,
        cancelTag = MiniPhoneChatTags.GROUP_DISBAND_CANCEL,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

/** 两个确认框只差文案与标签,形状与按钮位置一样。 */
@Composable
private fun ConfirmDialog(
    tag: String,
    title: String,
    hint: String,
    confirmTag: String,
    cancelTag: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val shape = RoundedCornerShape(dimens.cardRadius)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .testTag(tag)
                .width(GroupDialogWidth)
                .clip(shape)
                .background(colors.surface)
                .border(dimens.hairline, colors.divider, shape)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(text = title, style = typography.title.copy(color = colors.textPrimary))
            Spacer(Modifier.height(8.dp))
            Text(text = hint, style = typography.caption.copy(color = colors.textSecondary))
            Spacer(Modifier.height(18.dp))
            DialogActions(
                confirmColor = colors.unreadDot,
                confirmTag = confirmTag,
                confirmEnabled = true,
                confirmLabel = DELETE_LABEL,
                cancelTag = cancelTag,
                onCancel = onDismiss,
                onConfirm = onConfirm,
            )
        }
    }
}

/** 确认框底部两个文字按钮:取消在左,危险动作在右。 */
@Composable
private fun DialogActions(
    confirmColor: Color,
    confirmTag: String,
    confirmEnabled: Boolean,
    confirmLabel: String,
    cancelTag: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text = CANCEL_LABEL,
            style = typography.body.copy(color = colors.textSecondary),
            modifier = Modifier
                .testTag(cancelTag)
                .clip(RoundedCornerShape(DialogActionCorner))
                .clickable(onClick = onCancel)
                .heightIn(min = DialogActionHeight)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = confirmLabel,
            style = typography.body.copy(color = if (confirmEnabled) confirmColor else colors.textSecondary),
            modifier = Modifier
                .testTag(confirmTag)
                .clip(RoundedCornerShape(DialogActionCorner))
                .clickable(enabled = confirmEnabled, onClick = onConfirm)
                .heightIn(min = DialogActionHeight)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/**
 * 添加成员页:复用建群那份多选列表,只列还不在群里的人。
 *
 * 选中一人就能点「完成」—— 加人没有「至少两个」的门槛,门槛在「群至少留一个人」那边。
 */
@Composable
private fun GroupAddMembersPage(
    sections: List<MiniPhoneContactSection>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = MiniPhoneTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(MiniPhoneChatTags.GROUP_ADD_PAGE)
            .background(colors.background),
    ) {
        GroupPickHeader(
            title = ADD_MEMBER_TITLE,
            hint = ADD_MEMBER_HINT_PREFIX + selectedIds.size + " 人",
            canFinish = selectedIds.isNotEmpty(),
            tagPrefix = GROUP_ADD_TAG_PREFIX,
            onBack = onBack,
            onDone = onDone,
        )
        GroupPickList(
            sections = sections,
            selectedIds = selectedIds,
            emptyTitle = ADD_MEMBER_EMPTY_TITLE,
            emptyHint = ADD_MEMBER_EMPTY_HINT,
            onToggle = onToggle,
        )
    }
}

/** 群名输入框下面那行:当前字数与上限。 */
internal fun titleHintOf(length: Int): String = "$length / $GROUP_TITLE_MAX_LENGTH"

private val GroupMembersButtonSize = 40.dp
private val GroupMembersIconSize = 20.dp
private val MenuGap = 6.dp
private val GroupDialogWidth = 280.dp
private val WindowCorner = 8.dp
private val DialogActionCorner = 8.dp
private val DialogActionHeight = 36.dp

private const val GROUP_MEMBERS_TITLE: String = "群成员"
private const val GROUP_TITLE_LABEL: String = "群名"
private const val GROUP_TITLE_EDIT_HINT: String = "点击修改"
private const val GROUP_TITLE_EDIT_TITLE: String = "修改群名"
private const val ADD_MEMBER_LABEL: String = "+ 添加成员"
private const val ADD_MEMBER_TITLE: String = "添加成员"
private const val ADD_MEMBER_HINT_PREFIX: String = "已选 "
private const val ADD_MEMBER_EMPTY_TITLE: String = "没有可以加入的角色"
private const val ADD_MEMBER_EMPTY_HINT: String = "通讯录里的人都已经在群里了"
private const val DISBAND_LABEL: String = "删除并退出"
private const val DISBAND_TITLE: String = "删除并退出?"
private const val DISBAND_HINT: String = "整个群和里面的全部消息都会从这台手机上删掉,无法恢复。"
private const val REMOVE_HINT: String = "移出后他之前说过的话留在群里。"
private const val UNTITLED_GROUP_NAME: String = "群聊"
private const val SAVE_LABEL: String = "保存"
private const val CANCEL_LABEL: String = "取消"
private const val DELETE_LABEL: String = "删除"

/** 添加成员页的标签前缀:与建群页分开,测试点的是哪一页一眼能看出。 */
private const val GROUP_ADD_TAG_PREFIX: String = "mp-group-add-member"
