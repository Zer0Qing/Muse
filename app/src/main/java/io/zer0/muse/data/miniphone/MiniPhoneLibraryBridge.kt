package io.zer0.muse.data.miniphone

import android.net.Uri
import io.zer0.muse.data.sticker.StickerLibraryRepository
import io.zer0.muse.miniphone.api.MpSticker

/**
 * 小手机对主应用表情包库的只读互通。
 *
 * 表情只有一份，存在主应用这边；小手机不拷第二份文件，只把界面要的字段投影过去。
 * 主应用删改之后这里自然跟着变，不需要一条同步链路去追 —— 少一条链路就少一处会不一致的地方。
 *
 * 世界书不在这里：小手机的生成链路本就通过 `MiniContextBuilder` 吃到主应用的世界书（Lorebook），
 * 再投影一份只读列表既没人读，也会让人以为「看得见的那份」就是在生效的那份。
 */
class MiniPhoneLibraryBridge(
    private val stickers: StickerLibraryRepository,
) {
    /** 文件已不在磁盘上的条目直接跳过：给界面一个点不开的 uri 比少一条更糟。 */
    suspend fun listStickers(): List<MpSticker> = stickers.listStickers().mapNotNull { item ->
        val file = stickers.fileOf(item)
        if (!file.isFile) {
            null
        } else {
            MpSticker(
                id = item.id,
                category = item.category,
                uri = Uri.fromFile(file).toString(),
                caption = item.caption,
            )
        }
    }
}
