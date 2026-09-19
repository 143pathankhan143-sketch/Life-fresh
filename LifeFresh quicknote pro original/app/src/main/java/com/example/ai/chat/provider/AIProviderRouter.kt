package com.example.ai.chat.provider

import com.example.ai.chat.model.ChatMessage

class AIProviderRouter(
    private val providers: List<AIProvider> = listOf(GeminiProvider(), GroqProvider())
) {
    suspend fun routeChat(
        messages: List<ChatMessage>,
        systemInstruction: String
    ): AIProviderResult {
        val configuredProviders = providers.filter { it.isConfigured }
            .sortedByDescending { it is GeminiProvider }

        if (configuredProviders.isEmpty()) {
            return AIProviderResult.Failure(
                providerName = "Router",
                errorMessage = "LifeFresh AI is not configured yet. Add a Gemini API key in Settings to start chatting.",
                isRetryable = false
            )
        }

        var lastFailure: AIProviderResult.Failure? = null

        for (provider in configuredProviders) {
            when (val result = provider.generateResponse(messages, systemInstruction)) {
                is AIProviderResult.Success -> {
                    return result
                }
                is AIProviderResult.Failure -> {
                    lastFailure = result
                    // If auth failure (invalid API key), stop and show exact error
                    if (!result.isRetryable && !result.isRateLimitOrTimeout) {
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
