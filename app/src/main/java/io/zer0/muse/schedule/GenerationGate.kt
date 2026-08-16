package io.zer0.muse.schedule

import java.util.concurrent.Semaphore

/**
 * B-22: 主动消息 / 群聊生成的进程内共享并发限流闸。
 *
 * 背景: 主动消息(ProactiveMessageRunner)与群聊(GroupChatScheduler)各自发起 LLM
 * 调用,无共享限流,可叠加触发 429 导致群聊发言静默丢失。
 *
 * 进程内信号量,最大并发 [MAX_CONCURRENT_LLM] 路;跨进程(Worker)不覆盖,
 * 由各 Runner 自身的时间窗口兜底。注册到 Koin 为单例或直接用 object 均可
 * (object 天然进程内单例,避免 Koin 接线遗漏)。
 */
object GenerationGate {

    /** 最大并发 LLM 生成路数(主动消息 + 群聊共享)。 */
    private const val MAX_CONCURRENT_LLM = 2

    private val semaphore = Semaphore(MAX_CONCURRENT_LLM)

    /**
     * 在信号量许可内执行 [block]。
     * 阻塞等待许可时不持有任何锁,不阻塞其他协程调度。
     */
    suspend fun <T> withPermit(block: suspend () -> T): T {
        semaphore.acquire()
        try {
            return block()
        } finally {
            semaphore.release()
        }
    }
}
