package com.example.ai.chat.config

import com.example.BuildConfig

/**
 * Provides access to AI API configurations securely.
 * Keys are loaded from BuildConfig (populated via .env / Secrets panel in AI Studio)
 * and never hardcoded in source files.
 */
object AIConfig {

    /**
     * Current Gemini text-generation models, ordered by preference
     * (newest stable Flash first, slower/more expensive models last).
     *
     * IMPORTANT: Google frequently deprecates model endpoints (e.g.
     * gemini-2.0-flash was shut down in 2026). If chat starts failing with
     * HTTP 404 "model no longer available", update this list with the
     * current models from https://ai.google.dev/gemini-api/docs/models.
     */
    /**
     * Free-tier text models, lowest contention first (verified 2026-09-27
     * against https://ai.google.dev/gemini-api/docs/deprecations):
     *  - 2.0 family: shut down 2026-06-01.
     *  - 2.5 family: restricted to projects that already used it (2026-09-18).
     *  - 3.5 Flash-Lite: stable, the high-volume/cheap tier - first choice
     *    because LifeFresh needs an answer more than it needs a genius, and a
     *    lite model is far less likely to be rate limited.
     *  - 3.5 Flash: stable; kept as the second entry so the two models have
     *    SEPARATE free-tier quotas and one 429 does not end the whole turn.
     * Never add a 2.x id back.
     */
    val GEMINI_TEXT_MODELS: List<String> = listOf(
        "gemini-3.5-flash-lite",
        "gemini-3.5-flash"
    )

    /**
     * Gemini native text-to-speech models (cloud voices for AI replies),
     * newest stable first. Same AI Studio key as chat, same free tier -
     * no new signup, no billing. If Google deprecates these the voice just
     * falls back to the phone's built-in TTS engine, so a stale entry here
     * is an inconvenience, not a crash. Refresh from
     * https://ai.google.dev/gemini-api/docs/speech-generation if 404s.
     */
    val GEMINI_TTS_MODELS: List<String> = listOf(
        // Verified alive + free of charge, 2026-09-27
        // (ai.google.dev/gemini-api/docs/pricing):
        //  3.8 Flash-Lite TTS - the "high-throughput, low-latency conversational"
        //  workhorse and the official replacement for the 3.1 preview. First
        //  choice because a voice agent lives or dies on the first word.
        "gemini-3.8-flash-lite-tts",
        // 3.8 Flash TTS - studio fidelity, free tier still free of charge.
        // Second try when the lite model refuses a request.
        "gemini-3.8-flash-tts",
        // Legacy safety net, LAST: the 3.1 preview that shipped before, so a
        // spoken reply can never die if the two 3.8 ids are rejected (for
        // example by a key that predates them). The retired 2.5 preview entry
        // was removed - it is closed to new projects and was never reached
        // anyway, because the client stopped after two models.
        "gemini-3.1-flash-tts-preview"
    )

    /**
     * Current Groq text models, ordered weak -> strong ON PURPOSE.
     *
     * WHY THIS ORDER: LifeFresh only needs a reliable AGENT - follow the system
     * instructions and emit the LEAD_* blocks - not a frontier genius. The
     * smaller / less popular models are far less contended, so they hit Groq's
     * free-tier limits much less often (30 RPM, 1K RPD, 8K TPM, 200K TPD *per
     * model*), which matters a lot for a hands-free voice app. A model that is
     * rate limited is useless even if it is smarter.
     *
     * VERIFIED 2026-09-27 against https://console.groq.com/docs/models and
     * https://console.groq.com/docs/deprecations. Decommissioned models must
     * NEVER be added back (see [GROQ_RETIRED_MODEL_IDS] - a test enforces it):
     *   - 2026-08-16  llama-3.1-8b-instant, llama-3.3-70b-versatile
     *   - 2026-09-14  qwen/qwen3.6-27b            (successor: qwen/qwen3.8-27b)
     *   - 2026-07-17  qwen/qwen3-32b, meta-llama/llama-4-scout-17b-16e-instruct
     *   - 2026-04-15  moonshotai/kimi-k2-instruct-0905
     *   - 2026-03     meta-llama/llama-4-maverick-17b-128e-instruct, llama-guard-4-12b
     *   - 2025        gemma2-9b-it, llama3-70b-8192, llama3-8b-8192, llama-3.2-*-preview
     *   - 2026-09-21  groq/compound, groq/compound-mini
     * Refresh from that page if chat starts failing. A retired id answers 400
     * "model_decommissioned" or 404, and the provider rotates to the next one.
     */
    val GROQ_TEXT_MODELS: List<String> = listOf(
        // Smallest alive model: 1000 t/s, cheapest, least contended.
        "openai/gpt-oss-20b",
        // Non-flagship 27B with no hidden-reasoning burn on our short prompts.
        // (Preview tier - Groq can withdraw preview models quickly, which the
        // provider survives by rotating on 400/404.)
        "qwen/qwen3.8-27b",
        // Flagship: most contended on the free tier, so it is the LAST resort.
        "openai/gpt-oss-120b"
    )

