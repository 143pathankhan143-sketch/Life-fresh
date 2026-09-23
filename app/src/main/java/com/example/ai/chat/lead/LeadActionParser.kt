package com.example.ai.chat.lead

import org.json.JSONObject

/**
 * A lead action proposed by the AI through the LEAD-COLLECT protocol.
 *
 * The model ends its reply with a hidden block such as:
 * [LEAD_CONFIRM]{"name":"Rahul","mobile":"9876543210","diseases":["Diabetes"]}
 *
 * The block is never shown to the user. The app parses it out of the reply
 * and shows a confirmation card instead. A lead is saved to Room only after
 * the user taps a button on that card - the AI itself never writes data.
 */
data class LeadAction(
    val kind: Kind,
    val name: String,
    val mobile: String,
    val diseases: List<String>,
    val note: String = "",
    val reminderDate: String = "", // "yyyy-MM-dd" or empty
    val reminderTime: String = "", // "HH:mm" (24h) or empty
    val status: String = "", // "Pending" or "Complete" - only used for STATUS kind
    // UPDATE-only change fields (empty = no change)
    val setMobile: String = "",
    val setName: String = "",
    val setRelation: String = "",
    val setOtherRelation: String = "",
    val addDiseases: List<String> = emptyList(),
    val setReminderDate: String = "",
    val setReminderTime: String = "",
    val setReminderRepeat: String = "", // "none"/"daily"/"weekly"/"monthly" - empty = no change
    val logCallOutcome: String = "",    // "answered"/"no_answer"/"callback" - empty = no call log
    val removeReminder: Boolean = false
) {
    enum class Kind {
        /** All required details collected; the app offers Save (and Draft). */
        CONFIRM,

        /** Collection was cancelled/paused or is incomplete; save as a draft. */
        DRAFT,

        /** Mark an existing lead Pending/Complete; the app shows a confirm card. */
        STATUS,

        /** Change an existing lead's details; the app shows a confirm card. */
        UPDATE,

        /** Move a lead to Archived - executed directly, no card (soft delete). */
        ARCHIVE,

        /** Permanently delete an archived lead; the app shows a light confirm card. */
        DELETE,

        /** Open WhatsApp for the lead's number - executed directly, no card. */
        WHATSAPP
    }

    /** True when at least one real detail was collected (for draft decisions). */
    val hasAnyDetail: Boolean
        get() = (name.isNotBlank() && !name.equals("Unknown", ignoreCase = true)) || mobile.isNotBlank()
}

sealed class ParsedLeadReply {
    /** No action block present - show the reply as-is. */
    data class Normal(val text: String) : ParsedLeadReply()

    /** An action block was found and parsed - show [visibleText] plus the card. */
    data class WithAction(val action: LeadAction, val visibleText: String) : ParsedLeadReply()
}

/**
 * Extracts the hidden action block from an AI reply.
 *
 * Known kinds: LEAD_CONFIRM / LEAD_DRAFT (new lead), LEAD_STATUS / LEAD_UPDATE
 * / LEAD_DELETE (existing lead, confirmation card), LEAD_ARCHIVE /
 * LEAD_WHATSAPP (executed directly).
 *
 * Deliberately tolerant (the model is not 100% format-strict):
 * - the marker may appear anywhere in the reply, not only at the end;
 * - the optional closing tag may be missing;
 * - the JSON must be a flat object (the first '}' ends it);
 * - unknown extra keys are ignored, fields are trimmed and length-capped.
 *
 * Any malformed input degrades safely to [ParsedLeadReply.Normal]:
 * the raw text is shown and nothing is ever saved.
 */
object LeadActionParser {

    private const val MAX_NAME_LENGTH = 80
    private const val MAX_MOBILE_LENGTH = 20
    private const val MAX_DISEASES = 5
    private const val MAX_DISEASE_LENGTH = 40
    private const val MAX_RELATION_LENGTH = 40
    private const val MAX_NOTE_LENGTH = 120

    private val markerRegex = Regex(
        "\\[LEAD_(CONFIRM|DRAFT|STATUS|UPDATE|ARCHIVE|DELETE|WHATSAPP)\\]\\s*(\\{.*?\\})" +
            "(?:\\s*\\[/LEAD_(?:CONFIRM|DRAFT|STATUS|UPDATE|ARCHIVE|DELETE|WHATSAPP)\\])?",
        RegexOption.DOT_MATCHES_ALL
    )

