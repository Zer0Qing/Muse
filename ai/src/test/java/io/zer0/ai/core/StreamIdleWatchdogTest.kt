package io.zer0.ai.core

import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式**帧间静默**看门狗。
 *
 * 补的是一个很具体的用户体验洞：流式请求为了不把长回复硬杀，`callTimeout=0`，
 * 于是"服务端半死不活"只能等 `readTimeout=300s` 才被发现 ——
 * 用户看着光标转整整 5 分钟。这里压到 90s，并且发的是
 * [ChatStreamEvent.StreamInterrupted] + `IOException`，
 * 让上层走"保留已显示内容 + 等网络恢恢复后续传"的分支，而不是清屏重发。
 *
 * 全部用虚拟时间（`runTest`），不需要真的等 90 秒。
 */
class StreamIdleWatchdogTest {

    @Test
    fun `吐过一个字节后静默，到阈值就报中断且带 IOException`() = runTest {
        val events = flow<ChatStreamEvent> {
            emit(ChatStreamEvent.ContentDelta("半截回答"))
            awaitCancellation()   // 上游永不结束、也永不报错：正是 readTimeout 要 300s 才抓住的那种
        }.withIdleWatchdog(idleMs = 100).toList()

        assertEquals("应只剩 半截 + 一条中断：${events.map { it::class.simpleName } }", 2, events.size)
        val last = events.last()
        assertTrue("必须是 StreamInterrupted，上层才会保留已显示内容：$last",
            last is ChatStreamEvent.StreamInterrupted)
        assertTrue(
            "throwable 要是 IOException，上层据此判定为网络类错误并走续传分支",
            (last as ChatStreamEvent.StreamInterrupted).throwable is IOException,
        )
    }

    @Test
    fun `事件持续到达时不误伤`() = runTest {
        val events = flow<ChatStreamEvent> {
            repeat(3) { emit(ChatStreamEvent.ContentDelta("x")); delay(40) }
            emit(ChatStreamEvent.Done())
        }.withIdleWatchdog(idleMs = 100).toList()

        assertTrue("不该出现中断事件：$events", events.none { it is ChatStreamEvent.StreamInterrupted })
        assertEquals("4 个事件全部透出", 4, events.size)
    }

    @Test
    fun `上游正常结束后不再等待阈值`() = runTest {
        // 只发一个事件然后关闭流：如果实现是"等超时才收工"，这条用例会白等一个阈值
        val events = flowOf<ChatStreamEvent>(ChatStreamEvent.ContentDelta("完套内容"))
            .withIdleWatchdog(idleMs = 100)
            .toList()

        assertEquals(1, events.size)
    }

    @Test
    fun `Done 之后立即结束`() = runTest {
        val events = flowOf<ChatStreamEvent>(ChatStreamEvent.Done("stop"))
            .withIdleWatchdog(idleMs = 100)
            .toList()

        assertEquals("Done 后不应再多等一轮：$events", 1, events.size)
        assertTrue(events.single() is ChatStreamEvent.Done)
    }

    /**
     * 接线守卫：看门狗必须包在**原始流**上、首事件看门狗的**内侧**。
     *
     * 顺序错了会引入新 bug：首事件超时后走的非流式回退可以合法地几分钟不吐事件
     * （共享 client `CALL_TIMEOUT_SEC = 600L`），套在外面就把正常回退误杀成死流。
     * 这条只能做结构断言（组合顺序在 `AiModule` 里，构造真 Provider 不划算），
     * 说清楚它测的是"位置"，不是"效果"。
     */
    @Test
    fun `接线位置必须在首事件看门狗内侧`() {
        val candidates = listOf(
            java.io.File("src/main/java/io/zer0/ai/AiModule.kt"),
            java.io.File("ai/src/main/java/io/zer0/ai/AiModule.kt"),
            java.io.File("../ai/src/main/java/io/zer0/ai/AiModule.kt"),
        )
        val src = requireNotNull(candidates.firstOrNull { it.exists() }) {
            "找不到 AiModule.kt（工作目录=${java.io.File(".").absolutePath}）"
        }.readText()

        assertTrue(
            "流式链上必须挂帧间看门狗",
            src.contains("withIdleWatchdog("),
        )
        assertTrue(
            "顺序必须是 withIdleWatchdog().withFirstEventWatchdog(...)：反过来会误杀非流式回退",
            Regex("""withIdleWatchdog\([^)]*\)\s*\.withFirstEventWatchdog""").containsMatchIn(src),
        )
    }
}
