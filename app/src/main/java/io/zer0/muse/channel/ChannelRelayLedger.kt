package io.zer0.muse.channel

import java.security.MessageDigest

/**
 * 跨渠道转发的护栏。
 *
 * 让 AI 通过另一个已绑定渠道发话，前提是能兜住两类事故：
 *  1. **回声环**：两个机器人互相转述，同一句话在两边来回刷；
 *  2. **手滑刷屏**：模型一轮里连发多条到别人的 IM，人不在跟前，发出去就收不回。
 *
 * 这里只做判定与记账，不碰网络；真正发送仍在 [ChannelManager]。
 * 判定全部基于时间与文本摘要，不依赖任何模型输出，因此可预测、可测。
 */
object ChannelRelayLedger {

    /** 转出去的内容在这个窗口内原样回来，视为回声。 */
    const val ECHO_WINDOW_MS = 90_000L

    /** 一小时内最多转发多少条（跨所有渠道）。 */
    const val MAX_RELAY_PER_HOUR = 10

    /** 单轮自动回复里最多转发多少条。 */
    const val MAX_RELAY_PER_TURN = 2

    private const val HOUR_MS = 3_600_000L

    private data class EchoKey(val channelId: String, val textDigest: String)

    private val lock = Any()
    private val echoes = HashMap<EchoKey, Long>()
    private val sends = ArrayDeque<Long>()
    private var turnRelays = 0

    /** 每轮开始前调用，清零单轮计数。 */
    fun beginTurn() {
        synchronized(lock) { turnRelays = 0 }
    }

    /**
     * 发送前占位：单轮与每小时都有上限，超了就拒绝，不发送。
     * 只判定不记账，[recordSent] 才落账 —— 发送失败不该占额度。
     */
    fun tryReserveSend(now: Long): Boolean = synchronized(lock) {
        prune(now)
        if (turnRelays >= MAX_RELAY_PER_TURN) return false
        sends.size < MAX_RELAY_PER_HOUR
    }

    /** 发送成功后记账。 */
    fun recordSent(channelId: String, text: String, now: Long) {
        synchronized(lock) {
            turnRelays++
            sends.addLast(now)
            echoes[EchoKey(channelId, digest(text))] = now
            prune(now)
        }
    }

    /** 收到的这条是否是本机刚转出去的回声。 */
    fun isEcho(channelId: String, text: String, now: Long): Boolean {
        synchronized(lock) {
            prune(now)
            val sentAt = echoes[EchoKey(channelId, digest(text))] ?: return false
            return now - sentAt <= ECHO_WINDOW_MS
        }
    }

    /** 测试与「恢复出厂」用：清空全部记账。 */
    fun reset() {
        synchronized(lock) {
            echoes.clear()
            sends.clear()
            turnRelays = 0
        }
    }

    private fun prune(now: Long) {
        sends.removeAll { now - it > HOUR_MS }
        echoes.entries.removeAll { now - it.value > ECHO_WINDOW_MS }
    }

    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.trim().toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}
