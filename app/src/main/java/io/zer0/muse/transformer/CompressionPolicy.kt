package io.zer0.muse.transformer

/**
 * 上下文自动压缩的阈值策略 —— 单一真源。
 *
 * 审查发现（X-5）：压缩阈值此前散落在多处硬编码：
 *  - [ContextCompressTransformer] 的 companion 默认值 20 / 15
 *  - `ChatStreamCoordinator` 组装 extras 时写字面量 `if (longMemoryCompression) 10 else 20` / `8 else 15`
 *  - `ChannelAutoReply` 的 COMPRESS_THRESHOLD = 40（渠道侧独立子系统）
 *  - `ChatViewModel.manualCompress` 传 threshold = 1（手动强制触发）
 *
 * 四者语义**并不相同**，不能盲目合并：
 *  - 本对象只统一"**主对话自动压缩**"这一语义（Transformer 默认值与主对话取值必须是同一套）；
 *  - 手动压缩的 `1` 是"强制触发"语义（[FORCE_TRIGGER_THRESHOLD]），刻意区别于常规阈值；
 *  - 渠道侧按"轮次(turn)"而非"消息条数(message)"计数、走独立存储，属另一子系统，
 *    其阈值留在 `ChannelAutoReply`，此处只提供它引用不到时的说明。
 *
 * 改这里的值即同时影响 Transformer 缺省与主对话路径，避免"改一处不生效"。
 */
internal object CompressionPolicy {
    /** 主对话自动压缩：触发压缩的消息条数阈值（默认档）。 */
    const val THRESHOLD_DEFAULT = 20

    /** 主对话自动压缩：压缩后保留的最近消息条数（默认档）。 */
    const val KEEP_RECENT_DEFAULT = 15

    /**
     * 主对话自动压缩：实验性 longMemoryCompression 开启时更早触发。
     *
     * 约束：threshold 必须 > keepRecent，否则可压缩区间为空、压缩永不触发
     * （ContextCompressTransformer 对此有告警）。
     */
    const val THRESHOLD_LONG_MEMORY = 10

    /** 主对话自动压缩：longMemoryCompression 开启时保留的最近消息条数。 */
    const val KEEP_RECENT_LONG_MEMORY = 8

    /**
     * 手动压缩的强制触发阈值 —— 用户主动点压缩时不设条数门槛（任何历史都压缩）。
     * 语义刻意区别于常规阈值（[THRESHOLD_DEFAULT]），不要用常规值替换。
     */
    const val FORCE_TRIGGER_THRESHOLD = 1

    /** 按 longMemoryCompression 开关取触发阈值。 */
    fun threshold(longMemoryCompression: Boolean): Int =
        if (longMemoryCompression) THRESHOLD_LONG_MEMORY else THRESHOLD_DEFAULT

    /** 按 longMemoryCompression 开关取保留条数。 */
    fun keepRecent(longMemoryCompression: Boolean): Int =
        if (longMemoryCompression) KEEP_RECENT_LONG_MEMORY else KEEP_RECENT_DEFAULT
}
