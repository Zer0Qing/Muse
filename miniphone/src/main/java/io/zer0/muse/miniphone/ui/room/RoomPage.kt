package io.zer0.muse.miniphone.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.miniphone.R
import io.zer0.muse.miniphone.data.room.MpRoomAnchor
import io.zer0.muse.miniphone.data.room.MpRoomState
import io.zer0.muse.miniphone.data.room.MpRoomStore
import io.zer0.muse.miniphone.data.room.MpRooms
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.shell.MiniPhoneTags
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.launch

/**
 * 38: 小房间的 2D 视图。
 *
 * 家具是预置锚点、人是可移动的图钉：角色要能讲「我在窗边」，靠的就是这套有名字的有限位置。
 * 点家具即把自己挪过去，位置写进模块那一份 DataStore，关掉小手机再进来还在原处。
 */
@Composable
internal fun RoomPage(store: MpRoomStore, occupantNames: Map<String, String>, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    val state by produceState(initialValue = MpRoomState(MpRooms.DEFAULT_ROOM_ID, emptyMap()), store) {
        store.stateFlow.collect { value = it }
    }
    val room = MpRooms.find(state.roomId)
    val roomScope = rememberCoroutineScope()
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(MiniPhoneTags.ROOM_PAGE)
            .background(colors.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = MiniPhoneIcons.Back,
                contentDescription = stringResource(R.string.mp_room_back),
                tint = colors.textPrimary,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .padding(6.dp)
                    .size(22.dp)
                    .testTag(MiniPhoneTags.ROOM_BACK),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.mp_room_title),
                style = typography.body.copy(color = colors.textPrimary),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(room.labelRes),
                style = typography.body.copy(color = colors.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RoomSwitcher(
            selectedRoomId = room.id,
            onSwitch = { roomId -> roomScope.launch { store.switchRoom(roomId) } },
        )
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.verticalGradient(listOf(colors.wallpaperTop, colors.wallpaperBottom)),
                )
                .border(1.dp, colors.divider, RoundedCornerShape(18.dp))
                .testTag(MiniPhoneTags.ROOM_CANVAS),
        ) {
            val floorWidth = maxWidth
            val floorHeight = maxHeight
            room.anchors.forEach { anchor ->
                Furniture(
                    anchor = anchor,
                    occupied = state.occupants.values.any { it == anchor.id },
                    isLocalUserHere = state.occupants[MpRoomState.LOCAL_USER] == anchor.id,
                    onClick = { roomScope.launch { store.moveTo(anchor.id) } },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(
                            x = floorWidth * anchor.xFraction - 34.dp,
                            y = floorHeight * anchor.yFraction - 22.dp,
                        ),
                )
            }
            state.occupants.forEach { (participant, anchorId) ->
                val anchor = room.anchors.firstOrNull { it.id == anchorId } ?: return@forEach
                Figure(
                    name = occupantNames[participant] ?: participant,
                    isLocalUser = participant == MpRoomState.LOCAL_USER,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(
                            x = floorWidth * anchor.xFraction - 16.dp,
                            y = floorHeight * anchor.yFraction - 46.dp,
                        ),
                )
            }
        }
        Text(
            text = stringResource(R.string.mp_room_hint),
            style = typography.body.copy(color = colors.textSecondary),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** 家具块：名字就是角色会提到的位置词，所以它必须看得见。 */
@Composable
private fun Furniture(
    anchor: MpRoomAnchor,
    occupied: Boolean,
    isLocalUserHere: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .widthIn(min = 68.dp)
            .clip(shape)
            .background(colors.surface.copy(alpha = if (occupied) 0.95f else 0.7f))
            .border(
                if (isLocalUserHere) 2.dp else 1.dp,
                if (isLocalUserHere) colors.accent else colors.divider,
                shape,
            )
            .clickable(onClick = onClick)
            .testTag(MiniPhoneTags.roomAnchor(anchor.id))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(anchor.labelRes),
            style = typography.body.copy(
                color = colors.textPrimary,
                fontSize = typography.body.fontSize * 0.85f,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 人形图钉：名字首字进圆，站在所属家具的正上方。 */
@Composable
private fun Figure(name: String, isLocalUser: Boolean, modifier: Modifier = Modifier) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (isLocalUser) colors.accent else colors.bubbleOther)
            .testTag(MiniPhoneTags.roomFigure(if (isLocalUser) MpRoomState.LOCAL_USER else name)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1),
            style = typography.body.copy(
                color = if (isLocalUser) colors.onAccent else colors.bubbleOtherText,
                fontSize = typography.body.fontSize * 0.8f,
            ),
            maxLines = 1,
        )
    }
}

/** 房间切换条:房间不多,横向一行放得下,也方便以后加楼层时只改这里。 */
@Composable
private fun RoomSwitcher(selectedRoomId: String, onSwitch: (String) -> Unit) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MpRooms.all.forEach { candidate ->
            val selected = candidate.id == selectedRoomId
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) colors.accent else colors.surface)
                    .border(1.dp, if (selected) colors.accent else colors.divider, RoundedCornerShape(14.dp))
                    .clickable { onSwitch(candidate.id) }
                    .testTag(MiniPhoneTags.roomSwitcher(candidate.id))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = stringResource(candidate.labelRes),
                    style = typography.body.copy(
                        color = if (selected) colors.onAccent else colors.textSecondary,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}
