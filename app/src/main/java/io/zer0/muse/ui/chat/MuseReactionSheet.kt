package io.zer0.muse.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import compose.icons.TablerIcons
import compose.icons.tablericons.Flame
import compose.icons.tablericons.Heart
import compose.icons.tablericons.MoodSad
import compose.icons.tablericons.MoodSmile
import compose.icons.tablericons.Star
import compose.icons.tablericons.ThumbUp
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings

// E4 (前端专项 H8): 消息表情回应选择器。
// 项目 UI 约定禁止 emoji 字符(见 AuditLogPage/settings 注释),预设回应采用
// Material 语义图标表达(Facebook 式 reaction,无文字标签)。
// reaction 字段存预设 key("like"/"love"/...),UI 层经 reactionIcon/reactionLabelRes 映射。
internal val REACTION_PRESETS: List<Pair<String, ImageVector>> = listOf(
    "like" to TablerIcons.ThumbUp,
    "love" to TablerIcons.Heart,
    "laugh" to TablerIcons.MoodSmile,
    "wow" to TablerIcons.Star,
    "sad" to TablerIcons.MoodSad,
    "angry" to TablerIcons.Flame,
)

/** reaction key → 内容描述字符串资源;未知 key 返回 null(渲染层跳过)。 */
internal fun reactionLabelRes(key: String): Int? = when (key) {
    "like" -> R.string.chat_reaction_like
    "love" -> R.string.chat_reaction_love
    "laugh" -> R.string.chat_reaction_laugh
    "wow" -> R.string.chat_reaction_wow
    "sad" -> R.string.chat_reaction_sad
    "angry" -> R.string.chat_reaction_angry
    else -> null
}

/** reaction key → 图标;未知 key 返回 null。 */
internal fun reactionIcon(key: String): ImageVector? =
    REACTION_PRESETS.firstOrNull { it.first == key }?.second

/** 表情回应选择面板 — 预设回应横排 + 清除(已有回应时)。 */
@Composable
internal fun MuseReactionSheet(
    current: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    MuseBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = MusePaddings.screen),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.chat_reaction_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(MusePaddings.contentGap))
            Row(horizontalArrangement = Arrangement.spacedBy(MusePaddings.screen)) {
                REACTION_PRESETS.forEach { (key, icon) ->
                    ReactionButton(
                        key = key,
                        icon = icon,
                        selected = key == current,
                        onClick = { onSelect(key) },
                    )
                }
            }
            if (current != null) {
                Spacer(Modifier.height(MusePaddings.tightGap))
                TextButton(onClick = { onSelect(null) }) {
                    Text(stringResource(R.string.chat_reaction_clear))
                }
            }
        }
    }
}

/** 单个回应按钮 — 选中态高亮描边;内容描述用本地化标签(TalkBack)。 */
@Composable
private fun ReactionButton(
    key: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // 标签在 Composable 上下文取值(semantics lambda 内不能调用 stringResource)
    val label = reactionLabelRes(key)?.let { stringResource(it) }
    Icon(
        imageVector = icon,
        contentDescription = label,
        tint = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .size(MuseIconSizes.touchTarget)
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = CircleShape,
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = CircleShape,
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
    )
}
