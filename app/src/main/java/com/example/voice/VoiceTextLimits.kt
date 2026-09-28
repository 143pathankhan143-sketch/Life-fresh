package com.example.voice

/**
 * How much of a reply may be spoken at all.
 *
 * WHY THIS EXISTS (the "answer stops in the middle" bug, 2026-09-27):
 * AiTts used to trim every reply to 700 characters before speaking. A normal
 * CRM answer - "Main aapki CRM mein yeh kar sakta hoon: ... Lead update ...
 * Reminders ... Reports & summaries ..." - is 800+ characters, so the voice
 * stopped around the 700th character and the REST OF THE ANSWER WAS NEVER
 * SPOKEN. The chat kept working, so it looked like the voice had died.
 *
 * Chunking (AiVoicePlayer.splitForTts + AiTts.splitForEngine) is what keeps a
 * long reply inside the engine's limits; trimming is only a last-resort valve
 * for absurd input. It must therefore sit far above a real answer.
 *
 * Pure Kotlin on purpose: the rule is unit-tested without a device.
 */
object VoiceTextLimits {

    /**
     * Upper bound for spoken text. A phone engine refuses more than ~4000
     * characters per utterance, and our chunker splits long text well below
     * that anyway - so normal replies (even 2000+ characters) are spoken
     * completely and only a pathological input is trimmed.
     */
    const val MAX_SPEAKABLE_CHARS: Int = 8_000

    /**
     * True when [text] is mostly Latin script (English or Hinglish written in
     * Latin letters). Used to let the ENGLISH cloud voice read a Hinglish reply
     * - better for an illiterate listener than silence when the phone's own
     * engine is broken and there is no Gemini key.
     *
     * Pure and tested: the rule is a simple ratio over real letters, so digits,
     * punctuation and emoji can not skew it.
     */
    fun looksLatin(text: String): Boolean {
        var latin = 0
        var letters = 0
        for (ch in text) {
            if (ch.isLetter()) {
                letters++
                val code = ch.code
                if (code in 0x41..0x5A || code in 0x61..0x7A) latin++
            }
        }
        if (letters < 20) return false
        return latin * 10 >= letters * 6  // at least 60% Latin letters
    }

    /**
     * Trims [text] to [maxLen] at a word boundary, appending "…" so a listener
     * can hear that something was left out. Text within the limit is returned
     * unchanged - no silent cut.
     */
    fun truncateForSpeech(text: String, maxLen: Int = MAX_SPEAKABLE_CHARS): String {
        if (maxLen <= 0 || text.length <= maxLen) return text
        val cut = text.substring(0, maxLen)
        val lastSpace = cut.lastIndexOf(' ')
        val body = if (lastSpace > maxLen / 2) cut.substring(0, lastSpace) else cut
        return body.trimEnd() + "…"
    }
}
