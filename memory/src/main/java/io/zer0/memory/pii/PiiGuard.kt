package io.zer0.memory.pii

import io.zer0.common.PiiEngine

/** Compatibility facade for the memory PII API. */
object PiiGuard {
    data class ScrubResult(val cleaned: String, val detected: List<String>)
    data class MaskResult(val masked: String, val map: Map<String, String>)

    fun scrub(text: String): ScrubResult {
        val result = PiiEngine.scrubMemory(text)
        return ScrubResult(result.cleaned, result.detected)
    }

    fun mask(text: String): MaskResult {
        val result = PiiEngine.maskMemory(text)
        return MaskResult(result.masked, result.map)
    }

    fun unmask(text: String, map: Map<String, String>): String = PiiEngine.unmaskMemory(text, map)
}
