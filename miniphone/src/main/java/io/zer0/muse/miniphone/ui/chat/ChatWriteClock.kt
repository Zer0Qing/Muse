package io.zer0.muse.miniphone.ui.chat

import java.util.UUID

/**
 * 落库时用的两个可替换来源:现在几点、下一条消息的 id。
 *
 * 合成一个值是为了让 ViewModel 的参数表不至于膨胀 —— 这两个东西永远一起被替换,
 * 测试用假时钟钉死 `createdAt`、用自增 id 让断言可预期。
 */
data class ChatWriteClock(
    val now: () -> Long = System::currentTimeMillis,
    val newId: () -> String = { UUID.randomUUID().toString() },
)
