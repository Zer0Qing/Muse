package io.zer0.muse.ui.common

/**
 * Shared duration formatter for task-card and tool-trace surfaces.
 *
 * TaskCard and DelegationChainCard each had their own copy; keeping one implementation
 * makes the numbers comparable across cards.
 */
internal fun formatToolDuration(ms: Long): String {
    if (ms < 0) return "0ms"
    return when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> {
            val seconds = ms / 1000.0
            // One decimal is enough precision for tool latency and avoids "1.2333333s" noise.
            val rounded = kotlin.math.round(seconds * 10) / 10
            if (rounded % 1.0 == 0.0) "${rounded.toInt()}s" else "${rounded}s"
        }
        else -> {
            val min = ms / 60_000
            val sec = (ms % 60_000) / 1000
            "${min}m ${sec}s"
        }
    }
}
