package io.zer0.muse.automation.core

/**
 * Unified screen snapshot with version tracking.
 *
 * Combines structured UI tree data (ScreenInfo) with a screenshot reference,
 * plus an incremental version counter for change detection.
 *
 * Consumers use [version] to detect screen changes between snapshots:
 * incrementing values mean the screen has changed.
 */
data class ScreenSnapshot(
    val info: ScreenInfo,
    val screenshotPng: ByteArray? = null,
    /** Incremented each time a fresh snapshot is taken. */
    val version: Int = 0,
) {
    /** Compact textual summary for AI consumption. */
    fun toSummary(maxNodes: Int = 40): String = info.toSummary(maxNodes)

    /** Whether the screenshot is available. */
    val hasScreenshot: Boolean get() = screenshotPng != null && screenshotPng.isNotEmpty()

    companion object {
        /** Initial version for the first snapshot. */
        const val INITIAL_VERSION = 1
    }
}
