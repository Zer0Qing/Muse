package io.zer0.muse.miniphone.ui.misc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import io.zer0.muse.miniphone.data.db.entity.MpWardrobeItemEntity
import io.zer0.muse.miniphone.data.misc.MpWardrobeStore
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 衣橱换装页：选角色、选同类，填名字与地址即可添衣；列表里能一键换装、删除。
 *
 * 纯渲染页面，状态与动作都由 Flow + 回调驱动，不碰 Room。
 * 「穿上」只负责把动作交出去，同 kind 只留一件穿着由门面保证，界面不做二次判断。
 */
@Composable
internal fun MiniPhoneWardrobeScreen(
    itemsFor: (String) -> Flow<List<MpWardrobeItemEntity>>,
    characters: List<MpCharacter>,
    onAdd: (characterId: String, name: String, kind: String, uri: String) -> Unit,
    onEquip: (characterId: String, id: String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    val colors = MiniPhoneTheme.colors
    var selectedCharacter by remember { mutableStateOf<String?>(characters.firstOrNull()?.id) }
    var selectedKind by remember { mutableStateOf(MpWardrobeStore.KIND_TOP) }
    var nameDraft by remember { mutableStateOf("") }
    var uriDraft by remember { mutableStateOf("") }
    val cid = selectedCharacter
    val rows by (if (cid != null) itemsFor(cid) else flowOf(emptyList())).collectAsStateWithLifecycle(emptyList())

    Column(modifier = Modifier.fillMaxSize().testTag(MiniPhoneMiscTags.Wardrobe.PAGE).background(colors.background)) {
        MiscHeader(stringResource(R.string.mp_wardrobe_title), MiniPhoneMiscTags.Wardrobe.BACK, onBack)
        MiscChipRow(
            options = characters.map { it.name to it.id },
            selected = selectedCharacter,
            tagFor = { MiniPhoneMiscTags.Wardrobe.character(it) },
        ) { selectedCharacter = it }
        Spacer(Modifier.size(4.dp))
        MiscChipRow(
            options = WARDROBE_KINDS.map { wardrobeKindLabel(it) to it },
            selected = selectedKind,
            tagFor = { MiniPhoneMiscTags.Wardrobe.kind(it) },
        ) { selectedKind = it }
        WardrobeAddPanel(nameDraft, uriDraft, cid, onAdd = { name, uri ->
            nameDraft = ""
            uriDraft = ""
            onAdd(cid.orEmpty(), name, selectedKind, uri)
        }, onNameChange = { nameDraft = it }, onUriChange = { uriDraft = it })
        if (rows.isEmpty()) {
            MiscEmpty(stringResource(R.string.mp_wardrobe_empty), MiniPhoneMiscTags.Wardrobe.EMPTY)
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).testTag(MiniPhoneMiscTags.Wardrobe.LIST),
                contentPadding = PaddingValues(horizontal = MiniPhoneTheme.dimens.pageMargin, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { it.id }) { item ->
                    WardrobeItemRow(item, onEquip = { onEquip(item.characterId, item.id) }, onDelete = onDelete)
                }
            }
        }
    }
}

