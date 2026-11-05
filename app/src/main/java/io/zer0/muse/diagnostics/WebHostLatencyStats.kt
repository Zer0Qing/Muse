package io.zer0.muse.diagnostics

/**
 * v2.6.23(S4): Web Host 请求延迟统计 —— 诊断卡上「p95 / 最近请求数」的数据源。
 *
 * 环形缓冲记录最近 [CAPACITY] 次请求的耗时(毫秒),p95 在读取时按排序取位。
 * 不做直方图:窗口只有 512 条,读频率是"打开诊断页"级,排序成本可忽略。
 * 不记录路径与来源 IP —— 诊断卡的导出是**给用户贴给开发者的**,
 * 每个字段都要按"可能被公开"来设计(见 [DeviceDiagnostics] 的脱敏规则)。
 */
class WebHostLatencyStats(
    private val capacity: Int = CAPACITY,
) {

    private val lock = Any()
    private val durations = LongArray(capacity)
    private var next = 0
    private var filled = 0

    fun record(durationMs: Long) {
        if (durationMs < 0L) return
        synchronized(lock) {
            durations[next] = durationMs
            next = (next + 1) % capacity
            if (filled < capacity) filled++
        }
    }

    /** p95(毫秒);无样本返回 null。 */
    fun p95(): Long? = synchronized(lock) {
        if (filled == 0) return@synchronized null
        val copy = durations.copyOf(filled)
        copy.sort()
        val index = ((copy.size - 1) * 0.95).toInt()
        copy[index]
    }

    /** 已记录样本数(供「最近请求太少，p95 不可信」的展示判断)。 */
    fun sampleCount(): Int = synchronized(lock) { filled }

    fun snapshot(): Pair<Int, Long?> = sampleCount() to p95()

    companion object {
        const val CAPACITY = 512

        /** 低于这个样本数时 UI 应标注「样本不足」而不是给出精确数字。 */
        const val MIN_RELIABLE_SAMPLES = 20

        /** 全宿主共享:WebServer 的请求计时器往这里写,诊断卡从这里读。 */
        val shared: WebHostLatencyStats by lazy { WebHostLatencyStats() }
    }
}