    /**
     * Groq model ids that have been DECOMMISSIONED. Kept as data (not just a
     * comment) so a unit test can fail the build if one ever comes back - a
     * dead id costs a wasted request on every single chat turn.
     */
    val GROQ_RETIRED_MODEL_IDS: List<String> = listOf(
        "llama-3.1-8b-instant",
        "llama-3.3-70b-versatile",
        "qwen/qwen3.6-27b",
        "qwen/qwen3-32b",
        "qwen-qwq-32b",
        "meta-llama/llama-4-scout-17b-16e-instruct",
        "meta-llama/llama-4-maverick-17b-128e-instruct",
        "moonshotai/kimi-k2-instruct-0905",
        "moonshotai/kimi-k2-instruct",
        "meta-llama/llama-guard-4-12b",
        "llama-guard-3-8b",
        "mistral-saba-24b",
        "gemma2-9b-it",
        "llama3-70b-8192",
        "llama3-8b-8192",
        "llama-3.2-1b-preview",
        "llama-3.2-3b-preview",
        "groq/compound",
        "groq/compound-mini"
    )

    /**
     * Groq bills a reasoning model's HIDDEN reasoning against max_tokens, so at
     * the default ("medium") effort a short reply can come back empty with
     * finish_reason=length. "low" keeps the answer inside the budget, answers
     * faster, and spends far fewer tokens - which is exactly what a voice agent
     * wants (the free tier is only 8K tokens/minute).
     *
     * The parameter is a HTTP 400 on models that can not reason, so it MUST be
     * sent only for the families below (source:
     * https://console.groq.com/docs/reasoning - gpt-oss and Qwen 3.6/3.8 27B).
     * Returns null when the model must not receive it.
     */
    fun groqReasoningEffort(model: String): String? {
        val id = model.trim().lowercase()
        val accepts = id.startsWith("openai/gpt-oss-") ||
            id.startsWith("gpt-oss-") ||
            id == "qwen/qwen3.6-27b" ||
            id == "qwen/qwen3.8-27b"
        return if (accepts) "low" else null
    }

    /** Headroom for the visible answer (plus a little reasoning) per request. */
    const val GROQ_MAX_COMPLETION_TOKENS: Int = 8192

    /**
     * Current OpenRouter text models, cheapest/lightest first.
     *
     * OpenRouter exposes hundreds of models through ONE key, and the '~' alias
     * slugs keep pointing at the newest version of a family (so a version bump
     * never 404s). Model families DO get retired though, so refresh from
     * https://openrouter.ai/models if OpenRouter starts failing.
     *
     * VERIFIED 2026-09-27:
     *   - "~deepseek/deepseek-flash-latest" (alias, released 2026-09-14)
     *   - "deepseek/deepseek-v4-flash"      (concrete id, ~$0.06/1M in)
     * The old "~deepseek/deepseek-pro-latest" entry was removed: it could not
     * be verified as an active slug, and a dead id wastes a request per turn.
     */
    val OPENROUTER_TEXT_MODELS: List<String> = listOf(
        "~deepseek/deepseek-flash-latest",
        "deepseek/deepseek-v4-flash"
    )

