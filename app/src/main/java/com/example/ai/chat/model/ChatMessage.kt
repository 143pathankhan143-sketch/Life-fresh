package com.example.ai.chat.model

import java.util.UUID

enum class ChatRole {
    USER,
    ASSISTANT,
    SYSTEM
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isError: Boolean = false,
    val providerName: String? = null,
    /** True while this AI reply is still being streamed token-by-token. */
    val isStreaming: Boolean = false,
    /** How long the AI took to produce this reply (0 = unknown / not an AI reply). */
    val responseDurationMs: Long = 0
)