    fun parse(content: String): ParsedLeadReply {
        val match = markerRegex.find(content) ?: return ParsedLeadReply.Normal(content)

        val kind = when (match.groupValues[1]) {
            "CONFIRM" -> LeadAction.Kind.CONFIRM
            "STATUS" -> LeadAction.Kind.STATUS
            "UPDATE" -> LeadAction.Kind.UPDATE
            "ARCHIVE" -> LeadAction.Kind.ARCHIVE
            "DELETE" -> LeadAction.Kind.DELETE
            "WHATSAPP" -> LeadAction.Kind.WHATSAPP
            else -> LeadAction.Kind.DRAFT
        }

        val action = when (kind) {
            LeadAction.Kind.STATUS -> parseStatusAction(match.groupValues[2])
            LeadAction.Kind.UPDATE -> parseUpdateAction(match.groupValues[2])
            LeadAction.Kind.ARCHIVE -> parseSimpleAction(match.groupValues[2], kind)
            LeadAction.Kind.DELETE -> parseSimpleAction(match.groupValues[2], kind)
            LeadAction.Kind.WHATSAPP -> parseWhatsAppAction(match.groupValues[2])
            else -> parseAction(match.groupValues[2], kind)
        } ?: return ParsedLeadReply.Normal(content)

        val visibleText = (
            content.substring(0, match.range.first) +
                " " +
                content.substring(match.range.last + 1)
            ).trim()

        return ParsedLeadReply.WithAction(action = action, visibleText = visibleText)
    }