@Composable
private fun WardrobeAddPanel(
    name: String,
    uri: String,
    characterId: String?,
    onAdd: (name: String, uri: String) -> Unit,
    onNameChange: (String) -> Unit,
    onUriChange: (String) -> Unit,
) {
    val colors = MiniPhoneTheme.colors
    val canAdd = characterId != null && name.isNotBlank()
    Column(Modifier.fillMaxWidth().padding(horizontal = MiniPhoneTheme.dimens.pageMargin, vertical = 4.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            placeholder = { Text(stringResource(R.string.mp_wardrobe_name_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(MiniPhoneMiscTags.Wardrobe.NAME_INPUT),
        )
        Spacer(Modifier.size(6.dp))
        OutlinedTextField(
            value = uri,
            onValueChange = onUriChange,
            placeholder = { Text(stringResource(R.string.mp_wardrobe_uri_hint)) },
            singleLine = true,
            keyboardActions = KeyboardActions(onDone = { if (canAdd) onAdd(name, uri) }),
            modifier = Modifier.fillMaxWidth().testTag(MiniPhoneMiscTags.Wardrobe.URI_INPUT),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = stringResource(R.string.mp_wardrobe_add),
            style = MiniPhoneTheme.typography.body.copy(color = if (canAdd) colors.accent else colors.textSecondary),
            modifier = Modifier.clip(CircleShape).clickable(enabled = canAdd) { onAdd(name, uri) }
                .padding(horizontal = 12.dp, vertical = 8.dp).testTag(MiniPhoneMiscTags.Wardrobe.ADD),
        )
    }
}

@Composable
private fun WardrobeItemRow(item: MpWardrobeItemEntity, onEquip: () -> Unit, onDelete: (String) -> Unit) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Column(
        modifier = Modifier.fillMaxWidth().testTag(MiniPhoneMiscTags.Wardrobe.item(item.id))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.name,
                style = typography.body.copy(color = colors.textPrimary),
                modifier = Modifier.weight(1f),
            )
            if (item.equipped != 0) {
                Text(
                    text = stringResource(R.string.mp_wardrobe_equipped),
                    style = typography.caption.copy(color = colors.accent),
                )
            }
        }
        Text(
            text = wardrobeKindLabel(item.kind),
            style = typography.caption.copy(color = colors.textSecondary),
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            MiscAction(
                label = stringResource(R.string.mp_wardrobe_equip),
                tag = MiniPhoneMiscTags.Wardrobe.equip(item.id),
                enabled = item.equipped == 0,
            ) { onEquip() }
            MiscAction(
                label = stringResource(R.string.mp_memo_delete),
                tag = MiniPhoneMiscTags.Wardrobe.delete(item.id),
                enabled = true,
            ) { onDelete(item.id) }
        }
    }
}

@Composable
private fun wardrobeKindLabel(kind: String): String = when (kind) {
    MpWardrobeStore.KIND_TOP -> stringResource(R.string.mp_wardrobe_kind_top)
    MpWardrobeStore.KIND_BOTTOM -> stringResource(R.string.mp_wardrobe_kind_bottom)
    MpWardrobeStore.KIND_DRESS -> stringResource(R.string.mp_wardrobe_kind_dress)
    MpWardrobeStore.KIND_SHOES -> stringResource(R.string.mp_wardrobe_kind_shoes)
    MpWardrobeStore.KIND_ACCESSORY -> stringResource(R.string.mp_wardrobe_kind_accessory)
    else -> kind
}

private val WARDROBE_KINDS = listOf(
    MpWardrobeStore.KIND_TOP,
    MpWardrobeStore.KIND_BOTTOM,
    MpWardrobeStore.KIND_DRESS,
    MpWardrobeStore.KIND_SHOES,
    MpWardrobeStore.KIND_ACCESSORY,
)

/** 页头：返回箭头 + 标题，四个 misc 页面共用。 */
@Composable
internal fun MiscHeader(title: String, backTag: String, onBack: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MiniPhoneIcons.Back,
            contentDescription = stringResource(R.string.mp_room_back),
            tint = colors.textPrimary,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(6.dp).size(22.dp).testTag(backTag),
        )
        Spacer(Modifier.size(8.dp))
        Text(text = title, style = MiniPhoneTheme.typography.body.copy(color = colors.textPrimary))
    }
}

/** 一排可选标签，选中项高亮；tagFor 决定每一项的稳定标签。 */
@Composable
internal fun MiscChipRow(
    options: List<Pair<String, String>>,
    selected: String?,
    tagFor: (String) -> String = { it },
    onSelect: (String) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = MiniPhoneTheme.dimens.pageMargin),
    ) {
        options.forEach { (label, value) ->
            val colors = MiniPhoneTheme.colors
            Text(
                text = label,
                style = MiniPhoneTheme.typography.caption.copy(
                    color = if (selected == value) colors.accent else colors.textSecondary,
                ),
                modifier = Modifier.clip(CircleShape).clickable { onSelect(value) }
                    .padding(horizontal = 10.dp, vertical = 6.dp).testTag(tagFor(value)),
            )
        }
    }
}

/** 单个可点击动作（穿上 / 删除 / 领取），enabled 为假时置灰不响应。 */
@Composable
internal fun MiscAction(label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    Text(
        text = label,
        style = MiniPhoneTheme.typography.caption.copy(
            color = if (enabled) colors.accent else colors.textSecondary,
        ),
        modifier = Modifier.clip(CircleShape).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp).testTag(tag),
    )
}

/** 空态占位：整页居中一句话，带自己的定位标签。 */
@Composable
internal fun MiscEmpty(text: String, tag: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = MiniPhoneTheme.typography.caption, color = MiniPhoneTheme.colors.textSecondary)
    }
}
