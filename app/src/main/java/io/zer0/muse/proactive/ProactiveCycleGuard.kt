package io.zer0.muse.proactive

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 主动消息巡检的进程内单飞闸。
 *
 * 巡检有四路触发源（进程内定时、WorkManager、回前台、设置页手动），
 * 它们各自独立发起、互不知情。没有一道共享的闸时，两路可以同时进入巡检，
 * 各自读到同一份「上次发送时间」，于是各自认为「距上一条已经够久了」，
 * 最后在同一时间窗里落库两条内容高度相似的消息。
 *
 * 这里管两件事：
 *
 *  1. **在跑标记**：同一时刻只允许一轮巡检在跑。后到的触发不排队，
 *     直接拿到 null 走人 —— 排队没有意义，前一轮跑完就会重新判定时机，
 *     等待者醒来只会得到「刚说过话」的结论。
 *
 *  2. **尝试记录**：本轮真正开始生成时登记一笔开跑时间，并且跨轮保留。
 *     结果账要等消息落库才写，而生成最长几十秒；这段时间里只看结果账的话，
 *     正在生成的那一轮在数据层等于不存在。开跑时间补上这段空窗。
 *
 * 登记放在「进入生成」而不是「进入巡检」：被免打扰、额度、开关拦下的轮次
 * 什么都没做，不该在账上留下痕迹去压制后面的正常巡检。
 *
 * 同理，一轮结束时若什么都没发出去（模型说没什么可接的、生成失败、落库失败），
 * 这笔尝试会被回滚 —— 它没有产生消息，没有重复可言，也不该改变复查节奏。
 */
class ProactiveCycleGuard {

    /**
     * 一轮巡检的进入凭据。
     *
     * [previousAttemptAt] 是本轮**之前**那次生成尝试的时间：判定时机时需要知道
     * 「上一轮什么时候跑的」，但不能把本轮自己刚写下的时间当成上一轮，
     * 否则每一轮都会拦住自己。它同时也是回滚的落点。
     */
    data class Entry(
        /** 本轮进入巡检的时间戳。 */
        val enteredAt: Long,
        /** 本轮之前那次生成尝试的时间戳，0 表示还没有过。 */
        val previousAttemptAt: Long,
    )

    private val inFlight = AtomicBoolean(false)

    /** 最近一次真正进入生成的时间，跨轮保留。 */
    @Volatile
    private var attemptAt = 0L

    /** 此刻是否已经有一轮巡检在跑。 */
    val isInFlight: Boolean get() = inFlight.get()

    /**
     * 尝试进入一轮巡检。
     *
     * @return 拿到凭据表示可以跑；null 表示已经有一轮在跑，本次触发应当跳过。
     */
    fun tryEnter(now: Long): Entry? {
        if (!inFlight.compareAndSet(false, true)) return null
        return Entry(enteredAt = now, previousAttemptAt = attemptAt)
    }

    /**
     * 登记一次生成尝试。
     *
     * 由本轮在真正调用模型之前调一次。写入之后到本轮结束之前，
     * 这笔记录都有效 —— 期间若还有巡检进来（跨进程的 Worker 绕过了单飞闸
     * 时就是这种情况），它会看到「刚刚试过」，而不是看到一张空账。
     */
    fun markAttempt(now: Long) {
        attemptAt = now
    }

    /**
     * 退出一轮巡检。
     *
     * 只清在跑标记。「这一轮还在跑」和「这一轮试过了」是两件事，
     * 混在一起会让刚跑完的那一轮立刻失去痕迹。
     *
     * @param sent 本轮是否真的发出了一条消息。没发出去就把尝试回滚到
     *             [Entry.previousAttemptAt]，让下一次复查的节奏与改动前一致。
     */
    fun leave(entry: Entry, sent: Boolean) {
        if (!sent) attemptAt = entry.previousAttemptAt
        inFlight.set(false)
    }
}