    /**
     * Parses a LEAD_STATUS block. Requires a valid status and at least one
     * identifying field (name or mobile) so the app can match the lead.
     */
    private fun parseStatusAction(jsonText: String): LeadAction? {
        return try {
            val json = JSONObject(jsonText)
            val name = json.optString("name", "").trim().take(MAX_NAME_LENGTH)
            val mobile = json.optString("mobile", "").trim().take(MAX_MOBILE_LENGTH)
            val rawStatus = json.optString("status", "").trim().lowercase()
            val status = when {
                rawStatus == "complete" || rawStatus == "completed" -> "Complete"
                rawStatus == "pending" -> "Pending"
                else -> return null
            }
            if (name.isEmpty() && mobile.isEmpty()) return null
            LeadAction(
                kind = LeadAction.Kind.STATUS,
                name = name,
                mobile = mobile,
                diseases = emptyList(),
                status = status
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses a LEAD_UPDATE block: matching fields plus the actual changes.
     * At least one change must be present, otherwise it is not an action.
     */
    private fun parseUpdateAction(jsonText: String): LeadAction? {
        return try {
            val json = JSONObject(jsonText)
            val name = json.optString("name", "").trim().take(MAX_NAME_LENGTH)
            val mobile = json.optString("mobile", "").trim().take(MAX_MOBILE_LENGTH)
            val setMobile = json.optString("setMobile", "").trim().take(MAX_MOBILE_LENGTH)
            val setName = json.optString("setName", "").trim().take(MAX_NAME_LENGTH)
            val setRelation = json.optString("setRelation", "").trim().take(MAX_RELATION_LENGTH)
            val setOtherRelation = json.optString("setOtherRelation", "").trim().take(MAX_RELATION_LENGTH)
            val note = json.optString("note", "").trim().take(MAX_NOTE_LENGTH)
            val setReminderDate = json.optString("setReminderDate", "").trim().take(10)
            val setReminderTime = json.optString("setReminderTime", "").trim().take(5)
            val setReminderRepeat = when (json.optString("setReminderRepeat", "").trim().lowercase()) {
                "none", "daily", "weekly", "monthly" -> json.optString("setReminderRepeat", "").trim().lowercase()
                else -> ""
            }
            val logCallOutcome = when (json.optString("logCallOutcome", "").trim().lowercase().replace(' ', '_')) {
                "answered", "no_answer", "callback" ->
                    json.optString("logCallOutcome", "").trim().lowercase().replace(' ', '_')
                else -> ""
            }
            val removeReminder = json.optBoolean("removeReminder", false)

            val addDiseases = mutableListOf<String>()
            val rawAdd = json.optJSONArray("addDiseases")
            if (rawAdd != null) {
                for (i in 0 until rawAdd.length()) {
                    val disease = rawAdd.optString(i, "").trim().replace(Regex("\\s+"), " ")
                    if (disease.isNotEmpty()) addDiseases += disease.take(MAX_DISEASE_LENGTH)
                    if (addDiseases.size >= MAX_DISEASES) break
                }
            }

            if (name.isEmpty() && mobile.isEmpty()) return null
            val hasChange = setMobile.isNotEmpty() ||
                setName.isNotEmpty() ||
                setRelation.isNotEmpty() ||
                addDiseases.isNotEmpty() ||
                note.isNotEmpty() ||
                setReminderDate.isNotEmpty() ||
                setReminderRepeat.isNotEmpty() ||
                logCallOutcome.isNotEmpty() ||
                removeReminder
            if (!hasChange) return null

            LeadAction(
                kind = LeadAction.Kind.UPDATE,
                name = name,
                mobile = mobile,
                diseases = emptyList(),
                note = note,
                setMobile = setMobile,
                setName = setName,
                setRelation = setRelation,
                setOtherRelation = setOtherRelation,
                addDiseases = addDiseases.distinctBy { it.lowercase() },
                setReminderDate = setReminderDate,
                setReminderTime = setReminderTime,
                setReminderRepeat = setReminderRepeat,
                logCallOutcome = logCallOutcome,
                removeReminder = removeReminder
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses LEAD_ARCHIVE / LEAD_DELETE blocks. Requires at least one
     * identifying field (name or mobile).
     */
    private fun parseSimpleAction(jsonText: String, kind: LeadAction.Kind): LeadAction? {
        return try {
            val json = JSONObject(jsonText)
            val name = json.optString("name", "").trim().take(MAX_NAME_LENGTH)
            val mobile = json.optString("mobile", "").trim().take(MAX_MOBILE_LENGTH)
            if (name.isEmpty() && mobile.isEmpty()) return null
            LeadAction(kind = kind, name = name, mobile = mobile, diseases = emptyList())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses a LEAD_WHATSAPP block. The mobile number is mandatory - without
     * it there is nothing to open, so the block is ignored (safe fallback).
     */
    private fun parseWhatsAppAction(jsonText: String): LeadAction? {
        return try {
            val json = JSONObject(jsonText)
            val name = json.optString("name", "").trim().take(MAX_NAME_LENGTH)
            val mobile = json.optString("mobile", "").trim().take(MAX_MOBILE_LENGTH)
            if (mobile.isEmpty()) return null
            LeadAction(
                kind = LeadAction.Kind.WHATSAPP,
                name = name,
                mobile = mobile,
                diseases = emptyList()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseAction(jsonText: String, kind: LeadAction.Kind): LeadAction? {
        return try {
            val json = JSONObject(jsonText)
            val name = json.optString("name", "").trim().take(MAX_NAME_LENGTH)
            val mobile = json.optString("mobile", "").trim().take(MAX_MOBILE_LENGTH)

            val diseases = mutableListOf<String>()
            val rawDiseases = json.optJSONArray("diseases")
            if (rawDiseases != null) {
                for (i in 0 until rawDiseases.length()) {
                    val disease = rawDiseases.optString(i, "").trim().replace(Regex("\\s+"), " ")
                    if (disease.isNotEmpty()) diseases += disease.take(MAX_DISEASE_LENGTH)
                    if (diseases.size >= MAX_DISEASES) break
                }
            }

            val note = json.optString("note", "").trim().take(MAX_NOTE_LENGTH)
            val reminderDate = json.optString("reminderDate", "").trim().take(10)
            val reminderTime = json.optString("reminderTime", "").trim().take(5)

            // Nothing collected at all -> not a meaningful action.
            if (name.isEmpty() && mobile.isEmpty()) return null

            LeadAction(
                kind = kind,
                name = name,
                mobile = mobile,
                diseases = diseases.distinctBy { it.lowercase() },
                note = note,
                reminderDate = reminderDate,
                reminderTime = reminderTime
            )
        } catch (_: Exception) {
            null
        }
    }
}
