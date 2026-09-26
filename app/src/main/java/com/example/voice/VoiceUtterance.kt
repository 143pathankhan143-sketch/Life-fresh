package com.example.voice

import java.util.Locale

/** Result of classifying an utterance as a yes/no answer. */
enum class YesNoAnswer { YES, NO, UNKNOWN }

/**
 * Multilingual "haan/nahi" detector for the voice command layer.
 *
 * Mirrors the semantics of the AI chat's existing VoiceWordMatcher:
 *  - negation ALWAYS wins ("nahi kar do" is a NO), and
 *  - unknown phrases are never treated as answers.
 *
 * One deliberate difference: command verbs like "kar/karo/lagao" are kept OUT
 * of the affirmation set here, otherwise a re-spoken command such as
 * "backup karo" would be mistaken for a "yes" while a confirmation is waiting.
 *
 * Pure Kotlin (no Android imports) so it compiles and runs in deterministic
 * JVM tests.
 */
object VoiceUtterance {

    private val NEG_WORDS: Set<String> = setOf(
        "nahi", "nahin", "nhi", "nah", "na", "no", "nope", "not", "none",
        "never", "cancel", "chhod", "chhodo", "chhoddo", "chhor", "mat", "mt",
        "rehne", "rehna", "rok", "roko", "band", "bandh", "stop", "hata", "hatao",
        "remove", "venda", "venam", "illa", "illai", "yillai",
        "नहीं", "नही", "न", "मत", "छोड़", "छोड़ो", "रोक", "बंद", "हटाओ",
        "வேண்டாம்", "வேணாம்", "இல்லை", "யில்லை",
        "نہیں", "نھی", "نا", "مت", "بند", "روک"
    )

    private val POS_WORDS: Set<String> = setOf(
        "haan", "haanji", "haa", "han", "ha", "yes", "yess", "yep", "yeah", "ya",
        "ok", "okay", "okey", "theek", "thik", "sahi", "sure", "bilkul", "ji",
        "chalega", "chalo", "ama", "sari",
        "हाँ", "हां", "हांजी", "जी", "ठीक", "सही", "बिल्कुल",
        "ஆம்", "ஆமாம்", "சரி",
        "ہاں", "جی", "ٹھیک", "اوکے", "بلكل"
    )

    /** Too many words = not a yes/no answer (it is a sentence) → fewer false "yes". */
    private const val MAX_AFFIRM_WORDS = 4

    /**
     * "Stop talking right now" words. Deliberately short and few — a barge-in
     * must not fire on a normal sentence that happens to contain "bas".
     * "rok/band/stop" also appear in [NEG_WORDS], so when a confirmation is
     * open the negation rule already cancels the action.
     */
    private val BARGE_IN_WORDS: Set<String> = setOf(
        "ruko", "ruk", "rukjao", "ruko", "rukja", "chup", "choop", "chupo", "stop", "bas",
        "khamosh", "silence", "quiet",
        "रुको", "रुक", "चुप", "चुप्प", "बस", "खामोश",
        "رکو", "چپ", "بس", "خاموش",
        "நிறுத்து", "சத்தம்"
    )

    private const val MAX_BARGE_IN_WORDS = 3

    /** "ruko" / "chup" / "stop" — the user wants the phone to shut up. */
    fun isBargeIn(text: String): Boolean {
        val tokens = tokens(text)
        if (tokens.isEmpty() || tokens.size > MAX_BARGE_IN_WORDS) return false
        return tokens.any { it in BARGE_IN_WORDS }
    }

    fun classifyYesNo(text: String): YesNoAnswer {
        val tokens = tokens(text)
        if (tokens.isEmpty()) return YesNoAnswer.UNKNOWN
        if (tokens.any { it in NEG_WORDS }) return YesNoAnswer.NO
        if (tokens.any { it in POS_WORDS } && tokens.size <= MAX_AFFIRM_WORDS) {
            return YesNoAnswer.YES
        }
        return YesNoAnswer.UNKNOWN
    }

    fun isNegation(text: String): Boolean = classifyYesNo(text) == YesNoAnswer.NO

    fun isAffirmation(text: String): Boolean = classifyYesNo(text) == YesNoAnswer.YES

    /** Lowercase + split on non-letters (same tokenization as VoiceWordMatcher). */
    fun tokens(text: String): List<String> =
        text.lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}]+"))
            .filter { it.isNotBlank() }
}
