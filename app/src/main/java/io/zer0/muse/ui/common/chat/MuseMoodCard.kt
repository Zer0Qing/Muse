package io.zer0.muse.ui.common.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.markdown.MoodSectionParser
import io.zer0.muse.ui.theme.LocalLiquidGlass
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseBorderWidth
import io.zer0.muse.ui.theme.MuseBubbleStyles
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.glassBorder
import io.zer0.muse.ui.theme.glassEdgeHighlight
import io.zer0.muse.ui.theme.glassFakeSurfaceColor

/**
 * 统一的心情/腹稿折叠卡片（单聊 / Agent / 群聊共用）。
 *
 * 折叠态展示「心情 · 首行摘要」，展开态按 [MoodSectionParser] 分节展示；
 * 流式阶段自动保持折叠，避免尚未闭合的标签造成整块跳动。
 */
@Composable
fun MuseMoodCard(mood: String, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, isStreaming: Boolean = false) {
    val showExpanded = if (isStreaming) false else expanded
    val moodSections = remember(mood) { MoodSectionParser.parse(mood) }
    val moodLabel = stringResource(R.string.chat_mood_label)
    val collapsedTitle = remember(moodSections, moodLabel) {
        val summary = moodSections
            .firstOrNull { !it.title.isNullOrBlank() || it.lines.isNotEmpty() }
            ?.let { section ->
                section.lines.firstOrNull() ?: section.title.orEmpty()
            }
            ?.replace('\n', ' ')
            ?.trim()
            .orEmpty()
        if (summary.isBlank()) moodLabel else "$moodLabel · $summary"
    }

    val isMono = MuseBubbleStyles.isMonoBubbleTheme()
    val glassConfig = LocalLiquidGlass.current
    val useFakeGlass = glassConfig.enabled

    val baseContainerColor = if (isMono) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = MuseAlpha.mutedSurface)
    }
    val contentColor = if (isMono) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    val accentColor = if (isMono) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.primary
    }
    val borderColor = if (isMono) {
        MaterialTheme.colorScheme.outlineVariant
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = MuseAlpha.statusTint)
    }

    val surfaceModifier = when {
        !useFakeGlass -> modifier
        isMono ->
            modifier
                .clip(MuseShapes.medium)
                .glassEdgeHighlight(MuseShapes.medium, glassConfig)
                .glassBorder(MuseShapes.medium, glassConfig)
        else ->
            modifier
                .clip(MuseShapes.medium)
                .background(glassFakeSurfaceColor(baseContainerColor, glassConfig))
                .glassEdgeHighlight(MuseShapes.medium, glassConfig)
                .glassBorder(MuseShapes.medium, glassConfig)
    }

    Surface(
        color = if (useFakeGlass && !isMono) Color.Transparent else baseContainerColor,
        shape = MuseShapes.medium,
        tonalElevation = MuseElevation.none,
        border = if (useFakeGlass) null else BorderStroke(MuseBorderWidth.hairline, borderColor),
        modifier = surfaceModifier,
    ) {
        Column(modifier = Modifier.padding(MusePaddings.bubbleInner)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(vertical = MusePaddings.tinyGap),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (showExpanded) moodLabel else collapsedTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (showExpanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                    contentDescription = if (showExpanded) {
                        stringResource(R.string.chat_mood_collapse_cd)
                    } else {
                        stringResource(R.string.chat_mood_expand_cd)
                    },
                    tint = contentColor.copy(alpha = MuseAlpha.subtleBorder),
                    modifier = Modifier.size(MuseIconSizes.iconTiny),
                )
            }
            AnimatedVisibility(
                visible = showExpanded,
                enter = MuseMotion.expandFadeEnter(),
                exit = MuseMotion.expandFadeExit(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(MusePaddings.tinyGap))
                    moodSections.forEachIndexed { sectionIndex, section ->
                        if (sectionIndex > 0) {
                            Spacer(Modifier.height(MusePaddings.tinyGap))
                        }
                        section.title?.let { title ->
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelSmall,
                                color = accentColor,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        section.lines.forEach { rawLine ->
                            if (rawLine.isBlank()) return@forEach
                            val line = MoodSectionParser.toLine(rawLine)
                            Text(
                                text = if (line.bullet) "·  ${line.text}" else line.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = contentColor.copy(alpha = MuseAlpha.titleMuted),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}
