package io.zer0.muse.ui.chat

/**
 * v1.x: system prompt / 上下文 token 缓存的共享状态容器。
 *
 * 承载 cachedSystemPrompt(动态 system prompt 文本)、cachedStaticSystemPrompt(静态快照)、
 * cachedStaticSnapshotKey(快照失效 key),供 buildSystemPromptForStream / updateContextTokenCount
 * 及流式路径共享复用。ChatViewModel 通过 getter/setter 委托保持既有引用不变,
 * 为把 buildSystemPromptForStream 迁入 ChatGenerationController 铺路。
 */
internal class SystemPromptCache {
    /** v0.45: 缓存的 system prompt 文本(避免流式过程中每 50 字符都重建)。 */
    var cachedSystemPrompt: String = ""

    /** 静态 system prompt 快照(同一会话内连续发消息复用,只追加动态时间 section)。 */
    var cachedStaticSystemPrompt: String = ""

    /** 静态快照失效 key(assistant/settings/chatPreferences 变化时触发重建)。 */
    var cachedStaticSnapshotKey: String = ""
}
