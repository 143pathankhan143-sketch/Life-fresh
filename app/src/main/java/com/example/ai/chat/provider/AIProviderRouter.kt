package com.example.ai.chat.provider

import com.example.ai.chat.model.ChatMessage

class AIProviderRouter(
    private val providers: List<AIProvider> = listOf(GeminiProvider(), GroqProvider())
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
     *
     * Provider fallback rule: the next provider is only tried while NO
     * tokens have been emitted yet. Once text has started streaming to the
     * UI, switching providers mid-reply would mix two different answers -
     * in that case the failure is returned and the caller keeps the partial
     * text.
     */
    suspend fun routeChatStreaming(
        messages: List<ChatMessage>,
        systemInstruction: String,
        onToken: (String) -> Unit
    ): AIProviderResult {
        val configuredProviders = providers.filter { it.isConfigured }
            .sortedByDescending { it is GeminiProvider }

        if (configuredProviders.isEmpty()) {
            return AIProviderResult.Failure(
                providerName = "Router",
                errorMessage = "LifeFresh AI is not configured yet. Add a Gemini or Groq API key in Settings to start chatting.",
                isRetryable = false
            )
        }

        var lastFailure: AIProviderResult.Failure? = null
        var emittedTokens = 0L
        val countingOnToken: (String) -> Unit = { token ->
            emittedTokens++
            onToken(token)
        }

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

        val summary = when {
            lastFailure == null ->
                "I'm sorry, I couldn't reach the AI service right now. Please check your connection and try again."
            lastFailure.isRateLimitOrTimeout ->
                "The AI service is busy right now (rate limit or slow response). Please wait a minute and try again."
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
}
