package io.zer0.muse.ui.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.ChatGradient
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.SettingsSliderRow
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.theme.MuseCornerRadius
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.util.launchSafely
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

private const val BG_MAX_SIDE_PX = 1920
private const val BG_JPEG_QUALITY = 82
private const val TYPOGRAPHY_SAVE_DEBOUNCE_MS = 400L

/** Chat typography & bubble live preview section rendered under Appearance settings. */
@Composable
internal fun ChatTypographySection(prefs: ChatPreferences, update: ((ChatPreferences) -> ChatPreferences) -> Unit) {
    var fontScaleDraft by remember { mutableStateOf(prefs.messageFontScale) }
    LaunchedEffect(prefs.messageFontScale) {
        if (prefs.messageFontScale != fontScaleDraft) fontScaleDraft = prefs.messageFontScale
    }
    LaunchedEffect(fontScaleDraft) {
        if (fontScaleDraft != prefs.messageFontScale) {
            delay(TYPOGRAPHY_SAVE_DEBOUNCE_MS)
            update { it.copy(messageFontScale = fontScaleDraft) }
        }
    }
    var letterSpacingDraft by remember { mutableStateOf(prefs.messageLetterSpacingEm) }
    LaunchedEffect(prefs.messageLetterSpacingEm) {
        if (prefs.messageLetterSpacingEm != letterSpacingDraft) letterSpacingDraft = prefs.messageLetterSpacingEm
    }
    LaunchedEffect(letterSpacingDraft) {
        if (letterSpacingDraft != prefs.messageLetterSpacingEm) {
            delay(TYPOGRAPHY_SAVE_DEBOUNCE_MS)
            update { it.copy(messageLetterSpacingEm = letterSpacingDraft) }
        }
    }
    CardGroup(title = { Text(stringResource(R.string.settings_chat_typography_section)) }) {
        content(key = "bubble_preview") {
            BubblePreviewPanel(prefs = prefs)
        }
        content(key = "font_scale_slider") {
            SettingsSliderRow(
                title = stringResource(R.string.settings_chat_message_font_size),
                subtitle = stringResource(R.string.settings_chat_message_font_size_subtitle),
                value = fontScaleDraft,
                valueRange = 0.85f..1.25f,
                steps = 7,
                valueText = "${(fontScaleDraft * 100).toInt()}%",
                onValueChange = { fontScaleDraft = it },
            )
        }
        content(key = "letter_spacing_slider") {
            SettingsSliderRow(
                title = stringResource(R.string.settings_chat_letter_spacing),
                subtitle = stringResource(R.string.settings_chat_letter_spacing_subtitle),
                value = letterSpacingDraft,
                valueRange = -0.02f..0.08f,
                steps = 9,
                valueText = "%.2f em".format(letterSpacingDraft),
                onValueChange = { letterSpacingDraft = it },
            )
        }
    }
}

