package com.example.ai.chat.repository

import com.example.ai.chat.config.AIConfig
import com.example.ai.chat.lead.LeadAction
import com.example.ai.chat.lead.LeadActionParser
import com.example.ai.chat.lead.ParsedLeadReply
import com.example.ai.chat.model.ChatMessage
import com.example.ai.chat.model.ChatRole
import com.example.ai.chat.model.ChatUiState
import com.example.ai.chat.provider.AIProviderResult
import com.example.ai.chat.provider.AIProviderRouter
import com.example.ai.chat.provider.SearchIntentGate
import com.example.ai.chat.provider.TavilySearchClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Action kinds that need a user confirmation card before anything happens. */
private val CARD_ACTION_KINDS = setOf(
    LeadAction.Kind.CONFIRM,
    LeadAction.Kind.DRAFT,
    LeadAction.Kind.STATUS,
    LeadAction.Kind.UPDATE,
    LeadAction.Kind.DELETE,
    LeadAction.Kind.BULK
)

/**
 * Agent Mode: these kinds are executed AUTOMATICALLY (no confirmation card)
 * when the user has Agent Mode ON. DELETE deliberately stays on the card -
 * permanent deletion always needs a human tap (safety).
 */
private val AGENT_AUTO_KINDS = setOf(
    LeadAction.Kind.CONFIRM,
    LeadAction.Kind.DRAFT,
    LeadAction.Kind.STATUS,
    LeadAction.Kind.UPDATE,
    LeadAction.Kind.BULK
)

/**
 * System instruction for the hidden "extract a web search query" step of the
 * Tavily search flow. The model must reply with only the query, or NONE.
 */
