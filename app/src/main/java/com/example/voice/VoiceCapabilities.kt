package com.example.voice

/**
 * An app-level capability the voice layer can actually perform today.
 *
 * The loop only offers a command when its capability is switched on, so a
 * half-built milestone can never half-execute something: an unavailable
 * command answers "ye abhi voice se nahi hota" instead of pretending, and
 * destructive commands stay switched OFF until their milestone wires the real
 * executor (M4).
 */
enum class VoiceCapability {
    NAVIGATE,
    BACK,
    ASK_AI,
    HELP,
    SEARCH_LEADS,
    BACKUP,
    RESTORE,
    DELETE_LOCAL,
    DELETE_CLOUD,
    SET_LANGUAGE,
    SET_VOICE,
    BOLO_MODE
}

/** Which capability a parsed command needs. */
fun VoiceCommand.capability(): VoiceCapability? = when (this) {
    is VoiceCommand.Navigate -> VoiceCapability.NAVIGATE
    VoiceCommand.Back -> VoiceCapability.BACK
    is VoiceCommand.AskAI -> VoiceCapability.ASK_AI
    VoiceCommand.Help -> VoiceCapability.HELP
    is VoiceCommand.SearchLeads -> VoiceCapability.SEARCH_LEADS
    is VoiceCommand.Backup -> VoiceCapability.BACKUP
    is VoiceCommand.Restore -> VoiceCapability.RESTORE
    VoiceCommand.DeleteLocalData -> VoiceCapability.DELETE_LOCAL
    VoiceCommand.DeleteCloudBackup -> VoiceCapability.DELETE_CLOUD
    is VoiceCommand.SetLanguage -> VoiceCapability.SET_LANGUAGE
    is VoiceCommand.SetVoice -> VoiceCapability.SET_VOICE
    is VoiceCommand.SetBoloMode -> VoiceCapability.BOLO_MODE
    VoiceCommand.Unknown -> null
}

/**
 * Capability sets per milestone (work.md §11). Each stage is a superset of the
 * one before it, so shipping a stage only ever ADDS abilities.
 */
object VoiceCapabilities {
    /** M2 — safe foundation: navigation, back, questions, help. */
    val M2: Set<VoiceCapability> = setOf(
        VoiceCapability.NAVIGATE,
        VoiceCapability.BACK,
        VoiceCapability.ASK_AI,
        VoiceCapability.HELP
    )

    /** M3 — lead search/filter on the Leads screen. */
    val M3: Set<VoiceCapability> = M2 + VoiceCapability.SEARCH_LEADS

    /** M4 — backup/restore + the guarded deletes (triple gate for cloud). */
    val M4: Set<VoiceCapability> = M3 + setOf(
        VoiceCapability.BACKUP,
        VoiceCapability.RESTORE,
        VoiceCapability.DELETE_LOCAL,
        VoiceCapability.DELETE_CLOUD
    )

    /** M5 — settings: language, AI voice, Bolo mode. */
    val M5: Set<VoiceCapability> = M4 + setOf(
        VoiceCapability.SET_LANGUAGE,
        VoiceCapability.SET_VOICE,
        VoiceCapability.BOLO_MODE
    )

    /** Everything. */
    val ALL: Set<VoiceCapability> = M5
}
