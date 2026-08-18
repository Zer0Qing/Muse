package io.zer0.muse.ui.quicknotes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import kotlinx.coroutines.delay

/**
 * Muse 内全局快速记录胶囊。
 *
 * 右侧边缘的小把手支持点击或向左滑动,唤起一个不带遮罩的侧滑输入面板。
 * 这是应用内实现,不申请悬浮窗权限,不会抢占其他 App 的系统手势。
 */
@Composable
internal fun QuickCaptureEdgeOverlay(
    enabled: Boolean,
    viewModel: QuickNotesViewModel,
    modifier: Modifier = Modifier,
) {
    if (!enabled) return

    var expanded by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var panelOffsetX by rememberSaveable { mutableIntStateOf(0) }
    var panelOffsetY by rememberSaveable { mutableIntStateOf(0) }
    val focusRequester = FocusRequester()
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(expanded) {
        if (expanded) {
            delay(90)
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    fun closePanel() {
        keyboard?.hide()
        expanded = false
    }

    fun saveDraft() {
        val text = draft.trim()
        if (text.isBlank()) return
        val tags = extractHashTags(text)
        viewModel.add(
            title = deriveTitle(text, tags),
            content = text,
            tags = tags,
        )
        draft = ""
        closePanel()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        if (!expanded) {
            QuickCaptureEdgeHandle(
                onOpen = { expanded = true },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .widthIn(max = 360.dp)
                    .heightIn(min = 240.dp, max = 390.dp)
                    .offset { IntOffset(panelOffsetX, panelOffsetY) }
                    .shadow(
                        elevation = 18.dp,
                        shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
                    ),
                shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(MusePaddings.cardInner)
                        .padding(vertical = MusePaddings.contentGap),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    panelOffsetX = (panelOffsetX + dragAmount.x.toInt()).coerceIn(-600, 0)
                                    panelOffsetY = (panelOffsetY + dragAmount.y.toInt()).coerceIn(-900, 900)
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Lightbulb,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(MuseIconSizes.icon),
                        )
                        Spacer(Modifier.size(MusePaddings.tightGap))
                        Text(
                            text = stringResource(R.string.quick_notes_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = ::closePanel) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_close),
                            )
                        }
                    }
                    MuseTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = {
                            Text(stringResource(R.string.quick_notes_input_hint))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .focusRequester(focusRequester),
                        minLines = 4,
                        maxLines = 8,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Default,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { saveDraft() },
                        ),
                    )
                    Text(
                        text = stringResource(R.string.quick_notes_tag_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Surface(
                        onClick = ::saveDraft,
                        enabled = draft.isNotBlank(),
                        shape = MuseShapes.pill,
                        color = if (draft.isNotBlank()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (draft.isNotBlank()) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 13.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(MuseIconSizes.iconSmall),
                            )
                            Spacer(Modifier.size(MusePaddings.tightGap))
                            Text(
                                text = stringResource(R.string.quick_notes_save),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickCaptureEdgeHandle(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragDistance = 0f
    Box(
        modifier = modifier
            .widthIn(min = 22.dp, max = 28.dp)
            .height(92.dp)
            .clip(RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
            .clickable(onClick = onOpen)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, amount -> dragDistance += amount },
                    onDragEnd = {
                        if (dragDistance < -24f) onOpen()
                        dragDistance = 0f
                    },
                    onDragCancel = { dragDistance = 0f },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.ChevronLeft,
            contentDescription = stringResource(R.string.quick_notes_title),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(MuseIconSizes.iconSmall),
        )
    }
}