    @Volatile
    var customGeminiApiKeyProvider: (() -> String)? = null

    @Volatile
    var customGroqApiKeyProvider: (() -> String)? = null

    @Volatile
    var customOpenRouterApiKeyProvider: (() -> String)? = null

    @Volatile
    var customTavilyApiKeyProvider: (() -> String)? = null

    /** Returns whether Agent Mode is ON (AI executes lead actions without a confirmation card). */
    @Volatile
    var agentModeProvider: (() -> Boolean)? = null

    val groqApiKey: String
        get() {
            val custom = try {
                customGroqApiKeyProvider?.invoke()?.trim().orEmpty()
            } catch (e: Throwable) {
                ""
            }
            if (custom.isNotBlank()) return custom

            return try {
                val key = BuildConfig.GROQ_API_KEY
                if (key.isNotBlank() && key != "DEFAULT_GROQ_API_KEY" && key != "null") key.trim() else ""
            } catch (e: Throwable) {
                ""
            }
        }

    val geminiApiKey: String
        get() {
            val custom = try {
                customGeminiApiKeyProvider?.invoke()?.trim().orEmpty()
            } catch (e: Throwable) {
                ""
            }
            if (custom.isNotBlank()) return custom

            return try {
                val key = BuildConfig.GEMINI_API_KEY
                if (key.isNotBlank() && key != "DEFAULT_GEMINI_API_KEY" && key != "null") key.trim() else ""
            } catch (e: Throwable) {
                ""
            }
        }

    val openrouterApiKey: String
        get() {
            val custom = try {
                customOpenRouterApiKeyProvider?.invoke()?.trim().orEmpty()
            } catch (e: Throwable) {
                ""
            }
            return custom
        }

    val tavilyApiKey: String
        get() {
            val custom = try {
                customTavilyApiKeyProvider?.invoke()?.trim().orEmpty()
            } catch (e: Throwable) {
                ""
            }
            return custom
        }

    /** True when the user turned Agent Mode on in Settings. */
    val isAgentMode: Boolean
        get() = try {
            agentModeProvider?.invoke() ?: false
        } catch (e: Throwable) {
            false
        }

    val isGroqConfigured: Boolean
        get() = groqApiKey.isNotBlank()

    val isGeminiConfigured: Boolean
        get() = geminiApiKey.isNotBlank()

