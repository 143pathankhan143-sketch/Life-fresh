package com.example.voice

/**
 * Splits text for Groq's Orpheus TTS, whose `input` field accepts at most
 * [MAX_INPUT_CHARS] characters per request.
 *
 * A longer reply is spoken as several short requests whose audio files are
 * played one after another, so nothing is truncated. Cuts prefer sentence
 * boundaries, then word boundaries; a single word longer than the limit is
 * hard-cut (never happens with real replies, but the splitter must terminate).
 *
 * Pure Kotlin on purpose: the rule is unit-tested without a device.
 */
object OrpheusChunker {

    /** Groq docs: "input: Text to convert to speech (max 200 characters)". */
    const val MAX_INPUT_CHARS: Int = 200

    /** Some room for the engine's own counting (punctuation, spacing). */
    private const val SAFE_CHARS: Int = 190

    fun split(text: String): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        if (clean.length <= SAFE_CHARS) return listOf(clean)

        val out = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotBlank()) {
                out.add(current.toString().trim())
                current.setLength(0)
            }
        }

        for (sentence in clean.split(Regex("(?<=[.!?\\n])\\s+"))) {
            if (sentence.length > SAFE_CHARS) {
                flush()
                for (word in sentence.split(Regex("\\s+"))) {
                    var rest = word
                    while (rest.length > SAFE_CHARS) {
                        if (current.isNotEmpty()) flush()
                        out.add(rest.substring(0, SAFE_CHARS))
                        rest = rest.substring(SAFE_CHARS)
                    }
                    if (current.length + rest.length + 1 > SAFE_CHARS) flush()
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(rest)
                }
                continue
            }
            if (current.length + sentence.length + 1 > SAFE_CHARS) flush()
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
        }
        flush()
        return out
    }
}
