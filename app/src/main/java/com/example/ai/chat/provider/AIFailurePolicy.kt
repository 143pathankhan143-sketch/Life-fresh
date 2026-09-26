package com.example.ai.chat.provider

/** Why an AI HTTP attempt failed. */
enum class AIErrorKind {
    /** Bad key / blocked account / out of credit — retrying anything is pointless. */
    AUTH,

    /** This model's limit is used up (HTTP 429). Limits are per-model on Groq/OpenRouter. */
    RATE_LIMIT,

    /** The endpoint itself is overloaded or briefly down (HTTP 5xx / 408 / 409). */
    SERVICE_BUSY,

    /** This model name no longer exists (HTTP 404/405) — another model may work. */
    MODEL_MISSING,

    /** Anything else (bad request, unknown status). */
    OTHER
}

/** What a provider should do after a failed attempt. */
sealed class AIRecovery {
    /**
     * The server told us (or we know) it clears quickly, so waiting [waitMs] and
     * retrying the SAME model is worth it. Bounded by
     * [AIFailurePolicy.MAX_INLINE_WAIT_MS] so the chat never hangs.
     */
    data class RetrySameModel(val waitMs: Long) : AIRecovery()

    /** This model can not serve us now — try the next model in the chain. */
    object TryNextModel : AIRecovery()

    /** Stop immediately: retrying or rotating would not help. */
    object FailFast : AIRecovery()
}

/**
 * The brain behind "the AI service is busy" handling.
 *
 * The important rule (and the previous bug): a HTTP 429 means THIS MODEL is
 * rate limited, not the whole provider. Groq and OpenRouter apply limits per
 * model, so the next model in the chain is usually still free — aborting the
 * whole chain turned a recoverable per-model limit into a dead-end error the
 * user saw as "AI service is busy" even though another model could answer.
 *
 * Pure Kotlin (no Android/network) so every decision is unit-tested.
 */
object AIFailurePolicy {

    /** Never block a chat turn longer than this for an inline retry. */
    const val MAX_INLINE_WAIT_MS: Long = 1_500L

    /** Used when the server sent no Retry-After (typical transient busy blip). */
    const val DEFAULT_BUSY_WAIT_MS: Long = 900L

    /** Upper bound for a parsed Retry-After, so a silly value can not stall us. */
    const val MAX_RETRY_AFTER_MS: Long = 60_000L

    /** Pause before the router's single automatic retry pass. */
    const val RETRY_PASS_DELAY_MS: Long = 1_200L

    fun kindFor(status: Int): AIErrorKind = when {
        // 400/401/402/403 = bad key, blocked account or no credit.
        status == 400 || status == 401 || status == 402 || status == 403 -> AIErrorKind.AUTH
        status == 404 || status == 405 -> AIErrorKind.MODEL_MISSING
        status == 429 -> AIErrorKind.RATE_LIMIT
        status == 408 || status == 409 || status == 425 || status >= 500 -> AIErrorKind.SERVICE_BUSY
        else -> AIErrorKind.OTHER
    }

    /** True when the failure means "busy right now, try again shortly". */
    fun isBusy(kind: AIErrorKind): Boolean =
        kind == AIErrorKind.RATE_LIMIT || kind == AIErrorKind.SERVICE_BUSY

    /**
     * Decides what to do with a failed attempt.
     *
     * @param retryAfterHeader the raw `Retry-After` response header, if any.
     * @param sameModelRetryUsed true when this model was already retried once for
     *        this request (so we rotate instead of waiting again).
     */
    fun decide(
        status: Int,
        retryAfterHeader: String?,
        sameModelRetryUsed: Boolean
    ): AIRecovery = when (val kind = kindFor(status)) {
        AIErrorKind.AUTH -> AIRecovery.FailFast

        AIErrorKind.RATE_LIMIT, AIErrorKind.SERVICE_BUSY -> {
            // A big Retry-After (a daily limit, or a long outage) means waiting
            // inline is pointless — rotate to another model instead.
            val serverWait = retryAfterMillis(retryAfterHeader)
            val wait = serverWait ?: DEFAULT_BUSY_WAIT_MS
            if (!sameModelRetryUsed && wait <= MAX_INLINE_WAIT_MS) {
                AIRecovery.RetrySameModel(wait)
            } else {
                AIRecovery.TryNextModel
            }
        }

        AIErrorKind.MODEL_MISSING, AIErrorKind.OTHER -> AIRecovery.TryNextModel
    }

    /**
     * Parses a `Retry-After` header into milliseconds.
     * Accepts plain seconds ("1", "1.5" — the OpenAI/Groq style) and an
     * explicit milliseconds value ("800ms"). HTTP-date values are ignored
     * (null) because rotating models is faster than parsing a date.
     */
    fun retryAfterMillis(header: String?): Long? {
        val raw = header?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val lower = raw.lowercase()
        val value = if (lower.endsWith("ms")) {
            lower.removeSuffix("ms").trim().toDoubleOrNull()
        } else {
            lower.toDoubleOrNull()?.times(1000.0)
        } ?: return null
        if (value.isNaN() || value <= 0.0) return null
        return value.toLong().coerceAtMost(MAX_RETRY_AFTER_MS)
    }

    /** Human line for a busy failure, used in logs and the final message. */
    fun busyLabel(kind: AIErrorKind): String = when (kind) {
        AIErrorKind.RATE_LIMIT -> "rate limited"
        AIErrorKind.SERVICE_BUSY -> "service busy"
        else -> "unavailable"
    }
}
