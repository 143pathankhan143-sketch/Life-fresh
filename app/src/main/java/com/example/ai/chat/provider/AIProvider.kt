package com.example.ai.chat.provider

import com.example.ai.chat.model.ChatMessage

/**
 * Common provider interface allowing multiple AI backends (Groq, Gemini, etc.)
 * to be integrated and swapped seamlessly without altering UI or Repository code.
 */
interface AIProvider {
    val name: String
    val isConfigured: Boolean

    suspend fun generateResponse(
        messages: List<ChatMessage>,
        systemInstruction: String
    ): AIProviderResult

    /**
     * Streams the reply token-by-token through [onToken] and returns the final
     * result. The full reply text is exactly the concatenation of all tokens.
     *
     * If the backend cannot stream (or ignores the stream flag), the whole
     * reply is delivered as a single token at the end - the UI behaves the
     * same way either way. Implementations must never throw for stream
     * problems; they degrade to [AIProviderResult.Failure] like
     * [generateResponse] does.
     */
    suspend fun generateStreamingResponse(
        messages: List<ChatMessage>,
        systemInstruction: String,
        onToken: (String) -> Unit
    ): AIProviderResult
}
