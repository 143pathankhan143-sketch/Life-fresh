package com.example.voice

import java.util.Locale

/**
 * Rule-based, multilingual voice command parser.
 *
 * Pure Kotlin (no Android imports) so it compiles and runs against a plain JVM
 * Kotlin toolchain in deterministic tests.
 *
 * Lead operations are deliberately NOT parsed here: they become
 * [VoiceCommand.AskAI] — the full spoken text is forwarded to the AI chat which
 * already understands "Ramesh complete karo", "[LEAD_UPDATE]…", etc. via the
 * hidden-block protocol. This parser only classifies app-level commands and the
 * routing decision.
 */
object VoiceCommandParser {

    // ---------------- navigation ----------------
    private val OPEN_VERBS = setOf(
        "dikhao", "dikhaye", "dikha", "kholo", "show", "open", "jao",
        "खोलो", "दिखाओ", "دکھاؤ", "کھولو"
    )
    private val LEAD_TOKENS = setOf(
        "lead", "leads", "client", "clients", "customer", "customers",
        "grahak", "gahak", "gaahak", "ग्राहक", "क्लाइंट", "लीड", "لید", "گاہک", "کلیئنٹ"
    )
    private val LEAD_EDIT_TOKENS = setOf(
        "banao", "bana", "banaye", "add", "jodo", "जोड़ो", "जोड़", "badlo",
        "badal", "change", "update", "complete", "pending", "remind",
        "reminder", "note", "likho", "लिखो", "call", "archive", "status", "स्टेटस"
    )
    private val DASHBOARD_TOKENS = setOf("dashboard", "डैशबोर्ड", "home", "होम")
    private val HISTORY_TOKENS = setOf("history", "histories", "इतिहास", "ہسٹری")
    private val OLD_TOKENS = setOf("purani", "purane", "purana", "old", "पुरानी", "पुराने", "پرانى", "پرانے")
    private val CHAT_TOKENS = setOf("chat", "चैट", "چیٹ", "baat", "bat", "بات")
    private val AI_TOKENS = setOf("ai")
    private val REPORT_TOKENS = setOf("report", "reports", "रिपोर्ट", "रिपोर्ट्स", "ریپورٹ")
    private val SETTINGS_TOKENS = setOf("setting", "settings", "सेटिंग", "सेटिंग्स", "ترتیبات")

    // ---------------- back / ask / help ----------------
    private val BACK_TOKENS = setOf("back", "wapas", "wapis", "vapas", "peeche", "pichhe", "पीछे", "वापस", "वापिस", "واپس", "پیچھے")
    private val ASK_TOKENS = setOf("pucho", "puch", "pooch", "ask", "पूछो", "پوچھو")
    private val HELP_TOKENS = setOf("help", "madad", "सहायता", "मदद", "مدد", "commands")

    // ---------------- backup / restore / delete ----------------
    private val BACKUP_TOKENS = setOf("backup", "backups", "बैकअप", "بیکاپ")
    private val RESTORE_TOKENS = setOf("restore", "restor", "بازیابی")
    private val WAPAS_TOKENS = setOf("wapas", "wapis", "vapas", "वापस", "वापिस", "واپس")
    private val BRING_TOKENS = setOf("lao", "la", "lana", "लाओ", "लाना", "لاؤ", "لانا")
    private val CLOUD_TOKENS = setOf("cloud", "क्लाउड", "کلاوڈ", "drive", "ड्राइव", "ڈرائیو", "google", "गूगल", "گوگل", "firestore")
    private val LOCAL_TOKENS = setOf("local", "لوکل", "लोकल", "phone", "फ़ोन", "फोन", "فون", "mobile", "मोबाइल", "موبائل")
    private val ALL_DATA_TOKENS = setOf("saara", "sara", "pura", "poora", "sab", "sabka", "sabhi", "सारा", "पूरा", "सब", "تمام", "سب", "کل")
    private val RESET_TOKENS = setOf("reset", "रीसेट", "ریسیٹ", "wipe")
    private val DELETE_TOKENS = setOf("delete", "del", "डिलीट", "ڈیلیٹ", "حذف", "hatao", "हटाओ", "ہٹاؤ", "मिटाओ", "mitao", "mita", "hata", "remove", "remov")
    private val CLEAR_TOKENS = setOf("clear", "khatam", "खत्म", "ख़त्म", "साफ", "صاف", "ختم", "wipe")

