package com.example.ai.chat.provider

import android.util.Log
import com.example.ai.chat.model.ChatMessage
import kotlinx.coroutines.delay

/**
 * Routes a chat turn through the configured providers.
 *
 * Order: Gemini (primary) -> OpenRouter -> Groq. Every provider that has a key
 * is tried in turn.
 *
 * Two recovery rules, both added after users saw "AI service is busy" for
 * requests another model could have answered:
 *
 *  1. A provider's own model chain is walked on failure (see the providers'
 *     AIFailurePolicy usage) - a per-model rate limit is not the end of the
 *     request.
 *  2. If EVERY provider came back busy/rate limited and nothing was streamed
 *     yet, the whole chain is tried ONE more time after a short pause. Busy is
 *     usually a burst, so the retry often succeeds and the user never sees the
 *     error bubble.
 *
 * Fallback rule that must stay: the next provider is only tried while NO
 * tokens have been emitted. Once text is on screen, switching providers would
 * mix two different answers - the failure is returned and the caller keeps the
 * partial text.
 */
class AIProviderRouter(
    private val providers: List<AIProvider> = listOf(
        GeminiProvider(),
        OpenRouterProvider(),
        GroqProvider()
    ),

    /**
     * Pause before the single automatic retry pass. Short enough that a
     * normally-fast reply is still fast, long enough for a rate-limit burst
     * to clear.
     */
    private val busyRetryDelayMs: Long = AIFailurePolicy.RETRY_PASS_DELAY_MS,

    /** 1 = never retry a whole pass, 2 = retry once (default). */
    private val maxPasses: Int = 2,

    /** Injectable so tests do not actually wait. */
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {

    /**
     * Routes the chat to the first provider that can answer.
     */
    suspend fun routeChat(
        messages: List<ChatMessage>,
        systemInstruction: String
    ): AIProviderResult = routeChatStreaming(messages, systemInstruction) {
        // Non-streaming callers do not need the tokens.
    }

    /**
     * Routes the chat to the first provider that can answer, forwarding each
     * streamed token to [onToken] as it arrives.
     */
    suspend fun routeChatStreaming(
        messages: List<ChatMessage>,
        systemInstruction: String,
        onToken: (String) -> Unit
    ): AIProviderResult {
        // Tier order: Gemini (primary) -> OpenRouter (second) -> Groq (fast
        // last resort). Only providers with a saved key are configured.
        val configuredProviders = providers.filter { it.isConfigured }
            .sortedByDescending {
                when (it) {
                    is GeminiProvider -> 2
                    is OpenRouterProvider -> 1
                    else -> 0
                }
            }

        if (configuredProviders.isEmpty()) {
            return AIProviderResult.Failure(
                providerName = "Router",
                errorMessage = "LifeFresh AI is not configured yet. Add a Gemini, Groq or OpenRouter API key in Settings to start chatting.",
                isRetryable = false
            )
        }

        var lastFailure: AIProviderResult.Failure? = null
        var emittedTokens = 0L
        var passesDone = 0
        val countingOnToken: (String) -> Unit = { token ->
            emittedTokens++
            onToken(token)
        }

        while (true) {
            passesDone++

            for (provider in configuredProviders) {
                when (val result = provider.generateStreamingResponse(messages, systemInstruction, countingOnToken)) {
                    is AIProviderResult.Success -> {
                        return result
                    }
                    is AIProviderResult.Failure -> {
                        lastFailure = result
                        // If auth failure (invalid API key), stop and show exact error
                        if (!result.isRetryable && !result.isRateLimitOrTimeout) {
                            return result
                        }
                        // Partial text is already on screen - never mix in a
                        // second provider's answer.
                        if (emittedTokens > 0) {
                            return result
                        }
                    }
                }
            }

            // Nothing streamed and everyone was busy: one quiet retry pass,
            // then report. Anything else is final.
            val failure = lastFailure
            val retryTheWholePass = passesDone < maxPasses &&
                emittedTokens == 0L &&
                failure != null &&
                failure.isRetryable &&
                failure.isRateLimitOrTimeout

            if (!retryTheWholePass) break

            Log.w(
                TAG,
                "all ${configuredProviders.size} provider(s) busy (pass $passesDone) - " +
                    "retrying once in ${busyRetryDelayMs}ms"
            )
            sleep(busyRetryDelayMs)
        }

        val summary = when {
            lastFailure == null ->
                "I'm sorry, I couldn't reach the AI service right now. Please check your connection and try again."

            lastFailure.isRateLimitOrTimeout -> buildString {
                append("The AI service is busy right now (rate limit or slow response).")
                if (passesDone > 1) append(" I retried automatically.")
                append(" Please wait a minute and try again.")
                // A short reason helps the user report a real problem instead
                // of guessing (the raw provider text is trimmed).
                val detail = lastFailure.errorMessage.trim().take(90)
                if (detail.isNotEmpty()) append(" [$detail]")
            }

            else ->
                "I'm sorry, I couldn't reach the AI service right now. Last error: ${lastFailure.errorMessage.take(120)}"
        }

        return AIProviderResult.Failure(
            providerName = "Router",
            errorMessage = summary,
            isRetryable = true,
            isRateLimitOrTimeout = lastFailure?.isRateLimitOrTimeout ?: false,
            cause = lastFailure?.cause
        )
    }

    private companion object {
        const val TAG = "AIProviderRouter"
    }
}
