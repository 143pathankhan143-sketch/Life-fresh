package com.example.voice

import java.util.Locale

/** How many spoken confirmations an action needs before it may execute. */
enum class ConfirmLevel(val steps: Int) {
    SINGLE(1),
    DOUBLE(2),
    TRIPLE(3)
}

/** One step of the confirmation dialogue. */
sealed class ConfirmStep {
    /** Ask the user the next prompt (speak it via TTS). */
    data class Ask(val prompt: String) : ConfirmStep()

    /** Every required confirmation was given — execute the action. */
    object Confirmed : ConfirmStep()

    /** The user said no (in any language) — cancel. */
    object Cancelled : ConfirmStep()

    /** TRIPLE final step heard a wrong phrase — re-ask the exact-phrase prompt. */
    data class RetryExactPhrase(val prompt: String) : ConfirmStep()

    /** Heard something that is not a yes/no and not the exact phrase — keep waiting. */
    object Waiting : ConfirmStep()
}

/** The ONLY phrases accepted as the third (final) cloud-delete confirmation. */
val TRIPLE_ACCEPT_PHRASES: Set<String> = setOf("haan delete karo", "yes delete")

/**
 * Pure-Kotlin confirmation state machine (no Android imports).
 *
 * Guarantees from the spec:
 *  - SINGLE/DOUBLE levels advance on a spoken "haan" (any language).
 *  - Negation ALWAYS wins: "nahi" (any language) cancels immediately.
 *  - TRIPLE's final step ignores a plain "haan" — ONLY the exact phrase
 *    "haan delete karo" / "yes delete" confirms. The controller builds the
 *    prompts (see [VoiceConfirmPrompts]) and [VoiceConfirmGate] enforces the
 *    mechanics, so even a bug in the prompt wording can never weaken the gate.
 */
class VoiceConfirmGate(
    private val exactAcceptPhrases: Set<String> = TRIPLE_ACCEPT_PHRASES
) {
    var isActive: Boolean = false
        private set

    /** 1-based index of the step we are waiting on (1..level.steps). */
    val currentStep: Int get() = step + 1

    private var level: ConfirmLevel = ConfirmLevel.SINGLE
    private var prompts: List<String> = emptyList()
    private var step: Int = 0

    /** Starts a confirmation for [level]; [prompts] must have exactly `level.steps` entries. Returns the first prompt to speak. */
    fun begin(level: ConfirmLevel, prompts: List<String>): String {
        require(level.steps >= 1) { "ConfirmLevel must have at least 1 step" }
        require(prompts.size == level.steps) {
            "prompts.size (${prompts.size}) must equal level.steps (${level.steps})"
        }
        this.level = level
        this.prompts = prompts
        this.step = 0
        isActive = true
        return prompts.first()
    }

    /** Feeds one heard utterance; returns the next step of the dialogue. */
    fun feed(rawText: String): ConfirmStep {
        if (!isActive) return ConfirmStep.Waiting
        val text = normalize(rawText)
        if (text.isBlank()) return ConfirmStep.Waiting

        // Negation ALWAYS wins → cancel the whole thing.
        if (VoiceUtterance.isNegation(text)) {
            reset()
            return ConfirmStep.Cancelled
        }

        val isFinal = step == level.steps - 1

        // TRIPLE final step: ONLY the exact phrase deletes (a bare "haan" is NOT enough).
        if (level == ConfirmLevel.TRIPLE && isFinal) {
            return if (text in exactAcceptPhrases) {
                reset()
                ConfirmStep.Confirmed
            } else {
                ConfirmStep.RetryExactPhrase(prompts[step])
            }
        }

        return if (VoiceUtterance.isAffirmation(text)) {
            if (isFinal) {
                reset()
                ConfirmStep.Confirmed
            } else {
                step++
                ConfirmStep.Ask(prompts[step])
            }
        } else {
            ConfirmStep.Waiting
        }
    }

    /** Abandons any in-progress confirmation (barge-in / cancel from outside). */
    fun reset() {
        isActive = false
        step = 0
        prompts = emptyList()
    }

    private fun normalize(s: String): String =
        s.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[\\u0021-\\u002F\\u003A-\\u0040\\u005B-\\u0060\\u007B-\\u007E]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}

/**
 * Default Hinglish/English confirmation prompts. Milestone M6 swaps these for
 * fully localized strings from res/values-{hi,ta,ur}. GATE MECHANICS never
 * depend on the prompt wording — only on haan/nahi + the exact final phrase.
 */
object VoiceConfirmPrompts {
    fun cloudDelete(): List<String> = listOf(
        "Cloud backup hamesha ke liye delete kar du?",
        "Ye wapas nahi aayega, pakka haan?",
        "Aakhri baar — 'haan delete karo' bolo."
    )
    fun localDelete(): List<String> = listOf(
        "Local data delete kar du?",
        "Pakka? fir se haan bolo."
    )
    fun restore(): List<String> = listOf("Backup wapas la du? haan ya nahi bolo.")
    fun backup(): List<String> = listOf("Cloud backup kar du? haan ya nahi bolo.")
    fun single(question: String): List<String> = listOf(question)
    fun double(first: String): List<String> = listOf(first, "Pakka? fir se haan bolo.")
}
