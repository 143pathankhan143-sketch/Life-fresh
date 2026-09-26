package com.example.voice

/**
 * Freshness rule for a spoken question handed over to the AI chat.
 *
 * A prompt is posted, then the app switches to the AI tab which sends it. If
 * that switch is interrupted (another tap, a dialog, a slow frame) the prompt
 * would sit in the slot and fire much later — sending a question the user
 * asked minutes ago and has already forgotten. Anything older than
 * [MAX_AGE_MS] is dropped instead of sent.
 *
 * Pure Kotlin so the rule is unit-tested; the clock is passed in.
 */
object VoiceHandoffTiming {

    /** How long a handed-over question stays valid. */
    const val MAX_AGE_MS: Long = 15_000L

    fun isFresh(
        postedAtMs: Long,
        nowMs: Long,
        maxAgeMs: Long = MAX_AGE_MS
    ): Boolean {
        if (postedAtMs <= 0L) return false
        val age = nowMs - postedAtMs
        // A negative age means the clock moved backwards (or a bogus stamp):
        // treat it as fresh rather than dropping a question we just heard.
        return age <= maxAgeMs
    }
}
