package io.zer0.muse.ui.memory

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MuseConstellationPalette

/** 标准记忆分类展示顺序：记忆中心与星座共用，保证颜色与文案完全一致。 */
internal val MEMORY_STANDARD_CATEGORIES = listOf(
    "identity",
    "preference",
    "relationship",
    "event",
    "goal",
    "medical",
    "general",
)

internal fun memoryCategoryStringRes(category: String): Int = when (category.lowercase()) {
    "preference" -> R.string.memory_graph_category_preference
    "identity" -> R.string.memory_graph_category_identity
    "event" -> R.string.memory_graph_category_event
    "relationship" -> R.string.memory_graph_category_relationship
    "goal" -> R.string.memory_graph_category_goal
    "medical" -> R.string.memory_graph_category_medical
    else -> R.string.memory_graph_category_general
}

@Composable
internal fun memoryCategoryLabel(category: String): String = stringResource(memoryCategoryStringRes(category))

internal fun memoryCategoryColor(category: String, palette: MuseConstellationPalette): Color = when (category.lowercase()) {
    "identity" -> palette.identity
    "preference" -> palette.preference
    "relationship" -> palette.relationship
    "event" -> palette.event
    "goal" -> palette.goal
    "medical" -> palette.medical
    else -> palette.general
}

internal fun memoryRelationStringRes(relationType: String): Int = when (relationType.lowercase()) {
    "causes" -> R.string.memory_graph_link_type_causes
    "part_of" -> R.string.memory_graph_link_type_part_of
    "contradicts" -> R.string.memory_graph_link_type_contradicts
    else -> R.string.memory_graph_link_type_related
}

internal fun memoryRelationColor(relationType: String, palette: MuseConstellationPalette): Color = when (relationType.lowercase()) {
    "causes" -> palette.edgeCauses
    "part_of" -> palette.edgePartOf
    "contradicts" -> palette.edgeContradicts
    else -> palette.edgeRelated
}
