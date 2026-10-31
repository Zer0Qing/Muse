package io.zer0.muse.miniphone.ui.moments

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.zer0.muse.miniphone.data.moment.MOMENT_MAX_IMAGES
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme

/**
 * 发布页:整屏接管内容区,上半写正文,下半是已选图片的九宫格。
 *
 * 选图走系统选择器,一次最多九张;「发布」在两样都空时点不动 —— 空白动态没有意义。
 * 选中的 [Uri] 只在发布页活着:发布成功由 ViewModel 复制进私有目录并落库,
 * 失败(什么都没写)时这一页也不会留下半截草稿。
 */
@Composable
internal fun MomentPublishScreen(onClose: () -> Unit, onPublish: (String, List<Uri>, Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    var text by rememberSaveable { mutableStateOf("") }
    var picked by remember { mutableStateOf(emptyList<Uri>()) }
    var onlySelf by rememberSaveable { mutableStateOf(false) }
    val pickImages = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MOMENT_MAX_IMAGES),
    ) { uris -> picked = (picked + uris).distinct().take(MOMENT_MAX_IMAGES) }
    val canPublish = text.isNotBlank() || picked.isNotEmpty()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .testTag(MiniPhoneMomentsTags.PUBLISH_SCREEN),
    ) {
        PublishTopBar(
            canPublish = canPublish,
            onClose = onClose,
            onConfirm = { onPublish(text, picked, onlySelf) },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = dimens.pageMargin, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PublishTextField(text = text, onTextChange = { text = it })
            PickedImageGrid(
                picked = picked,
                onPick = {
                    pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
            if (picked.isNotEmpty()) {
                Text(
                    text = "${picked.size}/$MOMENT_MAX_IMAGES",
                    style = typography.timestamp.copy(color = colors.textSecondary),
                )
            }
            VisibilitySwitch(onlySelf = onlySelf, onToggle = { onlySelf = !onlySelf })
            Spacer(Modifier.weight(1f))
        }
    }
}

/** 顶栏:左关闭、中间标题、右发布。 */
@Composable
private fun PublishTopBar(canPublish: Boolean, onClose: () -> Unit, onConfirm: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(dimens.pageHeaderHeight)
                .padding(horizontal = dimens.pageMargin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(TopBarButton)
                    .clip(RoundedCornerShape(TopBarCorner))
                    .clickable(onClick = onClose)
                    .testTag(MiniPhoneMomentsTags.PUBLISH_CLOSE),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = CLOSE_LABEL, style = typography.caption.copy(color = colors.textSecondary))
            }
            Text(
                text = TITLE,
                style = typography.title.copy(color = colors.textPrimary),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(TopBarCorner))
                    .clickable(enabled = canPublish, onClick = onConfirm)
                    .testTag(MiniPhoneMomentsTags.PUBLISH_CONFIRM)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = CONFIRM_LABEL,
                    style = typography.caption.copy(
                        color = if (canPublish) colors.accent else colors.textSecondary,
                    ),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(dimens.hairline).background(colors.divider))
    }
}

/** 正文输入:最小高度一屏四分之一,写完再多也不顶着图片区。 */
@Composable
private fun PublishTextField(text: String, onTextChange: (String) -> Unit) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TextAreaHeight)
            .clip(RoundedCornerShape(TextAreaCorner))
            .background(colors.surface)
            .border(MiniPhoneTheme.dimens.hairline, colors.divider, RoundedCornerShape(TextAreaCorner))
            .padding(12.dp),
    ) {
        if (text.isEmpty()) {
            Text(
                text = PLACEHOLDER,
                style = typography.body.copy(color = colors.textSecondary),
            )
        }
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            textStyle = typography.body.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier
                .fillMaxSize()
                .testTag(MiniPhoneMomentsTags.PUBLISH_INPUT),
        )
    }
}

/** 已选图片:三列方格,末尾一格是选图入口,选满九张后不再显示。 */
@Composable
private fun PickedImageGrid(picked: List<Uri>, onPick: () -> Unit) {
    val cellCount = picked.size + if (picked.size < MOMENT_MAX_IMAGES) 1 else 0
    val rows = (0 until cellCount).chunked(PICKED_COLUMNS)
    Column(verticalArrangement = Arrangement.spacedBy(PickedGap)) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(PickedGap)) {
                for (index in row) {
                    if (index < picked.size) PickedThumb(uri = picked[index]) else PickerTile(onPick = onPick)
                }
            }
        }
    }
}

/** 已选的一张图。 */
@Composable
private fun PickedThumb(uri: Uri) {
    val colors = MiniPhoneTheme.colors
    Box(
        modifier = Modifier
            .size(PickedCell)
            .clip(RoundedCornerShape(PickedCorner))
            .background(colors.divider),
    ) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 选图入口:一枚相框图标占一格。 */
@Composable
private fun PickerTile(onPick: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    Box(
        modifier = Modifier
            .size(PickedCell)
            .clip(RoundedCornerShape(PickedCorner))
            .background(colors.surface)
            .border(MiniPhoneTheme.dimens.hairline, colors.divider, RoundedCornerShape(PickedCorner))
            .clickable(onClick = onPick)
            .testTag(MiniPhoneMomentsTags.PUBLISH_PICK),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MiniPhoneIcons.Image,
            contentDescription = PICK_LABEL,
            tint = colors.textSecondary,
            modifier = Modifier.size(PickIconSize),
        )
    }
}

/**
 * 可见范围开关:只给「仅自己可见」一个选项,默认关(空串 = 所有人可见)。
 *
 * 角色白名单留到以后,本阶段不把可见对象拍成一个多选列表。
 */
@Composable
private fun VisibilitySwitch(onlySelf: Boolean, onToggle: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(VisibilityCorner))
            .background(colors.surface)
            .clickable(onClick = onToggle)
            .testTag(MiniPhoneMomentsTags.PUBLISH_VISIBLE_SELF)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = ONLY_SELF_LABEL,
            style = typography.caption.copy(color = colors.textPrimary),
            modifier = Modifier.weight(1f),
        )
        VisibilityTrack(on = onlySelf)
    }
}

/** 开关键道:开时圆点靠右、底色换强调色。 */
@Composable
private fun VisibilityTrack(on: Boolean) {
    val colors = MiniPhoneTheme.colors
    val trackShape = RoundedCornerShape(TrackHeight)
    Box(
        modifier = Modifier
            .size(TrackWidth, TrackHeight)
            .clip(trackShape)
            .background(if (on) colors.accent else colors.divider)
            .padding(TrackPadding),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(TrackKnob).clip(RoundedCornerShape(TrackKnob)).background(colors.surface))
    }
}

private val TopBarButton = 44.dp
private val TopBarCorner = 8.dp
private val TextAreaHeight = 160.dp
private val TextAreaCorner = 12.dp
private val PickedCell = 76.dp
private val PickedGap = 6.dp
private val PickedCorner = 8.dp
private val PickIconSize = 22.dp
private val VisibilityCorner = 10.dp
private val TrackWidth = 38.dp
private val TrackHeight = 22.dp
private val TrackPadding = 3.dp
private val TrackKnob = 16.dp

private const val TITLE: String = "写动态"
private const val CLOSE_LABEL: String = "取消"
private const val CONFIRM_LABEL: String = "发布"
private const val PLACEHOLDER: String = "这一刻的想法……"
private const val PICK_LABEL: String = "选择图片"
private const val ONLY_SELF_LABEL: String = "仅自己可见"

/** 选图网格的列数。 */
private const val PICKED_COLUMNS: Int = 3
