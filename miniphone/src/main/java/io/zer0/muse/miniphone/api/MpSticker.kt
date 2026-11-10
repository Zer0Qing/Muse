package io.zer0.muse.miniphone.api

/**
 * 主应用表情包库里的一张表情（只读投影）。
 *
 * 小手机不另存表情文件，[uri] 指向主应用私有目录里的那一张；
 * 主应用删掉或重命名后这里自然就读不到，不做第二份拷贝去追同步。
 */
data class MpSticker(
    val id: String,
    val category: String,
    val uri: String,
    val caption: String? = null,
)
