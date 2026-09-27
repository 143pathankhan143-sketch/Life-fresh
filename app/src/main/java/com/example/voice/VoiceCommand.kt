package com.example.voice

/**
 * Where a voice command wants to take the user.
 */
enum class VocalDestination {
    DASHBOARD,
    LEADS,
    AI_CHAT,
    AI_HISTORY,
    REPORTS,
    SETTINGS
}

/** Which backup store a backup/restore command targets. */
enum class BackupTarget { CLOUD, LOCAL }

/**
 * A parsed voice command, produced by [VoiceCommandParser.parse].
 *
 * Design: this layer deliberately does NOT re-implement lead understanding.
 * Any lead operation (add/update/status/delete/bulk) or a general question is
 * [AskAI] — the full spoken text is forwarded to the AI chat, whose LEAD-*
 * hidden-block protocol + confirmation cards already exist and are tested.
 * Only app-level actions (navigation, backup/restore, settings, language,
 * voice, Bolo) are modelled as direct commands.
 */
sealed class VoiceCommand {
    /** "leads dikhao" / "dashboard dikhao" / "settings kholo" ... */
    data class Navigate(val destination: VocalDestination) : VoiceCommand()

    /** "wapas jao" / "back" */
    object Back : VoiceCommand()

    /** "Ramesh dhoondo" / "pending wale dikhao" — open Leads, apply search/filter. */
    data class SearchLeads(val query: String, val pendingOnly: Boolean = false) : VoiceCommand()

    /** "backup karo" / "local backup karo" */
    data class Backup(val target: BackupTarget) : VoiceCommand()

    /** "restore karo" / "backup wapas lao" */
    data class Restore(val target: BackupTarget) : VoiceCommand()

    /** "local data delete karo" — DOUBLE confirmation. */
    object DeleteLocalData : VoiceCommand()

    /** "cloud backup delete karo" — TRIPLE confirmation (exact phrase last). */
    object DeleteCloudBackup : VoiceCommand()

    /** "bhasha hindi karo" — code is en/hi/ta/ur. */
    data class SetLanguage(val languageCode: String) : VoiceCommand()

    /** "Kore awaz lagao" — [voiceName] blank = open the voice picker. */
    data class SetVoice(val voiceName: String) : VoiceCommand()

    /** "bolo mode on/off karo" */
    data class SetBoloMode(val enabled: Boolean) : VoiceCommand()

    /** "AI se pucho ..." — open AI chat and send [prompt]. */
    data class AskAI(val prompt: String) : VoiceCommand()

    /** "help" / "kya kya bol sakte ho" */
    object Help : VoiceCommand()

    /** Blank/noise only — nothing usable heard. */
    object Unknown : VoiceCommand()
}

/**
 * The ONLY two AI voices the app offers (user decision, 2026-09-27): one female,
 * one male. The 28 other Gemini studio voices were removed because an illiterate
 * user cannot pick from a list of 30 - and a wrong pick is worse than no pick.
 *
 * Kept here (pure Kotlin, no Android) so the parser, the cloud TTS client and
 * the tests all agree on the same two ids.
 */
object VoiceIds {
    /** Female — Gemini "Kore" (clear, firm). */
    const val FEMALE: String = "Kore"

    /** Male — Gemini "Orus" (firm, factual). */
    const val MALE: String = "Orus"

    val ALL: List<String> = listOf(FEMALE, MALE)

    /** Migration guard: any older stored name becomes the female voice. */
    fun normalise(name: String): String {
        val trimmed = name.trim()
        if (trimmed.equals("auto", ignoreCase = true)) return FEMALE
        return ALL.firstOrNull { it.equals(trimmed, ignoreCase = true) } ?: FEMALE
    }
}
