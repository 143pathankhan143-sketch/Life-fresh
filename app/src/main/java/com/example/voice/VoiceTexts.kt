package com.example.voice

import java.util.Locale

/**
 * Every string the voice layer speaks, as a stable key.
 *
 * The host resolves keys through [VoiceTexts]: the Android build reads them
 * from strings.xml in the app language (values, values-hi, values-ta,
 * values-ur), while tests use [VoiceTextDefaults.hinglish].
 * Keeping the keys here means one place to check that all four locale files
 * really contain every line the voice flow can say.
 */
object VoiceTextKeys {
    /** "Open the %1$s screen? Say yes or no." */
    const val NAV_PROMPT = "vc_nav_prompt"

    /** "%1$s screen is open." */
    const val NAV_DONE = "vc_nav_done"

    /** "%1$d pending, %2$d leads in total." */
    const val NAV_COUNTS = "vc_nav_counts"

    /** "Went back to the previous screen." */
    const val BACK_DONE = "vc_back_done"

    /** "Go back to the previous screen? Say yes or no." */
    const val BACK_PROMPT = "vc_back_prompt"

    /** "Is that okay? Say yes or no." — generic single confirmation. */
    const val CONFIRM_OK = "vc_confirm_ok"

    /** "Okay, cancelled." */
    const val CANCELLED = "vc_cancelled"

    /** "Say yes or no." */
    const val SAY_YES_OR_NO = "vc_say_yes_or_no"

    /** "I could not hear anything. Please say it again." */
    const val HEARD_NOTHING = "vc_heard_nothing"

    /** "Voice is not available on this phone..." */
    const val MIC_UNAVAILABLE = "vc_mic_unavailable"

    /** "Stopped." (barge-in) */
    const val STOPPED = "vc_stopped"

    /** "This is not voice-enabled yet." */
    const val NOT_YET = "vc_not_yet"

    /** "Are you sure? Say yes again." */
    const val PAKKA = "vc_pakka"

    /** "Start the cloud backup? Say yes or no." */
    const val BACKUP_PROMPT = "vc_backup_prompt"

    /** "Restore from backup? Say yes or no." */
    const val RESTORE_PROMPT = "vc_restore_prompt"

    /** "Delete local data? Say yes or no." */
    const val LOCAL_DELETE_PROMPT = "vc_local_delete_prompt"

    /** "Delete the cloud backup forever?" */
    const val CLOUD_DELETE_PROMPT_1 = "vc_cloud_delete_1"

    /** "This cannot be undone. Are you sure? Say yes." */
    const val CLOUD_DELETE_PROMPT_2 = "vc_cloud_delete_2"

    /** "Last time — say 'haan delete karo'." (teaches the exact phrase) */
    const val CLOUD_DELETE_PROMPT_3 = "vc_cloud_delete_3"

    /** Short list of things the user can say. */
    const val HELP = "vc_help"

    /** "Searching %1$s." (M3) */
    const val SEARCH_PROMPT = "vc_search_prompt"

    /** All keys — used by the locale coverage test. */
    val ALL: List<String> = listOf(
        NAV_PROMPT, NAV_DONE, NAV_COUNTS, BACK_DONE, BACK_PROMPT, CONFIRM_OK,
        CANCELLED, SAY_YES_OR_NO,
        HEARD_NOTHING, MIC_UNAVAILABLE, STOPPED, NOT_YET, PAKKA,
        BACKUP_PROMPT, RESTORE_PROMPT, LOCAL_DELETE_PROMPT,
        CLOUD_DELETE_PROMPT_1, CLOUD_DELETE_PROMPT_2, CLOUD_DELETE_PROMPT_3,
        HELP, SEARCH_PROMPT
    )
}

/**
 * Text lookup for the voice layer. Implementations must never throw: an
 * unknown key comes back as the key itself so a missing translation can not
 * crash a voice turn.
 */
