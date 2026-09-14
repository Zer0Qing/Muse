package io.zer0.muse.privacy

import io.zer0.common.PiiEngine

/** Compatibility facade for the app PII API. */
object PiiGuard {
    enum class PiiType { PHONE, EMAIL, ID_CARD, BANK_CARD, IP_ADDRESS, ADDRESS }
    data class PiiMatch(val type: PiiType, val original: String, val masked: String, val start: Int, val end: Int)

    fun detect(text: String): List<PiiMatch> = PiiEngine.detectApp(text).map(::fromShared)

    fun mask(text: String): Pair<String, List<PiiMatch>> {
        val (masked, matches) = PiiEngine.maskApp(text)
        return masked to matches.map(::fromShared)
    }

    fun unmask(text: String, matches: List<PiiMatch>): String =
        PiiEngine.unmaskApp(text, matches.map { PiiEngine.AppMatch(toSharedType(it.type), it.original, it.masked, it.start, it.end) })

    private fun fromShared(match: PiiEngine.AppMatch) = PiiMatch(
        PiiType.valueOf(match.type.name), match.original, match.masked, match.start, match.end,
    )

    private fun toSharedType(type: PiiType) = PiiEngine.AppType.valueOf(type.name)
}
