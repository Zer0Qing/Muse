package io.zer0.muse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowLeft
import compose.icons.tablericons.Send
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.MuseShapes

/** B7-07: 输入栏全屏编辑页,从 [InputBar] 拆出以降低主文件行数。 */
@Composable
internal fun MuseExpandedInputEditor(
    text: String,
    assistantName: String = "Muse",
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
            .systemBarsPadding()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(MusePaddings.screen)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = TablerIcons.ArrowLeft,
                        contentDescription = stringResource(R.string.action_cancel),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = stringResource(R.string.chat_expand_input_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.size(MuseIconSizes.touchTarget))
            }
            Spacer(Modifier.height(MusePaddings.itemGap))
            MuseTextField(
                value = text,
                onValueChange = { if (it.length <= INPUT_TEXT_MAX_LENGTH) onTextChanged(it) },
                modifier = Modifier.fillMaxWidth().weight(1f),
                minLines = 10,
                maxLines = 100,
                placeholder = {
                    Text(stringResource(R.string.chat_placeholder_send, assistantName.ifBlank { "Muse" }))
                },
            )
            Spacer(Modifier.height(MusePaddings.itemGap))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    onClick = {
                        if (text.isNotBlank()) {
                            onSend()
                            onClose()
                        }
                    },
                    enabled = text.isNotBlank(),
                    shape = MuseShapes.pill,
                    color = if (text.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = MusePaddings.inputHorizontal, vertical = MusePaddings.itemGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = TablerIcons.Send,
                            contentDescription = null,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                            tint = if (text.isNotBlank()) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            },
                        )
                        Spacer(Modifier.width(MusePaddings.labelVerticalGap))
                        Text(
                            text = stringResource(R.string.action_send),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (text.isNotBlank()) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            },
                        )
                    }
                }
            }
        }
    }
}
