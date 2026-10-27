package io.zer0.muse.channel

import io.zer0.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * QQ 入站消息落库。
 *
 * 图片消息先下载转 base64 再入库；下载失败降级成占位文本。
 * 单独成文件是为了不让接收器承担过多职责。
 */
internal fun recordQqInbound(scope: CoroutineScope, source: ChannelInbox.Source, data: JsonObject, text: String) {
    val attachment = (data["attachments"] as? JsonArray)?.firstOrNull() as? JsonObject
    val imageUrl = ChannelImageFetcher.qqImageUrl(attachment)
    if (imageUrl == null) {
        ChannelInbox.record(source, text, "")
        return
    }
    scope.launch {
        val base64 = ChannelImageFetcher.fetchQq(imageUrl)
        if (base64 != null) {
            ChannelInbox.record(
                source = source,
                text = text,
                rawPayload = "",
                media = ChannelInbox.Media(kind = "image", base64 = base64),
            )
        } else {
            Logger.w("QqReceiver", "QQ 图片获取失败(from=${source.from})")
            ChannelInbox.record(source, "[图片(未能获取)]", "")
        }
    }
}
