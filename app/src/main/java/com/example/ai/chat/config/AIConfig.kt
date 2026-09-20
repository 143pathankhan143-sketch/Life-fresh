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
    val GEMINI_TEXT_MODELS: List<String> = listOf(
        "gemini-3.8-flash",
        "gemini-3.7-flash",
        "gemini-3.6-flash",
        "gemini-3.5-flash",
        "gemini-flash-latest",
        "gemini-3.1-pro-preview"
    )

    @Volatile
    var customGeminiApiKeyProvider: (() -> String)? = null

    val groqApiKey: String
        get() = try {
            val key = BuildConfig.GROQ_API_KEY
            if (key.isNotBlank() && key != "DEFAULT_GROQ_API_KEY" && key != "null") key.trim() else ""
        } catch (e: Throwable) {
            ""
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
        "- Ask ONE question at a time, in short plain text. Collection order: name, then mobile number, then disease or wellness issue (optional).\n" +
        "- Accept details in any order, and accept several details in one message. Never re-ask for a detail the user already gave.\n" +
        "- If the user says they do not know a detail (for example 'naam nahi maloom'), use 'Unknown' for that detail and continue.\n" +
        "- If the user cancels or pauses in ANY wording (for example 'cancel karo', 'abhi lead add nahi karna', 'main nahi karna chahta', 'baad me karunga', 'chhod do'), stop collecting.\n" +
        "- You NEVER save anything yourself. The app shows a confirmation card and only the user's tap saves the lead. Never claim that a lead was saved or created.\n" +
        "\n" +
        "When the user's intent to add a lead is clear and you have enough details, end your reply with exactly one hidden action block on its own line. Never mention, explain or apologize for this block:\n" +
        "- If BOTH name and a valid mobile number (10-15 digits) are known, emit:\n" +
        "[LEAD_CONFIRM]{\"name\":\"<name>\",\"mobile\":\"<digits only>\",\"diseases\":[\"<issue>\",...]}\n" +
        "- Otherwise (the user cancelled, paused, or the mobile number is still unknown), emit a draft with whatever details you collected:\n" +
        "[LEAD_DRAFT]{\"name\":\"<name or Unknown>\",\"mobile\":\"<digits or empty>\",\"diseases\":[...]}\n" +
        "- The JSON must be one flat object with only these keys: name (string), mobile (string of digits only), diseases (array of short strings in the user's own words, or an empty array [] if none).\n" +
        "- If nothing at all was collected (no name, no number), emit NO action block - just acknowledge in text.\n" +
        "- If the user's message is not about adding a lead, never emit any action block."
}
