package io.zer0.muse.automation.core

import io.zer0.common.Logger
import kotlinx.coroutines.delay

/**
 * Extended ScreenInfo with a hash code for diffing between snapshots.
 */
val ScreenInfo.nodesHashCode: Int
    get() = nodes.map { "${it.text}_${it.boundsLeft}_${it.boundsTop}_${it.boundsRight}_${it.boundsBottom}" }.hashCode()

/**
 * Wait primitives for UI automation.
 *
 * Provides timeout-bounded polling primitives used by tools and the execution layer:
 * - [waitForIdle]: wait until no UI changes are detected within a stability window
 * - [waitForWindowChange]: wait until the top activity/package changes
 * - [waitForText]: wait until text appears (or disappears) on screen
 *
 * All primitives are bounded by [defaultTimeoutMs] to prevent infinite blocking.
 */
object WaitPrimitives {

    private const val TAG = "WaitPrimitives"
    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val DEFAULT_POLL_INTERVAL_MS = 500L
    const val DEFAULT_STABILITY_WINDOW_MS = 1_000L

    /**
     * Wait until the screen appears idle (no structural changes within [stabilityWindowMs]).
     *
     * Takes a snapshot, waits [stabilityWindowMs], takes another snapshot, and compares.
     * If still changing, repeats up to [timeoutMs].
     *
     * @param snapshotProvider function that produces a fresh [ScreenSnapshot]
     * @param timeoutMs maximum total wait time
     * @param pollIntervalMs interval between snapshot attempts
     * @param stabilityWindowMs quiet period required to declare idle
     * @return true if idle detected, false if timeout
     */
    suspend fun waitForIdle(
        snapshotProvider: suspend () -> ScreenSnapshot,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
        stabilityWindowMs: Long = DEFAULT_STABILITY_WINDOW_MS,
    ): Boolean = try {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastSnapshot: ScreenSnapshot? = null
        // v2.0.1: 初值改为当前时间 —— 屏幕本已静止时也能在稳定性窗口后正确返回 true,
        // 旧实现 lastChangeTime 恒为 0 会导致"永远等不到静止"直到超时。
        var lastChangeTime = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            val current = snapshotProvider()
            val changed = lastSnapshot?.let { snap ->
                snap.info.packageName != current.info.packageName ||
                    snap.info.activityName != current.info.activityName ||
                    snap.info.nodesHashCode != current.info.nodesHashCode
            } == true
            if (changed) {
                lastChangeTime = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - lastChangeTime >= stabilityWindowMs) {
                Logger.i(TAG, "waitForIdle: screen stable for ${stabilityWindowMs}ms")
                return true
            }
            lastSnapshot = current
            delay(pollIntervalMs)
        }
        Logger.w(TAG, "waitForIdle: timeout after ${timeoutMs}ms")
        false
    } catch (e: Exception) {
        Logger.w(TAG, "waitForIdle exception: ${e.message}")
        false
    }

    /**
     * Wait until the foreground app/activity changes from [initialPackage] or [initialActivity].
     *
     * @param initialPackage current foreground package (obtained before the action)
     * @param initialActivity current activity name (obtained before the action)
     * @param targetPackage optional expected new package (null = any change)
     * @param targetActivity optional expected new activity
     * @param snapshotProvider function that produces a fresh [ScreenSnapshot]
     * @param timeoutMs maximum wait time
     * @return true if window changed as expected, false if timeout
     */
    suspend fun waitForWindowChange(
        initialPackage: String?,
        initialActivity: String?,
        targetPackage: String? = null,
        targetActivity: String? = null,
        snapshotProvider: suspend () -> ScreenSnapshot,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    ): Boolean = try {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(pollIntervalMs)
            val snap = snapshotProvider()
            val pkgChanged = targetPackage?.let { it != snap.info.packageName }
                ?: (initialPackage != snap.info.packageName)
            val actChanged = targetActivity?.let { it != snap.info.activityName }
                ?: (initialActivity != snap.info.activityName)
            if (pkgChanged || actChanged) {
                Logger.i(TAG, "waitForWindowChange: detected change pkg=${snap.info.packageName} act=${snap.info.activityName}")
                return true
            }
        }
        Logger.w(TAG, "waitForWindowChange: timeout after ${timeoutMs}ms")
        false
    } catch (e: Exception) {
        Logger.w(TAG, "waitForWindowChange exception: ${e.message}")
        false
    }

    /**
     * Wait until [text] appears (or disappears when [appear=false]) on screen.
     *
     * @param text text to search for (substring match, case-insensitive)
     * @param appear whether to wait for appearance (true) or disappearance (false)
     * @param snapshotProvider function that produces a fresh [ScreenSnapshot]
     * @param timeoutMs maximum wait time
     * @return true if text condition met, false if timeout
     */
    suspend fun waitForText(
        text: String,
        appear: Boolean = true,
        snapshotProvider: suspend () -> ScreenSnapshot,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    ): Boolean = try {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val snap = snapshotProvider()
            val found = snap.info.nodes.any { node ->
                val searchText = node.text ?: node.contentDescription
                searchText?.contains(text, ignoreCase = true) == true
            }
            val satisfied = if (appear) found else !found
            if (satisfied) {
                Logger.i(TAG, "waitForText: text '${text.take(30)}' ${if (appear) "appeared" else "disappeared"}")
                return true
            }
            delay(pollIntervalMs)
        }
        Logger.w(TAG, "waitForText: timeout waiting for text '${text.take(30)}'")
        false
    } catch (e: Exception) {
        Logger.w(TAG, "waitForText exception: ${e.message}")
        false
    }
}
