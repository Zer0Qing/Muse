package io.zer0.muse.diagnostics

import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * v2.6.23(S4): 主线程卡顿计数 —— 诊断卡上「最近 5 分钟有没有卡」的数据源。
 *
 * 实现是 MessageQueue.IdleHandler 之外最轻的一档:包一层 Printer,测量主线程
 * 每条消息的处理时长。只计数,不抓栈(抓栈需要 ANR 级工具,耗隐私与性能);
 * 阈值取业界常用三档:可感知掉帧 64ms、明显卡顿 256ms、接近 ANR 1s。
 *
 * 分桶滚动:5 分钟窗口内三段时长各计数,窗口翻转由 [tickWindow] 驱动(由调用方
 * 的定时读取触发,不自带协程,避免为一个计数器引入调度)。
 */
class MainThreadJankTracker(
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) : android.util.Printer {

    /** 一次快照:三段计数 + 最慢一条的耗时。 */
    data class Snapshot(
        val jankCount: Int,
        val slowCount: Int,
        val blockCount: Int,
        val worstMs: Long,
        val windowStartedMs: Long,
        val nowMs: Long,
    ) {
        /** 当前窗口已走过的秒数;诊断卡与「运行」页统计都从这里取,免得两处各算一份对不上。 */
        val windowSeconds: Long get() = ((nowMs - windowStartedMs) / 1000L).coerceAtLeast(0L)
    }

    private val lock = Any()
    private var windowStart = nowMs()
    private var jank = 0
    private var slow = 0
    private var block = 0
    private var worst = 0L

    // 成对状态用显式布尔标记,不用时间值当哨兵:时钟从 0 起步是合法场景
    // (elapsedRealtime 从开机起算、测试里更是直接用虚拟时钟),
    // 拿 0L 兼作「未开始」会把每一轮的第一条结束事件误判成开始事件。
    private var messageStarted = false
    private val messageStartAt = AtomicLong(0L)

    // ── Printer 协议 ────────────────────────────────────────────
    // Looper 的消息日志只会走 println(String)(「>> / << …」成对出现);
    // 协议中的 println(Object) 在 API 36 stub 中不再由平台调用方使用,
    // 保持一个实现并让 record 的成对切换自解释。
    // record 只在主线程被调用(Looper 回调),messageStarted 无需原子可见性。
    override fun println(x: String) = record()

    private fun record() {
        if (!messageStarted) {
            // 本条消息的开始:记下时间戳即可,不分类
            messageStarted = true
            messageStartAt.set(nowMs())
            return
        }
        messageStarted = false
        val duration = nowMs() - messageStartAt.get()
        if (duration <= 0L) return
        synchronized(lock) {
            rollWindowLocked()
            if (duration >= BLOCK_MS) {
                block++
            } else if (duration >= SLOW_MS) {
                slow++
            } else if (duration >= JANK_MS) jank++
            if (duration > worst) worst = duration
        }
    }

    /** 读取快照(顺带滚动窗口)。 */
    fun snapshot(): Snapshot = synchronized(lock) {
        rollWindowLocked()
        Snapshot(
            jankCount = jank,
            slowCount = slow,
            blockCount = block,
            worstMs = worst,
            windowStartedMs = windowStart,
            nowMs = nowMs(),
        )
    }

    private fun rollWindowLocked() {
        val now = nowMs()
        if (now - windowStart >= WINDOW_MS) {
            windowStart = now
            jank = 0
            slow = 0
            block = 0
            worst = 0L
        }
    }

    companion object {
        /** 可感知掉帧(约 4 帧 @60Hz)。 */
        const val JANK_MS = 64L

        /** 明显卡顿。 */
        const val SLOW_MS = 256L

        /** 接近 ANR 门槛。 */
        const val BLOCK_MS = 1_000L

        /** 计数窗口。 */
        const val WINDOW_MS = 5 * 60_000L

        /**
         * 把 tracker 挂到主线程 Looper。必须在应用初始化早期调用一次;
         * setMessageLogging 是**替换**语义(Looper 只有一个 Printer 槽位),
         * 全仓只允许本处使用,重复调用被标记挡掉。
         */
        @Volatile
        private var installed = false

        val shared: MainThreadJankTracker by lazy { MainThreadJankTracker() }

        @Synchronized
        fun install(tracker: MainThreadJankTracker = shared) {
            if (installed) return
            installed = true
            Looper.getMainLooper().setMessageLogging(tracker)
        }
    }
}
