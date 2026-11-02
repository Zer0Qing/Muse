package io.zer0.muse.automation.core

/**
 * 屏幕观察的**有效期台账**。
 *
 * 解决的问题：模型读了一次屏、想了两秒、然后点下去 —— 这两秒里界面可能已经变了
 * （通知弹出、列表滚动、页面跳转），点到的就是别的东西。轻则白点一次，
 * 重则点到「删除/付款」这类按钮上。
 *
 * 做法：读屏时发一个 `observation_id`；点击时可以带上它，
 * 只要期间有过新的观察（或已过期），这个 id 就作废，工具直接拒绝并要求重新读屏。
 *
 * 三条约束：
 *  1. **同一 scope 只认最新一次**：拿旧 id 去点，说明模型看到的是过时画面；
 *  2. **必须有有效期**：界面没变但过了很久，仍然要重新确认（人也会看走眼）；
 *  3. **id 单调递增且不可猜**：避免模型复用自己编的 id 绕过校验。
 */
object AutomationObservationRegistry {

    /** 默认有效期：足够模型思考一步，又不至于让过时画面一直有效。 */
    const val DEFAULT_TTL_MS = 90_000L

    data class Observation(
        val id: String,
        val scope: String,
        val fingerprint: String,
        val createdAt: Long,
        val expiresAt: Long,
    ) {
        fun isExpired(now: Long): Boolean = now >= expiresAt
    }

    private val latestByScope = HashMap<String, Observation>()
    private var counter = 0L

    /**
     * 记录一次新的观察，返回它的 id。
     *
     * 同一个 scope 上之前发出的 id 立刻作废（这正是"画面可能变了"的表达）。
     */
    @Synchronized
    fun record(
        scope: String,
        fingerprint: String,
        now: Long,
        ttlMs: Long = DEFAULT_TTL_MS,
    ): Observation {
        counter += 1
        val observation = Observation(
            id = "obs-$counter",
            scope = scope,
            fingerprint = fingerprint,
            createdAt = now,
            expiresAt = now + ttlMs.coerceAtLeast(1),
        )
        latestByScope[scope] = observation
        return observation
    }

    /** 当前 scope 上仍然有效的观察（过期返回 null）。 */
    @Synchronized
    fun current(scope: String, now: Long): Observation? {
        val observation = latestByScope[scope] ?: return null
        if (observation.isExpired(now)) {
            latestByScope.remove(scope)
            return null
        }
        return observation
    }

    /**
     * [observationId] 是否仍可用于操作。
     *
     * 空 id 返回 false：调用方应该先判断"有没有传"，而不是把空串当成合法。
     */
    @Synchronized
    fun isFresh(scope: String, observationId: String?, now: Long): Boolean {
        if (observationId.isNullOrBlank()) return false
        return current(scope, now)?.id == observationId
    }

    /**
     * 用界面要素算指纹。
     *
     * 不追求密码学强度，只要能表达"这看起来还是不是同一屏"：
     * 包名 + 界面名 + 前若干个控件文字，任一变化都会换指纹。
     */
    fun fingerprintOf(packageName: String?, activityName: String?, nodeTexts: List<String>): String {
        val payload = buildString {
            append(packageName.orEmpty()).append('|').append(activityName.orEmpty())
            nodeTexts.take(40).forEach { append('|').append(it) }
        }
        return payload.hashCode().toUInt().toString(16)
    }

    /** 仅测试用：清空台账与计数器。 */
    @Synchronized
    internal fun resetForTests() {
        latestByScope.clear()
        counter = 0
    }
}