    // ---------------- language ----------------
    private val LANG_INDICATOR = setOf("bhasha", "bhaasha", "भाषा", "language", "زبان", "لینگویج")
    private val LANG_VERB = setOf("karo", "करो", "kare", "badlo", "बदलो", "change")
    private val LANG_HINDI = setOf("hindi", "हिंदी", "हिन्दी", "hindee")
    private val LANG_TAMIL = setOf("tamil", "तमिल", "தமிழ்", "تمل")
    private val LANG_URDU = setOf("urdu", "उर्दू", "اردو")
    private val LANG_ENGLISH = setOf("english", "angrezi", "अंग्रेजी", "इंग्लिश", "انگریزی")

    // ---------------- bolo / voice ----------------
    private val BOLO_TOKENS = setOf("bolo", "बोलो", "بولو")
    private val BOLO_MODE_TOKENS = setOf("mode", "mud", "मोड", "موڈ")
    private val BOLO_ON_TOKENS = setOf("on", "chaloo", "chalu", "chalao", "shuru", "start", "kholo", "karo", "enable", "चालू", "शुरू", "آن", "کرو")
    private val BOLO_OFF_TOKENS = setOf("off", "band", "bandh", "rok", "roko", "stop", "बंद", "روک", "بند")
    private val VOICE_TOKENS = setOf("awaz", "aawaz", "आवाज़", "आवाज", "voice", "آواز")

    // ---------------- search ----------------
    private val SEARCH_TOKENS = setOf("dhoondo", "dhundho", "dhoondh", "dhundh", "search", "find", "khojo", "khoj", "ढूंढो", "खोजो", "ڈھونڈو")
    private val PENDING_TOKENS = setOf("pending", "बकाया", "लंबित", "लम्बित")

    /**
     * Parses [rawText] into a [VoiceCommand]. [knownVoices] (e.g. the Gemini TTS
     * voice names) lets the parser recognise "Kore awaz lagao" precisely;
     * pass an empty set when voice selection is not relevant.
     */
    fun parse(rawText: String, knownVoices: Set<String> = emptySet()): VoiceCommand {
        val original = rawText.trim()
        if (original.isEmpty()) return VoiceCommand.Unknown
        val norm = normalize(original)
        val toks = tokens(norm)

        // 1. Help
        if (hasAny(toks, HELP_TOKENS) || norm.contains("bol sakte") || norm.contains("kar sakte")) {
            return VoiceCommand.Help
        }
        // 2. Language
        langCommand(toks)?.let { return it }
        // 3. Bolo mode
        if (hasAny(toks, BOLO_TOKENS) &&
            (hasAny(toks, BOLO_MODE_TOKENS) || hasAny(toks, BOLO_ON_TOKENS) || hasAny(toks, BOLO_OFF_TOKENS))
        ) {
            return VoiceCommand.SetBoloMode(enabled = !hasAny(toks, BOLO_OFF_TOKENS))
        }
        // 4. AI voice
        voiceCommand(toks, knownVoices)?.let { return it }
        // 5. Cloud backup delete (BEFORE generic backup/delete)
        if (hasAny(toks, CLOUD_TOKENS) && hasAny(toks, DELETE_TOKENS)) {
            return VoiceCommand.DeleteCloudBackup
        }
        // 6. Local data delete
        if ((hasAny(toks, LOCAL_TOKENS) || hasAny(toks, ALL_DATA_TOKENS) || hasAny(toks, RESET_TOKENS)) &&
            (hasAny(toks, DELETE_TOKENS) || hasAny(toks, CLEAR_TOKENS))
        ) {
            return VoiceCommand.DeleteLocalData
        }
        // 7. Restore (before Backup so "backup wapas lao" == restore)
        if (isRestorePhrase(toks)) return VoiceCommand.Restore(backupTarget(toks, norm))
        // 8. Backup
        if (hasAny(toks, BACKUP_TOKENS)) return VoiceCommand.Backup(backupTarget(toks, norm))
        // 9. Search
        val isPending = hasAny(toks, PENDING_TOKENS)
        if (hasAny(toks, SEARCH_TOKENS)) return VoiceCommand.SearchLeads(original, isPending)
        if (isPending && hasAny(toks, OPEN_VERBS)) return VoiceCommand.SearchLeads("pending", true)
        // 10. Ask AI
        if (hasAny(toks, ASK_TOKENS)) return VoiceCommand.AskAI(original)
        // 11. Back
        if (hasAny(toks, BACK_TOKENS)) return VoiceCommand.Back
        // 12. Navigation
        navigateCommand(toks)?.let { return it }
        // 13. Generic delete (a lead) -> AI chat decides archive/delete + card
        if (hasAny(toks, DELETE_TOKENS)) return VoiceCommand.AskAI(original)
        // 14. Standalone yes/no with nothing pending = no command
        if (VoiceUtterance.classifyYesNo(norm) != YesNoAnswer.UNKNOWN) return VoiceCommand.Unknown
        // 15. Anything else -> forward to AI chat
        return VoiceCommand.AskAI(original)
    }

