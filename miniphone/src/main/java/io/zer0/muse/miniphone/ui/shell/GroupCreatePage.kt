package io.zer0.muse.miniphone.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.chat.MIN_GROUP_MEMBERS
import io.zer0.muse.miniphone.data.chat.canCreateGroup
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme

/**
 * 发起群聊页:列出全部角色,勾够两个人才放行。
 *
 * 分组与排序直接用通讯录那份 [MiniPhoneContactSection](首字母分组、`#` 收尾),
 * 在这里另排一套只会让「同一个人在两页的位置不一样」。
 *
 * 状态全从外面来:选中集合与两个回调。页面自己不认识库,也不建群 ——
 * 建群是 [MiniPhoneShellViewModel] 的事,这里只负责把「选了谁」交出去。
 *
 * @param selectedIds 已选中的角色 id;顺序不参与判断,建群顺序由点击先后决定(见外壳)
 * @param onToggle 点一行:没选就选上,选过就取消
 * @param onDone 点「完成」;选不够 [MIN_GROUP_MEMBERS] 个时它不可按,所以不会走到这里
 */
@Composable
internal fun GroupCreatePage(
    sections: List<MiniPhoneContactSection>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiniPhoneTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(MiniPhoneTags.GROUP_CREATE_PAGE)
            .background(colors.background),
    ) {
        GroupPickHeader(
            title = GROUP_CREATE_TITLE,
            hint = selectedHintOf(selectedIds.size),
            canFinish = canCreateGroup(selectedIds.size),
            tagPrefix = GROUP_CREATE_TAG_PREFIX,
            onBack = onBack,
            onDone = onDone,
        )
        GroupPickList(
            sections = sections,
            selectedIds = selectedIds,
            emptyTitle = EMPTY_CONTACTS_TITLE,
            emptyHint = EMPTY_CONTACTS_HINT,
            onToggle = onToggle,
        )
    }
}

/**
 * 选人页的顶栏:返回、标题、完成;标题下面一行写已选几个人。
 *
 * [tagPrefix] 派生三个测试标签(返回 / 完成 / 人数行),两个选人页各有各的前缀,
 * 测试点「完成」时点的是哪一页一目了然。
 */
@Composable
internal fun GroupPickHeader(title: String, hint: String, canFinish: Boolean, tagPrefix: String, onBack: () -> Unit, onDone: () -> Unit) {
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
                    .size(GroupHeaderButtonSize)
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .testTag("$tagPrefix-back"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiniPhoneIcons.Back,
                    contentDescription = "返回",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(GroupHeaderIconSize),
                )
            }
            Text(
                text = title,
                style = typography.title.copy(color = colors.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
            )
            Text(
                text = GROUP_PICK_DONE_LABEL,
                style = typography.caption.copy(
                    color = if (canFinish) colors.accent else colors.textSecondary,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(enabled = canFinish, onClick = onDone)
                    .testTag("$tagPrefix-done")
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Text(
            text = hint,
            style = typography.timestamp.copy(color = colors.textSecondary),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("$tagPrefix-count")
                .padding(horizontal = dimens.pageMargin, vertical = 6.dp),
        )
        Box(Modifier.fillMaxWidth().height(dimens.hairline).background(colors.divider))
    }
}

/**
 * 可选角色的分组列表:首字母分组、整行可点、右侧选中圈;一个人都没有时给空态。
 *
 * 建群与加成员共用这一份 —— 两个入口看到的顺序、分组、选中样式因此完全一致。
 *
 * @param emptyTitle 没有任何可选角色时的标题
 * @param emptyHint 同一屏的第二行说明
 */
@Composable
internal fun GroupPickList(
    sections: List<MiniPhoneContactSection>,
    selectedIds: Set<String>,
    emptyTitle: String,
    emptyHint: String,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sections.isEmpty()) {
        GroupPickEmptyState(title = emptyTitle, hint = emptyHint, modifier = modifier)
        return
    }
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(bottom = 12.dp)) {
        sections.forEach { section ->
            item(key = "group-header-${section.key}") {
                GroupPickSectionHeader(key = section.key)
            }
            items(items = section.contacts, key = { "group-pick-${it.id}" }) { character ->
                GroupPickRow(
                    character = character,
                    selected = character.id in selectedIds,
                    onClick = { onToggle(character.id) },
                )
            }
        }
    }
}

/** 分组标题:与通讯录同一套小字。 */
@Composable
private fun GroupPickSectionHeader(key: String) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Text(
        text = key,
        style = typography.timestamp.copy(color = colors.textSecondary, fontWeight = FontWeight.Medium),
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .padding(start = dimens.pageMargin, end = dimens.pageMargin, top = 10.dp, bottom = 4.dp),
    )
}

/** 一行可选角色:头像 + 名字 + 选择圈。整行可点,不必非要点中那个圈。 */
@Composable
private fun GroupPickRow(character: MpCharacter, selected: Boolean, onClick: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MiniPhoneTags.groupPick(character.id))
            .clickable(onClick = onClick)
            .padding(horizontal = dimens.pageMargin, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniPhoneAvatar(name = character.name, size = dimens.avatarList)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = character.name,
                style = typography.body.copy(color = colors.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val tagline = character.tagline.orEmpty()
            if (tagline.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = tagline,
                    style = typography.caption.copy(color = colors.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        SelectionMark(selected = selected, characterId = character.id)
    }
}

/** 选择圈:选中时填强调色并画一枚对勾,没选时只描一圈淡边。 */
@Composable
private fun SelectionMark(selected: Boolean, characterId: String) {
    val colors = MiniPhoneTheme.colors
    val shape = CircleShape
    Box(
        modifier = Modifier
            .size(SelectionMarkSize)
            .clip(shape)
            .background(if (selected) colors.accent else colors.background)
            .border(1.dp, if (selected) colors.accent else colors.divider, shape)
            .testTag(MiniPhoneTags.groupPickMark(characterId)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = MiniPhoneIcons.Check,
                contentDescription = null,
                tint = colors.onAccent,
                modifier = Modifier.size(SelectionCheckSize),
            )
        }
    }
}

/** 一个可选角色都没有:说清为什么,而不是留一片白。 */
@Composable
private fun GroupPickEmptyState(title: String, hint: String, modifier: Modifier = Modifier) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(MiniPhoneTags.EMPTY_GROUP_CREATE)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = title, style = typography.title.copy(color = colors.textPrimary))
        Spacer(Modifier.height(6.dp))
        Text(text = hint, style = typography.caption.copy(color = colors.textSecondary))
    }
}

/** 已选人数那一行:不到两个人时顺带说明门槛。 */
internal fun selectedHintOf(selectedCount: Int): String = if (selectedCount < MIN_GROUP_MEMBERS) {
    "已选 $selectedCount 人,至少选 $MIN_GROUP_MEMBERS 人"
} else {
    "已选 $selectedCount 人"
}

private const val GROUP_CREATE_TITLE: String = "发起群聊"
private const val GROUP_PICK_DONE_LABEL: String = "完成"
private const val EMPTY_CONTACTS_TITLE: String = "通讯录还是空的"
private const val EMPTY_CONTACTS_HINT: String = "先有角色,才凑得起一个群"

/** 建群页的标签前缀:派生出的值就是对外约定的那几个标签。 */
private const val GROUP_CREATE_TAG_PREFIX: String = "mp-group-create"

private val GroupHeaderButtonSize = 40.dp
private val GroupHeaderIconSize = 20.dp
private val SelectionMarkSize = 22.dp
private val SelectionCheckSize = 14.dp
