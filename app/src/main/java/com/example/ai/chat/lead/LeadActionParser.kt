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
    val status: String = "" // "Pending" or "Complete" - only used for STATUS kind
) {
    enum class Kind {
        /** All required details collected; the app offers Save (and Draft). */
        CONFIRM,

        /** Collection was cancelled/paused or is incomplete; save as a draft. */
        DRAFT,

        /** Mark an existing lead Pending/Complete; the app shows a confirm card. */
        STATUS
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
 * Extracts the hidden LEAD_CONFIRM / LEAD_DRAFT / LEAD_STATUS block from an AI reply.
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
    private const val MAX_NOTE_LENGTH = 120

    private val markerRegex = Regex(
        "\\[LEAD_(CONFIRM|DRAFT|STATUS)\\]\\s*(\\{.*?\\})(?:\\s*\\[/LEAD_(?:CONFIRM|DRAFT|STATUS)\\])?",
        RegexOption.DOT_MATCHES_ALL
    )

    fun parse(content: String): ParsedLeadReply {
        val match = markerRegex.find(content) ?: return ParsedLeadReply.Normal(content)

        val kind = when (match.groupValues[1]) {
            "CONFIRM" -> LeadAction.Kind.CONFIRM
            "STATUS" -> LeadAction.Kind.STATUS
            else -> LeadAction.Kind.DRAFT
        }

        val action = when (kind) {
            LeadAction.Kind.STATUS -> parseStatusAction(match.groupValues[2])
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
