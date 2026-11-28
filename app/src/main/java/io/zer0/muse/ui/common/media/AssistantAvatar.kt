package io.zer0.muse.ui.common.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantEntity
import kotlin.math.roundToInt

/**
 * v0.25: 助手头像统一渲染组件。
 *
 * 优先级:① 图片头像(avatarImageUrl)→ ② Emoji 头像(avatarEmoji)→ ③ 名称首字母
 *
 * 用法:`AssistantAvatar(assistant = entity, avatarSize = 40.dp)`
 */
internal fun assistantAvatarImageRequest(context: android.content.Context, imageUrl: String, sizePx: Int): ImageRequest {
    val boundedSize = sizePx.coerceAtLeast(1)
    return ImageRequest.Builder(context)
        .data(imageUrl)
        // Role-card avatars can be multi-megapixel originals. Decode only what
        // the chat/list surface can display, otherwise one avatar can exhaust
        // the small Android heap before Compose reports the allocation failure.
        .size(boundedSize, boundedSize)
        .crossfade(true)
        .build()
}

@Composable
fun AssistantAvatar(
    assistant: AssistantEntity,
    modifier: Modifier = Modifier,
    // L-AA3: 参数名 size → avatarSize,避免与 Modifier.size 同名遮蔽导致调用方/内部引用混淆。
    avatarSize: androidx.compose.ui.unit.Dp = 40.dp,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val avatarSizePx = (avatarSize.value * density.density).roundToInt().coerceAtLeast(1)
    // stringResource 需在 @Composable 直接调用位置提取,不能在 semantics{} 内使用。
    val avatarCd = stringResource(R.string.common_avatar_cd, assistant.name)
    when {
        // ① 图片头像(可能是 content:// URI 或文件路径)
        assistant.hasImageAvatar() -> {
            val model = remember(assistant.avatarImageUrl, avatarSizePx) {
                assistantAvatarImageRequest(ctx, assistant.avatarImageUrl, avatarSizePx)
            }
            var loadFailed by remember(assistant.avatarImageUrl) { mutableStateOf(false) }
            if (loadFailed) {
                Box(
                    modifier = modifier
                        .size(avatarSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (assistant.avatarEmoji.isNotBlank()) {
                        Text(
                            text = assistant.avatarEmoji,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    } else {
                        Text(
                            text = assistant.name.firstOrNull()?.toString()?.ifBlank { "A" } ?: "A",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                AsyncImage(
                    model = model,
                    contentDescription = assistant.name,
                    onError = { loadFailed = true },
                    modifier = modifier
                        .size(avatarSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop,
                )
            }
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
                    style = MaterialTheme.typography.titleLarge,
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
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
