package com.example.voice

/**
 * Something the app should SAY. Kept as a small sealed type instead of a plain
 * String so the loop can stay pure: live data (how many leads are pending,
 * etc.) is filled in by the host at execution time, not by the loop.
 */
sealed class VoiceSpeech {
    /** A ready-to-speak sentence. */
    data class Literal(val text: String) : VoiceSpeech()

    /**
     * Navigation finished — the host speaks the localized
     * "[screen] khul gaya" line and (on Leads/Dashboard) appends the live
     * pending/total counts it can read from the ViewModel.
     */
    data class NavDone(val destination: VocalDestination) : VoiceSpeech()
}

/** Why the mic was opened again. */
enum class ListenReason {
    /** Waiting for a command, or for "haan/nahi" on an active confirmation. */
    COMMAND,

    /** Continuous (Bolo) mode: listening for the next command after a reply. */
    FOLLOW_UP
}

/**
 * One step of the voice turn script emitted by [VoiceLoop].
 *
 * The host MUST execute the list strictly in order, suspending on
 * [Speak] until the audio finishes. [StartListening], [StopListening] and
 * [SessionEnded] are always LAST in a list (the turn ends there).
 */
sealed class VoiceEffect {
    /** Speak this, waiting for playback to finish before the next effect. */
    data class Speak(val speech: VoiceSpeech) : VoiceEffect()

    /** Barge-in: stop any audio that is playing right now. */
    object StopSpeaking : VoiceEffect()

    /** Open the mic. Terminal effect. */
    data class StartListening(val reason: ListenReason) : VoiceEffect()

    /** Close the mic. Terminal effect. */
    object StopListening : VoiceEffect()

    /** Switch to [destination] (host maps it through [VoiceNavigator]). */
    data class Navigate(val destination: VocalDestination) : VoiceEffect()

    /** Go one screen back. */
    object Back : VoiceEffect()

    /** Open the AI chat and send [prompt]; the chat owns the mic from here. */
    data class AskAI(val prompt: String) : VoiceEffect()

    /** Show ([prompt] non-null) or hide (null) the visual confirmation card. */
    data class ConfirmCard(val prompt: String?) : VoiceEffect()

    /** The gate said yes — run this command for real (M3/M4 executors). */
    data class Execute(val command: VoiceCommand) : VoiceEffect()

    /** The voice session is over: release the mic and reset the UI. */
    object SessionEnded : VoiceEffect()
}
