package io.zer0.muse.ui

import io.zer0.ai.core.UIMessage

/**
 * 审计修复 (A-16): 收尾消息媒体合并纯函数。
 *
 * 背景(S-01/A-01): Agent Loop 收尾的 finalAssistantMessage 由 GenerationHandler 构造,
 * 只含 content + toolCalls,不含媒体字段;而 execGenerateImage/Video/QrCode 已把媒体
 * 写入同 id 消息的内存副本。若直接覆盖,图片/视频/二维码会丢失。
 *
 * 合并规则:
 *  - finalAssistant 自身有媒体 → 以其为准(收尾消息可能携带新媒体)
 *  - finalAssistant 无媒体 → 回退保留 existingMsg(工具轮写入的媒体)
 *  - existingMsg 为 null → 媒体为空列表(无可合并来源)
 *
 * 抽成纯函数便于单测(A-16 要求),调用点: ChatViewModel Agent Loop 收尾。
 */
internal fun mergeFinalAssistantMedia(finalAssistant: UIMessage, existingMsg: UIMessage?): UIMessage =
    finalAssistant.copy(
        imageUrls = finalAssistant.imageUrls.ifEmpty { existingMsg?.imageUrls ?: emptyList() },
        imageBase64List = finalAssistant.imageBase64List.ifEmpty { existingMsg?.imageBase64List ?: emptyList() },
        videoFileUri = finalAssistant.videoFileUri ?: existingMsg?.videoFileUri,
    )
