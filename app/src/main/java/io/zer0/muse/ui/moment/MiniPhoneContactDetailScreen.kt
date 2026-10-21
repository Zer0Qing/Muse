package io.zer0.muse.ui.moment

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.relationship.AssistantRelationshipEntity
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * v2.6.9: 小手机内的角色详情页。
 *
 * 之前通讯录点进去直接开聊，没有任何「这个人是谁」的中间页。
 * 角色扮演用户需要看到：头像、名字、一句话简介、你们的关系与亲密度，
 * 以及最近一次互动是什么时候——这些是「关系感」的具体承载。
 *
 * 刻意保持 IM 风格（大头像 + 信息行），而不是设置页那种表单感。
 */
@Composable
fun MiniPhoneContactDetailScreen(
    assistant: AssistantEntity?,
    relationship: AssistantRelationshipEntity?,
    onBack: () -> Unit,
    onStartChat: () -> Unit,
    onEditRelationship: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // ── 顶栏 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = MusePaddings.screen, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            io.zer0.muse.ui.common.form.MuseTactileButton(
                icon = MuseIcons.arrowLeft,
                onClick = onBack,
                contentDescription = stringResource(R.string.action_back),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.miniphone_contact_detail_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MusePaddings.screen),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            // ── 头像与名字 ──
            ContactHeader(assistant = assistant)

            // ── 关系信息 ──
            RelationshipInfoCard(relationship = relationship)

            // ── 动作 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MuseCapsuleButton(
                    text = stringResource(R.string.miniphone_contact_start_chat),
                    onClick = onStartChat,
                    variant = IosCapsuleButtonVariant.Primary,
                    fillWidth = false,
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.miniphone_contact_edit_relationship),
                    onClick = onEditRelationship,
                    variant = IosCapsuleButtonVariant.Secondary,
                    fillWidth = false,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun affinityText(affinity: Int): String = when {
    affinity >= 80 -> stringResource(R.string.settings_relationship_affinity_close)
    affinity >= 50 -> stringResource(R.string.settings_relationship_affinity_familiar)
    affinity >= 20 -> stringResource(R.string.settings_relationship_affinity_known)
    else -> stringResource(R.string.settings_relationship_affinity_new)
}

@Composable
private fun lastInteractionText(timestamp: Long): String {
    if (timestamp <= 0L) return stringResource(R.string.miniphone_contact_never)
    val days = ((System.currentTimeMillis() - timestamp) / 86_400_000L).toInt()
    return when {
        days <= 0 -> stringResource(R.string.miniphone_time_just_now)
        days == 1 -> stringResource(R.string.miniphone_time_yesterday)
        else -> stringResource(R.string.miniphone_time_days_ago, days)
    }
}

/**
 * v2.6.9: 头像与名字区（从主函数拆出，主函数原本 133 行）.
 */
@Composable
private fun ContactHeader(assistant: AssistantEntity?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            val avatar = assistant?.avatarImageUrl
            if (!avatar.isNullOrBlank()) {
                AsyncImage(
                    model = avatar,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = (assistant?.name ?: "?").take(1),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = assistant?.name ?: stringResource(R.string.assistant_repo_default_name),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val summary = assistant?.summary?.takeIf { it.isNotBlank() }
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

/**
 * v2.6.9: 关系信息卡（从主函数拆出）.
 */
@Composable
private fun RelationshipInfoCard(relationship: AssistantRelationshipEntity?) {
    MuseSurface(shape = MuseShapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
            DetailRow(
                label = stringResource(R.string.settings_relationship_title),
                value = relationship?.relationLabel?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.settings_relationship_unset),
            )
            Spacer(Modifier.height(10.dp))
            DetailRow(
                label = stringResource(R.string.miniphone_contact_affinity),
                value = affinityText(relationship?.affinity ?: 0),
            )
            Spacer(Modifier.height(10.dp))
            DetailRow(
                label = stringResource(R.string.miniphone_contact_last_interaction),
                value = lastInteractionText(relationship?.lastInteractionAt ?: 0L),
            )
        }
    }
}