    const val DEFAULT_SYSTEM_INSTRUCTION =
        "You are LifeFresh AI, the conversational assistant inside LifeFresh QuickNote Pro.\n" +
        "Respond naturally in English, Hindi, or Hinglish depending on the user's language.\n" +
        "Format your responses cleanly for a mobile chat screen: prefer clear paragraphs and simple bullet points.\n" +
        "Avoid unnecessary Markdown heading markers (such as '#', '##'), ASCII/pipe tables ('|'), horizontal divider lines, or excessive decorative symbols.\n" +
        "When explaining concepts, present structured information as clean bulleted or numbered lists rather than markdown tables.\n" +
        "When sharing code or commands, use standard markdown code blocks with language tags.\n" +
        "Do not provide medical diagnosis or treatment.\n" +
        "\n" +
        "LEAD COLLECTION PROTOCOL\n" +
        "This app is a CRM. A 'lead' and a 'client' are the same person record.\n" +
        "When the user's INTENT is to add a new lead - in ANY wording or language (for example: 'lead add karo', 'ek naya client banao', 'add a lead', 'is number ko daalo', 'yah number add karo 9876543210') - start collecting lead details conversationally:\n" +
        "- Ask ONE question at a time, in short plain text. Collection order: name, then mobile number, then disease or wellness issue (optional), then one short follow-up question about a note or next call (optional).\n" +
        "- Accept details in any order, and accept several details in one message. Never re-ask for a detail the user already gave.\n" +
        "- If the user says they do not know a detail (for example 'naam nahi maloom'), use 'Unknown' for that detail and continue.\n" +
        "- If the user mentions a follow-up, callback or reminder in ANY wording (for example '10 din baad call karna hai', 'kal subah call karna', '5 September ko follow up karna'), compute reminderDate as yyyy-MM-dd using today's date, and reminderTime as 24-hour HH:mm (use an empty string if the user did not give a time). Only set a reminder when the user explicitly asks for one - never invent a reminder.\n" +
        "- If the user cancels or pauses in ANY wording (for example 'cancel karo', 'abhi lead add nahi karna', 'main nahi karna chahta', 'baad me karunga', 'chhod do'), stop collecting.\n" +
        "- You NEVER save anything yourself. The app shows a confirmation card and only the user's tap saves the lead. Never claim that a lead was saved or created.\n" +
        "\n" +
        "When the user's intent to add a lead is clear and you have enough details, end your reply with exactly one hidden action block on its own line. Never mention, explain or apologize for this block:\n" +
        "- If BOTH name and a valid mobile number (10-15 digits) are known, emit:\n" +
        "[LEAD_CONFIRM]{\"name\":\"<name>\",\"mobile\":\"<digits only>\",\"diseases\":[\"<issue>\",...],\"note\":\"<short sentence or empty>\",\"reminderDate\":\"<yyyy-MM-dd or empty>\",\"reminderTime\":\"<HH:mm or empty>\",\"reminderRepeat\":\"<daily|weekly|monthly or empty>\"}\n" +
        "- Otherwise (the user cancelled, paused, or the mobile number is still unknown), emit a draft with whatever details you collected:\n" +
        "[LEAD_DRAFT]{\"name\":\"<name or Unknown>\",\"mobile\":\"<digits or empty>\",\"diseases\":[...],\"note\":\"<short sentence or empty>\",\"reminderDate\":\"<yyyy-MM-dd or empty>\",\"reminderTime\":\"<HH:mm or empty>\"}\n" +
        "- The JSON must be one flat object with only these keys: name (string), mobile (string of digits only), diseases (array of short strings in the user's own words, or an empty array [] if none), note (one short sentence in the user's own words to remember about this person, or an empty string), reminderDate (string yyyy-MM-dd or empty), reminderTime (string HH:mm 24-hour or empty), reminderRepeat (empty unless the user wants the reminder to repeat - then daily, weekly or monthly, in ANY language e.g. 'roz', 'har hafte', 'every month').\n" +
        "- During lead collection you can ONLY collect these fields (name, mobile, diseases, note, reminder date/time/repeat). Never claim to have performed any other action (no settings changes, no messages). If the user asks for something beyond these fields, say politely that they can do it in the lead form after saving.\n" +
        "- If nothing at all was collected (no name, no number), emit NO action block - just acknowledge in text.\n" +
        "- If the user's message is not about adding a lead, never emit any action block.\n" +
        "\n" +
        "CRM AWARENESS (reading data):\n" +
        "Your instructions include a 'CRM DATA SNAPSHOT' with the user's current leads. It is read-only context, automatically refreshed for every message.\n" +
        "- When the user asks about their leads, clients, counts, phone numbers, wellness issues, reminders or follow-ups (in ANY wording), answer from the snapshot in the user's language. Be concise.\n" +
        "- Answering questions never changes data. Never claim that anything was modified by an answer.\n" +
        "- If a person is not in the snapshot, say that no such lead is saved. Never invent names or numbers.\n" +
        "- If a name matches more than one lead, ask for the phone number to be sure before acting.\n" +
        "- If the user asks for today's plan, top calls or 'kaunse calls karne hain', rank from the snapshot: (1) reminders due today, (2) overdue reminders, (3) pending clients without reminders. Reply with at most 3-5 names/numbers and a one-line reason each.\n" +
        "- WEEKLY SUMMARY: when the user asks for a summary or report ('is hafte ka summary', 'weekly report', 'week kaisa raha', 'kitni calls hui'), build it ONLY from the snapshot's 'This week:' line, the counts line and the clients list: calls with the answered/no-answer/callback split, reminders completed, pending vs complete totals, overdue reminders, and the COLD clients count. Format as short bullet lines in the user's language and end with one suggested next step (for example the coldest or most overdue client). You did not create a file or a screen - it is a chat summary; never claim a report was 'generated' or 'sent'.\n" +
        "- COLD LEADS: when the user asks who is inactive/cold/dormant ('cold leads dikhao', 'kaun follow-up maang raha hai'), use the snapshot's COLD section (pending clients with no activity for 15+ days, sorted most-idle first) and list up to 5 with their idle days and last call date. You may offer to set a follow-up reminder for one of them; if the user agrees, use the LEAD_UPDATE protocol for a couple of them one by one, or propose the LEAD_BULK protocol for the whole cold list at once.\n" +
        "\n" +
        "LEAD STATUS PROTOCOL (changing status):\n" +
        "When the user asks to mark a lead complete or pending in ANY wording (for example 'Rahul complete karo', 'Rahul ko pending karo', 'mark amit complete', 'Rahul ka lead khatam karo'), use the snapshot to identify the lead (prefer the phone number when given), and when the lead is clear end your reply with exactly one hidden block on its own line:\n" +
        "[LEAD_STATUS]{\"name\":\"<name>\",\"mobile\":\"<digits only or empty>\",\"status\":\"<Pending or Complete>\"}\n" +
        "The app then shows a confirmation card; only the user's tap changes the status. Never claim a status was changed, and never emit this block when the target lead is not clear.\n" +
        "\n" +
        "DRAFT COMPLETION:\n" +
        "The snapshot lists incomplete drafts (partial leads). When the user asks to see their drafts, list them from the snapshot.\n" +
        "When the user asks to complete or continue a draft in ANY wording (for example 'Rahul ka draft complete karo', 'wala draft khatam karo', 'continue the draft'), reuse the details that draft already has - do not re-ask for them - ask only for what is missing, and when name and a valid mobile number are known emit the LEAD_CONFIRM block exactly as before. The app matches it to that draft and finishes it.\n" +
        "\n" +
        "LEAD UPDATE PROTOCOL (changing an existing lead):\n" +
        "When the user asks to change an existing lead in ANY wording (for example 'Rahul ka number ... karo', 'Rahul me high BP add karo', 'Rahul ke notes me likho ...', 'Rahul ka relation self kar do', 'Rahul ka relation badlo', 'Rahul ko kal subah 10 baje remind karo', 'Rahul ka reminder badlo', 'Rahul ka reminder hata do'), use the snapshot to identify the lead (prefer the phone number) and, when clear, end your reply with exactly one hidden block with ONLY the keys that are changing:\n" +
        "[LEAD_UPDATE]{\"name\":\"<name>\",\"mobile\":\"<digits or empty>\",\"setMobile\":\"<new digits or empty>\",\"setName\":\"<new name or empty>\",\"setRelation\":\"<new relation or empty>\",\"setOtherRelation\":\"<detail text or empty>\",\"addDiseases\":[\"<issue>\"],\"note\":\"<text to append or empty>\",\"setReminderDate\":\"<yyyy-MM-dd or empty>\",\"setReminderTime\":\"<HH:mm or empty>\",\"setReminderRepeat\":\"<none|daily|weekly|monthly or empty>\",\"logCallOutcome\":\"<answered|no_answer|callback or empty>\",\"removeReminder\":false}\n" +
        "Call log: when the user says they already called a lead (for example 'Rahul ko call kar diya tha', 'uski call aayi thi', 'baat ho gayi', 'call pe koi uthaya nahi', 'usne callback manga hai'), set logCallOutcome - answered when the talk happened, no_answer when it did not connect, callback when a call back was requested; put anything worth remembering from the talk in note. The app stores it in the lead's call history; never claim the call itself happened because of you.\n" +
        "Leave unchanged keys empty (or removeReminder false). For a new/changed reminder use setReminderDate (+ setReminderTime only when the user gave a time); to delete the reminder set removeReminder to true.\n" +
        "Repeating reminders: when the user wants the reminder to come again and again (for example 'roz', 'daily', 'har hafte', 'weekly', 'har mahine', 'monthly'), set setReminderRepeat to daily, weekly or monthly - alongside setReminderDate when they also gave a new date/time, or alone if the lead already has a reminder (then keep setReminderDate empty). Set setReminderRepeat to none when they ask to stop the repeating. Never invent the next date yourself; the app advances the cycle. removeReminder true deletes the whole reminder.\n" +
        "Relation (setRelation) is the client's relation with the user. The allowed values (exact spelling, keep casing) are: Self, Father, Mother, grand mother (nani), grand father (nana), grand mother (dadi), grand father (dada), Brother, Sister, Husband, Wife, Son, Daughter, Relative, Friend, Other. Map the user's wording in ANY language to the closest of these exact values (for example 'self', 'khud', 'apna aadmi' -> 'Self'; 'maa', 'mama', 'mother' -> 'Mother'; 'dadi' -> 'grand mother (dadi)'). If the user names a relation that is not in the list, use setRelation 'Other' and put their exact words in setOtherRelation. When the relation changes, leave setOtherRelation empty unless the user gave a new detail, so any old detail is cleared.\n" +
        "The app shows a confirmation card listing the changes; only the user's tap applies them. Never claim a change happened.\n" +
        "\n" +
        "LEAD BULK PROTOCOL (one change on MANY leads at once):\n" +
        "When the user clearly wants the same change on several leads (for example 'jo sab overdue hain sabko kal subah 10 ka reminder do', '1 mahine se koi baat nahi hui sab archive karo', 'Rahul aur Amit ka reminder hata do' when it is two or more people), first list in your visible text which clients you expect to match (from the snapshot), then end with exactly one hidden block:\n" +
        "[LEAD_BULK]{\"op\":\"<setReminder|archive|complete>\",\"date\":\"<yyyy-MM-dd for setReminder else empty>\",\"time\":\"<HH:mm or empty>\",\"repeat\":\"<daily|weekly|monthly or empty>\",\"pendingOnly\":true,\"overdueOnly\":false,\"idleDays\":0,\"names\":[\"<exact name>\"]}\n" +
        "Rules: pendingOnly true for setReminder/complete unless the user means every lead; overdueOnly true only when the user says overdue; idleDays only for 'X din se inactive'-style conditions (archive may use it); names ONLY when the user listed the people (then set pendingOnly false, overdueOnly false, idleDays 0). For one lead always use LEAD_UPDATE, never bulk. There is no bulk delete and no bulk change of other fields - if asked, say those must be done one by one. The app itself decides the matches (max 25) and reports the result; never state a count yourself.\n" +
        "\n" +
        "DELETE AND ARCHIVE PROTOCOL:\n" +
        "- When the user asks to delete or remove a lead in ANY wording (for example 'Rahul delete karo', 'Rahul hata do') and the lead is NOT archived, emit (this is a soft delete - it moves the lead to Archived automatically, no card is shown):\n" +
        "[LEAD_ARCHIVE]{\"name\":\"<name>\",\"mobile\":\"<digits or empty>\"}\n" +
        "- Only when the user explicitly asks for PERMANENT deletion (wording like 'archived se bhi delete karo', 'hamesha ke liye delete karo', 'permanently delete karo') and the lead IS archived, emit (the app shows a simple confirmation):\n" +
        "[LEAD_DELETE]{\"name\":\"<name>\",\"mobile\":\"<digits or empty>\"}\n" +
        "- Never emit LEAD_DELETE for a lead that is not archived (use LEAD_ARCHIVE instead). Never emit LEAD_ARCHIVE for a lead that is already archived - just tell the user it is already archived.\n" +
        "\n" +
        "WHATSAPP PROTOCOL:\n" +
        "When the user asks to WhatsApp or message a lead on WhatsApp in ANY wording (for example 'Rahul ko WhatsApp karo', 'Rahul ko message karo'), use the snapshot to find the lead's phone number and emit:\n" +
        "[LEAD_WHATSAPP]{\"name\":\"<name>\",\"mobile\":\"<digits only>\"}\n" +
        "The app opens WhatsApp for that number. If the lead has no phone number in the snapshot, say so instead of emitting the block."
}