/** Chat background (light wallpaper, dark wallpaper, gradient preset) section rendered under Appearance settings. */
@Composable
internal fun ChatBackgroundSection(settings: SettingsRepository, scope: CoroutineScope) {
    val chatBackground by settings.chatBackgroundFlow.collectAsStateWithLifecycle(initialValue = null)
    val darkBg by settings.chatBackgroundDarkFlow.collectAsStateWithLifecycle(initialValue = null)
    val chatGradient by settings.chatGradientFlow.collectAsStateWithLifecycle(initialValue = null)
    val bgContext = LocalContext.current

    val bgLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri ->
        uri?.let { picked ->
            scope.launch {
                val savedPath = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = bgContext.contentResolver.openInputStream(picked)?.use { it.readBytes() }
                            ?: return@runCatching null
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ?: return@runCatching null
                        val scale = minOf(1f, BG_MAX_SIDE_PX.toFloat() / maxOf(bmp.width, bmp.height))
                        val scaled = if (scale < 1f) {
                            Bitmap.createScaledBitmap(
                                bmp,
                                (bmp.width * scale).toInt(),
                                (bmp.height * scale).toInt(),
                                true,
                            )
                        } else {
                            bmp
                        }
                        val out = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, BG_JPEG_QUALITY, out)
                        if (scaled !== bmp) scaled.recycle()
                        bmp.recycle()
                        val target = File(bgContext.filesDir, "chat_background.jpg")
                        FileOutputStream(target).use { fos -> fos.write(out.toByteArray()) }
                        out.close()
                        "file://" + target.absolutePath
                    }.onFailure { e ->
                        Logger.w(
                            "ChatSettings",
                            "Failed to set chat background uri=$picked: ${e.message ?: e.javaClass.simpleName}",
                            e,
                        )
                    }.getOrNull()
                }
                if (savedPath != null) {
                    settings.saveChatBackground(savedPath)
                    MuseToast.show(bgContext.getString(R.string.settings_chat_background_saved))
                } else {
                    MuseToast.show(bgContext.getString(R.string.settings_chat_background_failed))
                }
            }
        }
    }

    val darkBgLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri ->
        uri?.let { picked ->
            scope.launch {
                val savedPath = withContext(Dispatchers.IO) {
                    ChatBackgroundStore.saveFromUri(bgContext, picked, "chat_background_dark")
                }
                if (savedPath != null) {
                    settings.saveChatBackgroundDark(savedPath)
                    MuseToast.show(bgContext.getString(R.string.settings_chat_background_saved))
                } else {
                    MuseToast.show(bgContext.getString(R.string.chat_background_save_failed))
                }
            }
        }
    }

    CardGroup(title = { Text(stringResource(R.string.settings_chat_background_section)) }) {
        item(
            key = "bg_light",
            onClick = { bgLauncher.launchSafely("image/*", bgContext) },
            leadingContent = {
                Icon(
                    imageVector = MuseIcons.image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            },
            headlineContent = { Text(stringResource(R.string.settings_chat_background_title)) },
            supportingContent = { Text(stringResource(R.string.settings_chat_background_subtitle)) },
            trailingContent = {
                BackgroundActionLabel(
                    hasValue = chatBackground != null,
                    onClear = {
                        scope.launch {
                            settings.saveChatBackground(null)
                            ChatBackgroundStore.deleteSlot(bgContext, null)
                        }
                    },
                )
            },
        )
        item(
            key = "bg_dark",
            onClick = { darkBgLauncher.launchSafely("image/*", bgContext) },
            leadingContent = {
                Icon(
                    imageVector = MuseIcons.image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            },
            headlineContent = { Text(stringResource(R.string.settings_chat_background_dark_title)) },
            supportingContent = { Text(stringResource(R.string.settings_chat_background_dark_subtitle)) },
            trailingContent = {
                BackgroundActionLabel(
                    hasValue = darkBg != null,
                    onClear = {
                        scope.launch {
                            settings.saveChatBackgroundDark(null)
                            ChatBackgroundStore.deleteSlot(bgContext, "chat_background_dark")
                        }
                    },
                )
            },
        )
        content(key = "bg_gradient") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MusePaddings.cardInner),
                verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.md),
                ) {
                    Icon(
                        imageVector = MuseIcons.palette,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_chat_gradient_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.settings_chat_gradient_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (chatGradient != null) {
                        ClearActionBox(
                            onClick = { scope.launch { settings.saveChatGradient(null) } },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                ) {
                    PRESET_CHAT_GRADIENTS.forEach { (start, end) ->
                        val selected = chatGradient?.startColorArgb == start && chatGradient?.endColorArgb == end
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(MuseCornerRadius.SMALL.dp))
                                .clickable {
                                    scope.launch { settings.saveChatGradient(ChatGradient(start, end)) }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(MuseCornerRadius.SMALL.dp))
                                    .background(
                                        Brush.linearGradient(
                                            listOf(Color(start.toInt()), Color(end.toInt())),
                                        ),
                                    )
                                    .border(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        },
                                        shape = RoundedCornerShape(MuseCornerRadius.SMALL.dp),
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackgroundActionLabel(hasValue: Boolean, onClear: () -> Unit) {
    if (hasValue) {
        ClearActionBox(onClick = onClear)
    } else {
        Text(
            text = stringResource(R.string.settings_chat_background_choose),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ClearActionBox(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(MuseCornerRadius.SEMI_LARGE.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = MuseSpacing.sm, vertical = MuseSpacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.action_clear),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
