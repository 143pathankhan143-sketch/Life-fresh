package com.example.data.database

import androidx.room.Entity

/**
 * One manual call-log entry for a lead (device-local history).
 *
 * The app never reads the phone's native call log - an entry is created when
 * the user taps Call in the app and picks an outcome on the small dialog, or
 * when the LifeFresh AI logs a call from chat. `LeadEntity.lastCall` stays as
 * the synced summary (it tracks the newest entry); the full history lives
 * here and in the local backup.
 */
@Entity(
    tableName = "call_logs",
    primaryKeys = ["ownerUid", "id"]
)
data class CallLogEntity(
    val ownerUid: String = "",
    val id: String,
    val leadId: String,
    val callTime: String,          // same ISO format as LeadEntity.lastCall
    val outcome: String,           // "answered", "no_answer", "callback"
    val note: String = ""
)

/** Canonical outcome values stored in the database. */
object CallOutcomes {
    const val ANSWERED = "answered"
    const val NO_ANSWER = "no_answer"
    const val CALLBACK = "callback"

    val ALL = setOf(ANSWERED, NO_ANSWER, CALLBACK)

    fun normalize(value: String): String? =
        ALL.firstOrNull { it == value.trim().lowercase(java.util.Locale.ROOT) }
}