private const val SEARCH_QUERY_EXTRACTION_INSTRUCTION =
    "Convert the user's request below into ONE short English web search query (maximum 8 words). " +
        "Reply with ONLY the query text - no quotes, no explanation, no markdown. " +
        "If the request does not need fresh information from the internet (for example personal CRM data, math, translation or general conversation), reply exactly: NONE"

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
    fun setDirectActionHandler(handler: ((LeadAction) -> Unit)?)
    /**
     * Agent Mode handler: card-based actions (CONFIRM, DRAFT, STATUS, UPDATE)
     * are dispatched here WITHOUT a confirmation card when Agent Mode is ON.
     * DELETE is never dispatched here - it always keeps its confirmation card.
     */
    fun setAgentActionHandler(handler: ((LeadAction) -> Unit)?)
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
    private var directActionHandler: ((LeadAction) -> Unit)? = null

    @Volatile
    private var agentActionHandler: ((LeadAction) -> Unit)? = null

    /**
     * Appended to the system instruction while Agent Mode is ON: tells the
     * model that its action blocks are now executed automatically, so it
     * phrases replies accordingly (and that DELETE still asks the user).
     */
    private val agentModeInstruction: String =
        "\n\nAGENT MODE IS ON (user opted in in Settings):\n" +
        "The app now AUTOMATICALLY EXECUTES your LEAD_CONFIRM, LEAD_DRAFT, LEAD_STATUS and LEAD_UPDATE action blocks without asking the user for confirmation.\n" +
        "So when you emit one of these blocks, phrase your visible reply as if the action is being done right now (for example: 'Rahul ka lead save ho gaya ✅' or 'Status update ho gaya ✅').\n" +
        "LEAD_DELETE still shows a confirmation to the user (safety) - say a confirmation will appear.\n" +
        "All other parts of the protocol above stay exactly the same."

    private data class RequestToken(val id: Long, val conversationGeneration: Long)

    private val _uiState = MutableStateFlow(ChatUiState())
    override val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    private val stateLock = Any()
    private var nextRequestId = 0L
    private var conversationGeneration = 0L
    private var activeRequestId: Long? = null

    /**
     * Accumulates the RAW streamed text of the active request (including the
     * hidden action block). Only one request can be active at a time, so a
     * single buffer is safe.
     */
    private val streamingRawBuffer = StringBuilder()
    private var requestStartedAtMs = 0L

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
            routeStreaming(token, messages)
        } finally {
            finishRequest(token)
        }
    }

    override suspend fun retry() {
        val token = beginRequest() ?: return
        try {
            val messages = synchronized(stateLock) {
                if (!isCurrent(token)) return
                val current = _uiState.value.messages
                val last = current.lastOrNull() ?: return
                if (last.role != ChatRole.ASSISTANT) return
                // After an error reply: drop the error message. After a normal
                // reply: drop that reply - the same last user question is then
                // asked again (regenerate).
                var base = if (last.isError) current.filterNot { it.isError }
                else current.dropLast(1)
                // A partial streamed reply may sit right before the error -
                // drop it too so the real last user question is re-asked.
                if (base.lastOrNull()?.role == ChatRole.ASSISTANT) {
                    base = base.dropLast(1)
                }
                if (base.lastOrNull()?.role != ChatRole.USER) return
                _uiState.value = _uiState.value.copy(
                    messages = base, isThinking = true, errorMessage = null,
                    canRetry = false, activeProvider = null, pendingLeadAction = null
                )
                base
            }
            routeStreaming(token, messages)
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

    private fun beginRequest(): RequestToken? =
        synchronized(stateLock) {
            if (activeRequestId != null || _uiState.value.isThinking) return@synchronized null
            nextRequestId++
            activeRequestId = nextRequestId
            requestStartedAtMs = System.currentTimeMillis()
            streamingRawBuffer.setLength(0)
            RequestToken(nextRequestId, conversationGeneration)
        }

    private fun isCurrent(token: RequestToken): Boolean =
        activeRequestId == token.id && conversationGeneration == token.conversationGeneration

    /**
     * Runs the streaming request: tokens arrive through [onToken] and are
     * appended to (or create) the in-flight assistant message; when the
     * provider finishes, [applyResultIfCurrent] finalizes it.
     */
    private suspend fun routeStreaming(token: RequestToken, messages: List<ChatMessage>) {
        val startedAtMs = requestStartedAtMs
        val onToken: (String) -> Unit = { delta ->
            synchronized(stateLock) {
                if (isCurrent(token)) appendStreamingToken(delta)
            }
        }
        val result = safelyRouteStreaming(messages, onToken)
        applyResultIfCurrent(token, result, startedAtMs)
    }

    private suspend fun safelyRouteStreaming(
        messages: List<ChatMessage>,
        onToken: (String) -> Unit
    ): AIProviderResult = try {
        // The snapshot is rebuilt for every request so the model always sees
        // the current leads (a failing provider never breaks the chat).
        val snapshot = try {
            crmSnapshotProvider?.invoke().orEmpty()
        } catch (t: Throwable) {
            ""
        }
        // Web search (Tavily) runs BEFORE the main request when the user's
        // message looks like a search ask; any failure degrades to "".
        val searchContext = try {
            buildSearchContext(messages)
        } catch (t: Throwable) {
            ""
        }
        val agentBlock = if (isAgentModeOn()) agentModeInstruction else ""
        router.routeChatStreaming(
            messages,
            fullSystemInstruction + snapshot + searchContext + agentBlock,
            onToken
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        AIProviderResult.Failure(
            providerName = "Router",
            errorMessage = t.message?.takeIf { it.isNotBlank() } ?: "I'm sorry, something went wrong while contacting the AI service. Please try again.",
            isRetryable = true
        )
    }

    /** Agent Mode flag read; must never throw. */
    private fun isAgentModeOn(): Boolean = try {
        AIConfig.isAgentMode
    } catch (_: Throwable) {
        false
    }

    /**
     * Web search (Tavily) pipeline, run before the main AI request:
     *  1. Only when a Tavily key is saved AND the last user message looks
     *     like a search request, ask the AI for ONE short English search
     *     query (or the word NONE when no web info is really needed).
     *  2. If a query came back, fetch results from Tavily.
     *  3. Return a context block to append to the system instruction
     *     (empty string = no search, or search produced nothing).
     *
     * Any failure degrades to an empty block - search must never break chat.
     */
    private suspend fun buildSearchContext(messages: List<ChatMessage>): String {
        val userMessage = messages.lastOrNull { it.role == ChatRole.USER }?.content.orEmpty()
        if (userMessage.isBlank()) return ""

        if (!TavilySearchClient.hasKey()) {
            return "WEB SEARCH STATUS: not configured. If the user asks to search the internet or the web, or wants live/fresh information, tell them (in their language) that web search is not set up yet and they can add a Tavily key in Settings > AI API Keys - then answer from your own knowledge.\n\n"
        }

        if (!SearchIntentGate.looksLikeSearchRequest(userMessage)) return ""

        // Stage 1: the AI converts the request into a short English query,
        // or NONE when web information is not actually needed.
        val extractionResult = router.routeChat(
            listOf(ChatMessage(role = ChatRole.USER, content = userMessage)),
            SEARCH_QUERY_EXTRACTION_INSTRUCTION
        )
        var query = (extractionResult as? AIProviderResult.Success)?.text?.trim().orEmpty()
        if (query.length > 120) query = query.take(120)
        val noSearch = setOf("NONE", "NONE.", "N/A", "NA", "NOT APPLICABLE")
        if (query.isEmpty() || query in noSearch) return ""
        // A stray hidden action block is never a valid search query.
        if (query.contains("[LEAD_", ignoreCase = true)) return ""

        val results = TavilySearchClient.search(query)
        if (results.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("WEB SEARCH RESULTS (fresh from the internet - use them to answer the user):\n")
        results.forEachIndexed { i, r ->
            sb.append("${i + 1}. ${r.title.ifBlank { "Result ${i + 1}" }}\n")
            sb.append("URL: ${r.url}\n")
            sb.append(r.content.take(700))
            sb.append("\n\n")
        }
        sb.append("Answer the user using these results, in the user's language. If the results are not useful, say so briefly and answer from your own knowledge. End your answer with a line 'Sources:' listing the top 1-3 URLs.\n\n")
        return sb.toString()
    }

    /**
     * Appends one streamed delta to the in-flight assistant message.
     * Must be called with [stateLock] held.
     */
    private fun appendStreamingToken(delta: String) {
        streamingRawBuffer.append(delta)
        val display = streamVisibleText(streamingRawBuffer.toString())
        val messages = _uiState.value.messages
        val lastIdx = messages.lastIndex
        val last = messages.getOrNull(lastIdx)
        val updated: List<ChatMessage> =
            if (last != null && last.role == ChatRole.ASSISTANT && last.isStreaming) {
                val list = messages.toMutableList()
                list[lastIdx] = last.copy(content = display)
                list
            } else {
                messages + ChatMessage(role = ChatRole.ASSISTANT, content = display, isStreaming = true)
            }
        _uiState.value = _uiState.value.copy(messages = updated)
    }

    /**
     * Strips the hidden LEAD_* action block from the raw stream while it is
     * still arriving, so the block (or part of it) is never visible to the
     * user. The final parse of the complete text decides the real visible
     * text; this only protects the live view.
     */
    private fun streamVisibleText(raw: String): String {
        var display = raw
        val idx = display.indexOf("[LEAD_")
        if (idx >= 0) display = display.substring(0, idx)
        // Also drop a partial trailing tag prefix (for example a stream that
        // split "[LEAD_CONFIRM]" across two tokens).
        val tag = "[LEAD_"
        var cut = 0
        while (cut < tag.length && display.endsWith(tag.substring(0, cut + 1))) cut++
        if (cut > 0) display = display.substring(0, display.length - cut)
        return display
    }

    private fun applyResultIfCurrent(token: RequestToken, result: AIProviderResult, startedAtMs: Long) {
        var directAction: LeadAction? = null
        var agentAction: LeadAction? = null
        synchronized(stateLock) {
            if (!isCurrent(token)) return
            when (result) {
                is AIProviderResult.Success -> {
                    // The reply may end with a hidden action block. It is
                    // stripped from the visible text; card-based types become a
                    // pending confirmation card, direct types (ARCHIVE,
                    // WHATSAPP) are dispatched to the handler after the lock
                    // is released.
                    val parsed = LeadActionParser.parse(result.text)
                    val visibleText = when (parsed) {
                        is ParsedLeadReply.Normal -> parsed.text
                        is ParsedLeadReply.WithAction -> parsed.visibleText
                    }
                    val action = (parsed as? ParsedLeadReply.WithAction)?.action
                    directAction = action?.takeIf {
                        it.kind == LeadAction.Kind.ARCHIVE ||
                            it.kind == LeadAction.Kind.WHATSAPP
                    }
                    // Agent Mode: card kinds (except DELETE) are executed
                    // automatically - no confirmation card at all.
                    agentAction = action?.takeIf {
                        isAgentModeOn() && it.kind in AGENT_AUTO_KINDS
                    }
                    val messages = finalizeStreamingMessage(
                        rawFullText = result.text,
                        visibleText = visibleText,
                        durationMs = System.currentTimeMillis() - startedAtMs,
                        providerName = result.providerName
                    )
                    _uiState.value = _uiState.value.copy(
                        messages = messages, isThinking = false,
                        errorMessage = null, canRetry = false, activeProvider = result.providerName,
                        pendingLeadAction = action?.takeIf {
                            agentAction == null && it.kind in CARD_ACTION_KINDS
                        }
                    )
                }
                is AIProviderResult.Failure -> {
                    // Keep any partial text that already arrived on screen
                    // (an empty bubble is dropped); the error message follows
                    // it so the user can Retry.
                    val messages = finalizeStreamingMessage(
                        rawFullText = null,
                        visibleText = null,
                        durationMs = 0L,
                        providerName = null
                    )
                    val errorMessage = ChatMessage(
                        role = ChatRole.ASSISTANT, content = result.errorMessage,
                        isError = true, providerName = result.providerName
                    )
                    _uiState.value = _uiState.value.copy(
                        messages = messages + errorMessage, isThinking = false,
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
        // Agent Mode auto-actions run the same way: outside the lock, and a
        // failure must never crash the chat (the AI reply is already on screen).
        agentAction?.let { action ->
            try {
                agentActionHandler?.invoke(action)
            } catch (_: Throwable) {
                // A failed auto-action must never crash the chat.
            }
        }
    }

    /**
     * Turns the in-flight streaming assistant message into its final form and
     * returns the new message list. Must be called with [stateLock] held.
     *
     * - Success ([rawFullText] != null): the message content becomes the
     *   parsed [visibleText], streaming stops and the reply time is stored.
     * - Failure: the partial visible text already shown is kept (if any); an
     *   empty bubble is dropped entirely.
     */
    private fun finalizeStreamingMessage(
        rawFullText: String?,
        visibleText: String?,
        durationMs: Long,
        providerName: String?
    ): List<ChatMessage> {
        val messages = _uiState.value.messages
        val lastIdx = messages.lastIndex
        val last = messages.getOrNull(lastIdx)

        if (last == null || last.role != ChatRole.ASSISTANT || !last.isStreaming) {
            // Defensive: no streaming message exists (no token ever arrived
            // on the success path).
            if (rawFullText != null) {
                return messages + ChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = (visibleText ?: rawFullText).ifBlank { rawFullText },
                    providerName = providerName,
                    responseDurationMs = durationMs
                )
            }
            return messages
        }

        val list = messages.toMutableList()
        if (rawFullText != null) {
            list[lastIdx] = last.copy(
                content = (visibleText ?: rawFullText).ifBlank { rawFullText },
                isStreaming = false,
                providerName = providerName ?: last.providerName,
                responseDurationMs = durationMs
            )
        } else if (last.content.isNotBlank()) {
            // Failure with partial text: keep what the user already saw.
            list[lastIdx] = last.copy(isStreaming = false)
        } else {
            // Failure without any partial text: drop the empty bubble.
            list.removeAt(lastIdx)
        }
        return list
    }

    override fun setCrmSnapshotProvider(provider: (() -> String)?) {
        crmSnapshotProvider = provider
    }

    override fun setDirectActionHandler(handler: ((LeadAction) -> Unit)?) {
        directActionHandler = handler
    }

    override fun setAgentActionHandler(handler: ((LeadAction) -> Unit)?) {
        agentActionHandler = handler
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
