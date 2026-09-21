package com.example.ai.chat.repository

import com.example.ai.chat.config.AIConfig
import com.example.ai.chat.lead.LeadActionParser
import com.example.ai.chat.lead.ParsedLeadReply
import com.example.ai.chat.model.ChatMessage
import com.example.ai.chat.model.ChatRole
import com.example.ai.chat.model.ChatUiState
import com.example.ai.chat.provider.AIProviderResult
import com.example.ai.chat.provider.AIProviderRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Action kinds that need a user confirmation card before anything happens. */
private val CARD_ACTION_KINDS = setOf(
    com.example.ai.chat.lead.LeadAction.Kind.CONFIRM,
    com.example.ai.chat.lead.LeadAction.Kind.DRAFT,
    com.example.ai.chat.lead.LeadAction.Kind.STATUS,
    com.example.ai.chat.lead.LeadAction.Kind.UPDATE,
    com.example.ai.chat.lead.LeadAction.Kind.DELETE
)

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
    /**
     * Dismisses the pending lead confirmation card without saving anything.
     * Called when the user taps Cancel, or implicitly when a new message is sent.
     */
    fun dismissPendingLead()
    /**
     * Appends a locally generated assistant message (for example a save
     * confirmation after the user tapped a card button).
     */
    fun addLocalAssistantMessage(content: String)
    /**
     * Registers the source of the read-only CRM data snapshot that is
     * appended to the system instruction before every request. Null (or an
     * empty result) means the snapshot is simply not sent.
     */
    fun setCrmSnapshotProvider(provider: (() -> String)?)
    /**
     * Handles action types that execute directly without a confirmation card
     * (ARCHIVE, WHATSAPP). Card-based types (CONFIRM, DRAFT, STATUS, UPDATE,
     * DELETE) go through [ChatUiState.pendingLeadAction] instead.
     */
    fun setDirectActionHandler(handler: ((com.example.ai.chat.lead.LeadAction) -> Unit)?)
}

class DefaultAIChatRepository(
    private val router: AIProviderRouter = AIProviderRouter(),
    systemInstruction: String = AIConfig.DEFAULT_SYSTEM_INSTRUCTION
) : AIChatRepository {
    /**
     * The model needs today's date to convert relative follow-up expressions
     * (for example "10 din baad call karna hai") into a concrete reminder
     * date. Computed once per app launch.
     */
    private val fullSystemInstruction: String =
        systemInstruction +
            "\nToday's date: " +
            SimpleDateFormat("yyyy-MM-dd (EEEE)", Locale.ENGLISH).format(Date())

    @Volatile
    private var crmSnapshotProvider: (() -> String)? = null

    @Volatile
    private var directActionHandler: ((com.example.ai.chat.lead.LeadAction) -> Unit)? = null

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
                    canRetry = false, activeProvider = null,
                    pendingLeadAction = null // a new message dismisses any pending lead card
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
                    canRetry = false, activeProvider = null, pendingLeadAction = null
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
        // The snapshot is rebuilt for every request so the model always sees
        // the current leads (a failing provider never breaks the chat).
        val snapshot = try {
            crmSnapshotProvider?.invoke().orEmpty()
        } catch (t: Throwable) {
            ""
        }
        router.routeChat(messages, fullSystemInstruction + snapshot)
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
        var directAction: com.example.ai.chat.lead.LeadAction? = null
        synchronized(stateLock) {
            if (!isCurrent(token)) return
            when (result) {
                is AIProviderResult.Success -> {
                    // The reply may end with a hidden action block. It is stripped
                    // from the visible text; card-based types become a pending
                    // confirmation card, direct types (ARCHIVE, WHATSAPP) are
                    // dispatched to the handler after the lock is released.
                    val parsed = LeadActionParser.parse(result.text)
                    val visibleText = when (parsed) {
                        is ParsedLeadReply.Normal -> parsed.text
                        is ParsedLeadReply.WithAction -> parsed.visibleText
                    }
                    val action = (parsed as? ParsedLeadReply.WithAction)?.action
                    directAction = action?.takeIf {
                        it.kind == com.example.ai.chat.lead.LeadAction.Kind.ARCHIVE ||
                            it.kind == com.example.ai.chat.lead.LeadAction.Kind.WHATSAPP
                    }
                    val message = ChatMessage(
                        role = ChatRole.ASSISTANT, content = visibleText,
                        providerName = result.providerName
                    )
                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages + message, isThinking = false,
                        errorMessage = null, canRetry = false, activeProvider = result.providerName,
                        pendingLeadAction = action?.takeIf { it.kind in CARD_ACTION_KINDS }
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
        // Direct actions (ARCHIVE, WHATSAPP) run here, OUTSIDE the lock, so the
        // Room write can never deadlock with the UI update above.
        directAction?.let { action ->
            try {
                directActionHandler?.invoke(action)
            } catch (_: Throwable) {
                // A failed direct action must never crash the chat.
            }
        }
    }

    override fun setCrmSnapshotProvider(provider: (() -> String)?) {
        crmSnapshotProvider = provider
    }

    override fun setDirectActionHandler(handler: ((com.example.ai.chat.lead.LeadAction) -> Unit)?) {
        directActionHandler = handler
    }

    override fun dismissPendingLead() {
        synchronized(stateLock) {
            if (_uiState.value.pendingLeadAction != null) {
                _uiState.value = _uiState.value.copy(pendingLeadAction = null)
            }
        }
    }

    override fun addLocalAssistantMessage(content: String) {
        if (content.isBlank()) return
        synchronized(stateLock) {
            val message = ChatMessage(
                role = ChatRole.ASSISTANT, content = content.trim(),
                providerName = null
            )
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages + message,
                errorMessage = null, canRetry = false
            )
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
