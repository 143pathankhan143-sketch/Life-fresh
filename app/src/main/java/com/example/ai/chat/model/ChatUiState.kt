package com.example.ai.chat.model

import com.example.ai.chat.lead.LeadAction

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isThinking: Boolean = false,
    val errorMessage: String? = null,
    val canRetry: Boolean = false,
    val activeProvider: String? = null,
    /** Lead proposed by the AI; the chat shows a confirmation card until the user decides. */
    val pendingLeadAction: LeadAction? = null
)