    // ---------------- helpers ----------------

    private fun tokens(s: String): List<String> = VoiceUtterance.tokens(s)

    private fun hasAny(toks: List<String>, words: Set<String>): Boolean = toks.any { it in words }

    private fun normalize(s: String): String = s.lowercase(Locale.ROOT).trim()

    private fun langCommand(toks: List<String>): VoiceCommand? {
        val code = when {
            hasAny(toks, LANG_HINDI) -> "hi"
            hasAny(toks, LANG_TAMIL) -> "ta"
            hasAny(toks, LANG_URDU) -> "ur"
            hasAny(toks, LANG_ENGLISH) -> "en"
            else -> return null
        }
        val context = hasAny(toks, LANG_INDICATOR) || hasAny(toks, LANG_VERB) || toks.size == 1
        return if (context) VoiceCommand.SetLanguage(code) else null
    }

    private fun voiceCommand(toks: List<String>, knownVoices: Set<String>): VoiceCommand? {
        if (!hasAny(toks, VOICE_TOKENS)) return null
        val hit = knownVoices.firstOrNull { v -> hasAny(toks, setOf(v.lowercase(Locale.ROOT))) }
        return if (hit != null) VoiceCommand.SetVoice(hit) else VoiceCommand.SetVoice("")
    }

    private fun isRestorePhrase(toks: List<String>): Boolean =
        hasAny(toks, RESTORE_TOKENS) || (hasAny(toks, WAPAS_TOKENS) && hasAny(toks, BRING_TOKENS))

    private fun backupTarget(toks: List<String>, norm: String): BackupTarget {
        if (hasAny(toks, LOCAL_TOKENS) || norm.contains("phone") || norm.contains("file")) {
            return BackupTarget.LOCAL
        }
        return BackupTarget.CLOUD
    }

    private fun navigateCommand(toks: List<String>): VoiceCommand? {
        if (hasAny(toks, DASHBOARD_TOKENS)) return VoiceCommand.Navigate(VocalDestination.DASHBOARD)
        if (hasAny(toks, HISTORY_TOKENS) || (hasAny(toks, OLD_TOKENS) && hasAny(toks, CHAT_TOKENS))) {
            return VoiceCommand.Navigate(VocalDestination.AI_HISTORY)
        }
        val leadEdit = hasAny(toks, LEAD_EDIT_TOKENS)
        if (hasAny(toks, LEAD_TOKENS) && !leadEdit && (hasAny(toks, OPEN_VERBS) || toks.size == 1)) {
            return VoiceCommand.Navigate(VocalDestination.LEADS)
        }
        if ((hasAny(toks, AI_TOKENS) || hasAny(toks, CHAT_TOKENS)) && (hasAny(toks, OPEN_VERBS) || toks.size == 1)) {
            return VoiceCommand.Navigate(VocalDestination.AI_CHAT)
        }
        if (hasAny(toks, REPORT_TOKENS)) return VoiceCommand.Navigate(VocalDestination.REPORTS)
        if (hasAny(toks, SETTINGS_TOKENS)) return VoiceCommand.Navigate(VocalDestination.SETTINGS)
        return null
    }
}
