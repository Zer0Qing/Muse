package io.zer0.muse.miniphone.ui.chat

import android.net.Uri
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.data.media.MpImageStore

/**
 * 图片消息的存盘与落库。
 *
 * [MpImageStore] 只负责把字节写进私有目录;「写成功了才落一条消息、写不进就跳过这一张」
 * 这层编排在这里,调用方不用自己判断拿到的路径是否可信。
 *
 * @param threadId 本会话 id:用户选的图默认落回这里
 */
internal class ChatMediaWriter(
    private val imageStore: MpImageStore,
    private val writer: ChatMessageWriter,
    private val threadId: String,
) {

    /**
     * 用户从相册选的图:存一张落一条,顺序与选择一致。
     *
     * 存不下(读不到或写不进磁盘)的那张直接跳过,不影响其余几张。
     */
    suspend fun storePickedImages(uris: List<Uri>, characterId: String) {
        uris.mapNotNull { uri -> imageStore.import(uri) }
            .forEach { path -> store(threadId, characterId, MpActorType.USER, path) }
    }

    /**
     * 落一条角色发的图片:文件已在盘上,只写消息行。
     *
     * 本阶段没有任何地方真的生成图片,这个方法留给后续角色发图与测试用;
     * 调用方自己负责保证 [localFile] 已经存在。
     */
    suspend fun storeCharacterImage(threadId: String, characterId: String, localFile: String) {
        store(threadId, characterId, MpActorType.CHARACTER, localFile)
    }

    private suspend fun store(threadId: String, characterId: String, sender: MpActorType, mediaUri: String) {
        writer.append(
            ChatOutgoingMessage(
                threadId = threadId,
                characterId = characterId,
                sender = sender,
                type = MpMessageType.IMAGE,
                content = IMAGE_PREVIEW_TEXT,
                mediaUri = mediaUri,
            ),
        )
    }

    companion object {
        /** 图片消息的正文:库里不存图,只存这句预览文案,列表与未读预览都用它。 */
        const val IMAGE_PREVIEW_TEXT: String = "[图片]"
    }
}
