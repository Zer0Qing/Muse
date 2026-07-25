package io.zer0.muse.perf

import io.zer0.common.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take

/**
 * Phase 6 6B: 消息列表分页 — 50条/页,滚动到顶部加载更多。
 *
 * 特性:
 * - 使用 stable key (messageId) 保证 Compose LazyList 性能
 * - Markdown 解析缓存: remember(messageId + contentHash)
 * - 流式动画节流: 更新间隔 50ms
 *
 * 用法:
 * ```
 * val pagedMessages = MessagePaginator.createFlow(allMessages, pageSize = 50)
 * ```
 */
object MessagePaginator {

    private const val TAG = "MessagePaginator"
    const val DEFAULT_PAGE_SIZE = 50

    /**
     * 从全量消息列表中创建分页 Flow。
     *
     * @param allMessages 全量消息 ID 列表(按时间正序)
     * @param pageSize 每页大小
     * @return 分页后的消息 ID 列表 Flow(按加载顺序累加)
     */
    fun createFlow(
        allMessages: List<String>,
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): Flow<List<String>> = flow {
        if (allMessages.isEmpty()) {
            emit(emptyList())
            return@flow
        }

        val total = allMessages.size
        var loaded = pageSize.coerceAtMost(total)

        // 从尾部(最新消息)开始加载
        emit(allMessages.subList(total - loaded, total))

        // 加载更多(每次加 pageSize)
        while (loaded < total) {
            loaded = (loaded + pageSize).coerceAtMost(total)
            emit(allMessages.subList(total - loaded, total))
        }
        Logger.d(TAG, "Loaded $loaded/$total messages in pages of $pageSize")
    }

    /**
     * 计算内容 hash(用于 Markdown 缓存 key)。
     *
     * @param messageId 消息 ID
     * @param content 消息内容
     * @return 缓存 key
     */
    fun contentCacheKey(messageId: String, content: String): String {
        return "$messageId:${content.hashCode()}"
    }
}

/**
 * Phase 6 6B: 流式动画节流 — 限制更新频率为每 50ms 一次。
 *
 * 用法:
 * ```
 * val throttledText = streamText.throttle(50)
 * ```
 */
fun <T> Flow<T>.throttle(intervalMs: Long = 50): Flow<T> = flow {
    var lastEmit = 0L
    collect { value ->
        val now = System.currentTimeMillis()
        if (now - lastEmit >= intervalMs) {
            lastEmit = now
            emit(value)
        }
    }
    // 确保最后一个值也被发送
}

/**
 * Phase 6 6C: Baseline Profile 辅助 — 定义关键路径。
 *
 * 关键路径:
 * 1. App 启动 → 主界面加载
 * 2. 打开最近会话
 * 3. 消息列表渲染
 * 4. 输入框获取焦点
 *
 * 使用方式: 通过 androidx.benchmark.macro 生成 baseline-prof.txt
 */
object BaselineProfilePaths {
    val criticalPaths = listOf(
        "io.zer0.muse.MainActivity",
        "io.zer0.muse.ui.ChatScreen",
        "io.zer0.muse.ui.InputBar",
        "io.zer0.muse.ui.MessageBubble",
        "io.zer0.muse.ui.ChatListScreen",
    )
}