interface VoiceTexts {
    /** [args] fill %1$s / %1$d style placeholders in the template. */
    fun get(key: String, vararg args: Any): String

    /** Localized name of a screen: "Leads" / "लीड्स" / "லீட்கள்" / "لیڈز". */
    fun destinationName(destination: VocalDestination): String
}

/** Simple [VoiceTexts] over in-memory maps. */
class MapVoiceTexts(
    private val values: Map<String, String>,
    private val destinationNames: Map<VocalDestination, String> = emptyMap()
) : VoiceTexts {

    override fun get(key: String, vararg args: Any): String {
        val template = values[key] ?: return key
        if (args.isEmpty()) return template
        return try {
            String.format(Locale.ROOT, template, *args)
        } catch (e: Throwable) {
            template
        }
    }

    override fun destinationName(destination: VocalDestination): String =
        destinationNames[destination] ?: destination.name.lowercase(Locale.ROOT).replace('_', ' ')
}

/**
 * English/Hinglish fallback used by tests and by the Android resolver whenever
 * a resource is missing, so the voice flow always has something to say.
 */
object VoiceTextDefaults {

    private val HINGLISH: Map<String, String> = mapOf(
        VoiceTextKeys.NAV_PROMPT to "Open the %1\$s screen? Say yes or no.",
        VoiceTextKeys.NAV_DONE to "%1\$s screen is open.",
        VoiceTextKeys.NAV_COUNTS to "%1\$d pending, %2\$d leads in total.",
        VoiceTextKeys.BACK_DONE to "Went back to the previous screen.",
        VoiceTextKeys.BACK_PROMPT to "Go back to the previous screen? Say yes or no.",
        VoiceTextKeys.CONFIRM_OK to "Is that okay? Say yes or no.",
        VoiceTextKeys.CANCELLED to "Okay, cancelled.",
        VoiceTextKeys.SAY_YES_OR_NO to "Say yes or no.",
        VoiceTextKeys.HEARD_NOTHING to "I could not hear anything. Please say it again.",
        VoiceTextKeys.MIC_UNAVAILABLE to
            "Voice is not working on this phone. You can type instead.",
        VoiceTextKeys.STOPPED to "Stopped.",
        VoiceTextKeys.NOT_YET to
            "This is not voice-enabled yet. You can do it on the screen.",
        VoiceTextKeys.PAKKA to "Are you sure? Say yes again.",
        VoiceTextKeys.BACKUP_PROMPT to "Start the cloud backup? Say yes or no.",
        VoiceTextKeys.RESTORE_PROMPT to "Restore from backup? Say yes or no.",
        VoiceTextKeys.LOCAL_DELETE_PROMPT to "Delete local data? Say yes or no.",
        VoiceTextKeys.CLOUD_DELETE_PROMPT_1 to "Delete the cloud backup forever?",
        VoiceTextKeys.CLOUD_DELETE_PROMPT_2 to "This cannot be undone. Are you sure? Say yes.",
        VoiceTextKeys.CLOUD_DELETE_PROMPT_3 to "Last time — say 'haan delete karo'.",
        VoiceTextKeys.HELP to
            "You can say: leads dikhao, dashboard, backup karo, settings kholo. " +
                "Or simply ask your question.",
        VoiceTextKeys.SEARCH_PROMPT to "Searching %1\$s."
    )

    private val NAMES: Map<VocalDestination, String> = mapOf(
        VocalDestination.DASHBOARD to "Dashboard",
        VocalDestination.LEADS to "Leads",
        VocalDestination.AI_CHAT to "AI",
        VocalDestination.AI_HISTORY to "AI history",
        VocalDestination.REPORTS to "Reports",
        VocalDestination.SETTINGS to "Settings"
    )

    /** The Hinglish/English fallback set. */
    fun hinglish(): VoiceTexts = MapVoiceTexts(HINGLISH, NAMES)

    /** Raw map — handy for tests. */
    fun hinglishMap(): Map<String, String> = HINGLISH
}
