package io.zer0.muse.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantEntity

/**
 * v0.25: 助手头像统一渲染组件。
 *
 * 优先级:① 图片头像(avatarImageUrl)→ ② Emoji 头像(avatarEmoji)→ ③ 名称首字母
 *
 * 用法:`AssistantAvatar(assistant = entity, avatarSize = 40.dp)`
 */
@Composable
fun AssistantAvatar(
    assistant: AssistantEntity,
    modifier: Modifier = Modifier,
    // L-AA3: 参数名 size → avatarSize,避免与 Modifier.size 同名遮蔽导致调用方/内部引用混淆。
    avatarSize: androidx.compose.ui.unit.Dp = 40.dp,
) {
    val ctx = LocalContext.current
    // stringResource 需在 @Composable 直接调用位置提取,不能在 semantics{} 内使用。
    val avatarCd = stringResource(R.string.common_avatar_cd, assistant.name)
    when {
        // ① 图片头像(可能是 content:// URI 或文件路径)
        assistant.hasImageAvatar() -> {
            // v0.36 性能优化:缓存 ImageRequest,避免每次重组都重建 Coil 请求对象。
            val model = remember(assistant.avatarImageUrl) {
                ImageRequest.Builder(ctx)
                    .data(assistant.avatarImageUrl)
                    .crossfade(true)
                    .build()
            }
            // M-AA1: 用 SubcomposeAsyncImage 替代 AsyncImage,通过 error slot 处理加载失败。
            // 旧实现加载失败时显示空白 surfaceVariant 背景;现在 fallback 到首字母,
            // 保证头像始终有可辨识内容(对标 iOS 联系人头像加载失败回退首字母)。
            SubcomposeAsyncImage(
                model = model,
                contentDescription = assistant.name,
                modifier = modifier
                    .size(avatarSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop,
                error = {
                    Text(
                        text = assistant.name.firstOrNull()?.toString()?.ifBlank { "A" } ?: "A",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
        // ② Emoji 头像
        assistant.avatarEmoji.isNotBlank() -> {
            Box(
                modifier = modifier
                    .size(avatarSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    // M-AA2: 加 contentDescription 语义(mergeDescendants 合并子 Text),
                    // TalkBack 朗读为"X 的头像",旧实现仅朗读 Emoji 字符,语义不明确。
                    .semantics(mergeDescendants = true) {
                        contentDescription = avatarCd
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = assistant.avatarEmoji,
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        }
        // ③ 名称首字母
        else -> {
            Box(
                modifier = modifier
                    .size(avatarSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    // M-AA2: 加 contentDescription 语义(mergeDescendants 合并子 Text),
                    // TalkBack 朗读为"X 的头像"。
                    .semantics(mergeDescendants = true) {
                        contentDescription = avatarCd
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = assistant.name.firstOrNull()?.toString()?.ifBlank { "A" } ?: "A",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
