package io.zer0.muse.miniphone.ui.chat

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import io.zer0.muse.miniphone.ui.shell.MiniPhoneAvatar

/**
 * 聊天页头像:有 uri 就加载真图,没有或加载失败回落到首字圆章。
 *
 * 回落不是兜底文案,而是本模块自有的头像形制 —— 通讯录与消息列表用的就是它,
 * 同一屏里不会出现两种「没有头像」的样子。
 */
@Composable
internal fun MiniPhoneChatAvatar(name: String, avatarUri: String?, size: Dp, modifier: Modifier = Modifier) {
    if (avatarUri.isNullOrBlank()) {
        MiniPhoneAvatar(name = name, size = size, modifier = modifier)
        return
    }
    // 加载失败后不再重试:同一 uri 反复失败只会闪,不会成功
    var failed by remember(avatarUri) { mutableStateOf(false) }
    if (failed) {
        MiniPhoneAvatar(name = name, size = size, modifier = modifier)
        return
    }
    AsyncImage(
        model = avatarUri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        onError = { failed = true },
        modifier = modifier
            .size(size)
            .clip(CircleShape),
    )
}
