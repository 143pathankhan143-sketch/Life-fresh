package com.example.ai.chat.repository

import com.example.ai.chat.config.AIConfig
import com.example.ai.chat.model.ChatMessage
import com.example.ai.chat.model.ChatRole
import com.example.ai.chat.model.ChatUiState
import com.example.ai.chat.provider.AIProviderResult
import com.example.ai.chat.provider.AIProviderRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface AIChatRepository {
    val uiState: StateFlow<ChatUiState>
    suspend fun sendMessage(text: String)
    suspend fun retry()
    fun clearConversation()
    /**
     * Loads a previously persisted conversation into the in-memory UI state.
     * Only applies when the current conversation is empty, so a live
     * conversation is never clobbered by a restore.
     */
    fun restoreMessages(messages: List<ChatMessage>)
}

class DefaultAIChatRepository(
    private val router: AIProviderRouter = AIProviderRouter(),
    private val systemInstruction: String = AIConfig.DEFAULT_SYSTEM_INSTRUCTION
) : AIChatRepository {
    private data class RequestToken(val id: Long, val conversationGeneration: Long)

    private val _uiState = MutableStateFlow(ChatUiState())
    override val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    private val stateLock = Any()
    private var nextRequestId = 0L
    private var conversationGeneration = 0L
    private var activeRequestId: Long? = null

    override suspend fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        val token = beginRequest() ?: return
        try {
            val messages = synchronized(stateLock) {
                if (!isCurrent(token)) return
                val updated = _uiState.value.messages.filterNot { it.isError } +
                    ChatMessage(role = ChatRole.USER, content = trimmed)
                _uiState.value = _uiState.value.copy(
                    messages = updated, isThinking = true, errorMessage = null,
                    canRetry = false, activeProvider = null
                )
                updated
            }
            applyResultIfCurrent(token, safelyRoute(messages))
        } finally {
            finishRequest(token)
        }
    }

    override suspend fun retry() {
        val token = beginRequest(requireRetryableError = true) ?: return
        try {
            val messages = synchronized(stateLock) {
                if (!isCurrent(token)) return
                val clean = _uiState.value.messages.filterNot { it.isError }
                if (clean.none { it.role == ChatRole.USER }) return
                _uiState.value = _uiState.value.copy(
                    messages = clean, isThinking = true, errorMessage = null,
                    canRetry = false, activeProvider = null
                )
                clean
            }
            applyResultIfCurrent(token, safelyRoute(messages))
        } finally {
            finishRequest(token)
        }
    }

    override fun clearConversation() {
        synchronized(stateLock) {
            conversationGeneration++
            activeRequestId = null
            _uiState.value = ChatUiState()
        }
    }

    override fun restoreMessages(messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        synchronized(stateLock) {
            if (_uiState.value.messages.isNotEmpty()) return
            conversationGeneration++
            activeRequestId = null
            _uiState.value = ChatUiState(messages = messages)
        }
    }

    private fun beginRequest(requireRetryableError: Boolean = false): RequestToken? =
        synchronized(stateLock) {
            if (activeRequestId != null || _uiState.value.isThinking) return@synchronized null
            if (requireRetryableError && !_uiState.value.canRetry) return@synchronized null
            nextRequestId++
            activeRequestId = nextRequestId
            RequestToken(nextRequestId, conversationGeneration)
        }

    private fun isCurrent(token: RequestToken): Boolean =
        activeRequestId == token.id && conversationGeneration == token.conversationGeneration

    private suspend fun safelyRoute(messages: List<ChatMessage>): AIProviderResult = try {
        router.routeChat(messages, systemInstruction)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        AIProviderResult.Failure(
            providerName = "Router",
            errorMessage = t.message?.takeIf { it.isNotBlank() } ?: "I'm sorry, something went wrong while contacting the AI service. Please try again.",
            isRetryable = true
        )
    }

    private fun applyResultIfCurrent(token: RequestToken, result: AIProviderResult) {
        synchronized(stateLock) {
            if (!isCurrent(token)) return
            when (result) {
                is AIProviderResult.Success -> {
                    val message = ChatMessage(
                        role = ChatRole.ASSISTANT, content = result.text,
                        providerName = result.providerName
                    )
                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages + message, isThinking = false,
                        errorMessage = null, canRetry = false, activeProvider = result.providerName
                    )
                }
                is AIProviderResult.Failure -> {
                    val message = ChatMessage(
                        role = ChatRole.ASSISTANT, content = result.errorMessage,
                        isError = true, providerName = result.providerName
                    )
                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages + message, isThinking = false,
                        errorMessage = result.errorMessage, canRetry = result.isRetryable,
                        activeProvider = result.providerName
                    )
                }
            }
        }
    }

    private fun finishRequest(token: RequestToken) {
        synchronized(stateLock) {
            if (!isCurrent(token)) return
            activeRequestId = null
            if (_uiState.value.isThinking) {
                _uiState.value = _uiState.value.copy(isThinking = false)
            }
        }
    }
}
