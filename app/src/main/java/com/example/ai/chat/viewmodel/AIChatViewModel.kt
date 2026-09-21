package com.example.ai.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.chat.lead.LeadAction
import com.example.ai.chat.model.ChatMessage
import com.example.ai.chat.model.ChatRole
import com.example.ai.chat.model.ChatUiState
import com.example.ai.chat.repository.AIChatRepository
import com.example.ai.chat.repository.DefaultAIChatRepository
import com.example.ui.screens.MockMessage
import com.example.ui.screens.Sender
import com.example.ui.viewmodel.CRMViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AIChatViewModel(
    private val repository: AIChatRepository = DefaultAIChatRepository()
) : ViewModel() {
    val uiState: StateFlow<ChatUiState> = repository.uiState
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()
    private var activeRequestJob: Job? = null
    private var crmViewModel: CRMViewModel? = null
    private var persistenceStarted = false
    private var persistedMessageIds: Set<String> = emptySet()
    private var lastPersistedTimestamp = 0L

    /**
     * Binds the CRM view model for:
     *  - one-time restore of the last persisted conversation (survives
     *    screen exit, configuration change and process death), and
     *  - mirroring every new message into Room so the conversation is
     *    durable and can be restored on the next app launch.
     */
    fun attachCrmViewModel(viewModel: CRMViewModel?) {
        if (crmViewModel === viewModel) return
        crmViewModel = viewModel
        if (viewModel != null && !persistenceStarted) {
            persistenceStarted = true
            // The AI gets a fresh read-only CRM snapshot before every request.
            repository.setCrmSnapshotProvider { viewModel.buildCrmSnapshot() }
            viewModelScope.launch { startPersistence(viewModel) }
        }
    }

    private suspend fun startPersistence(crm: CRMViewModel) {
        // 1) Restore the previous conversation. Only when the UI is empty, so a
        //    live conversation is never clobbered by stale rows.
        if (repository.uiState.value.messages.isEmpty()) {
            val restored = crm.loadActiveSessionMessagesOnce()
            if (restored.isNotEmpty()) {
                repository.restoreMessages(restored.map { it.toChatMessage() })
                val state = repository.uiState.value
                persistedMessageIds = state.messages.map { it.id }.toSet()
                lastPersistedTimestamp = state.messages.map { it.timestamp }.maxOrNull() ?: 0L
            }
        }

        // 2) Mirror every new message into Room (fire-and-forget; CRMViewModel
        //    owns its own IO scope and guards on signed-in uid).
        repository.uiState.collect { state ->
            val newOnes = state.messages.filter { it.id !in persistedMessageIds }
            if (newOnes.isEmpty()) return@collect
            val sessionId = crm.ensureActiveSession(
                titleHint = newOnes.firstOrNull { it.role == ChatRole.USER }?.content
            ) ?: return@collect
            var ts = System.currentTimeMillis()
            newOnes.forEach { msg ->
                ts = if (ts > lastPersistedTimestamp) ts + 1 else lastPersistedTimestamp + 1
                lastPersistedTimestamp = ts
                crm.saveMessage(sessionId, msg.toMockMessage(timestamp = ts))
            }
            persistedMessageIds = state.messages.map { it.id }.toSet()
        }
    }

    fun onInputChanged(text: String) {
        _inputText.value = text
    }

    fun sendMessage(textOverride: String? = null) {
        val query = textOverride ?: _inputText.value
        val trimmed = query.trim()
        if (trimmed.isBlank() || uiState.value.isThinking || activeRequestJob?.isActive == true) return
        if (textOverride == null) _inputText.value = ""

        val job = viewModelScope.launch {
            repository.sendMessage(trimmed)
        }
        activeRequestJob = job
        job.invokeOnCompletion { if (activeRequestJob === job) activeRequestJob = null }
    }

    fun retry() {
        if (!uiState.value.canRetry || uiState.value.isThinking || activeRequestJob?.isActive == true) return
        val job = viewModelScope.launch { repository.retry() }
        activeRequestJob = job
        job.invokeOnCompletion { if (activeRequestJob === job) activeRequestJob = null }
    }

    /**
     * Saves the lead proposed by the AI. [saveAsDraft] forces a draft.
     * Only callable from the chat confirmation card - the AI itself can
     * never trigger a save.
     */
    fun confirmPendingLead(saveAsDraft: Boolean = false) {
        val action = uiState.value.pendingLeadAction ?: return
        val crm = crmViewModel
        repository.dismissPendingLead()
        if (crm == null) {
            repository.addLocalAssistantMessage("Lead save ke liye account zaroori hai. Pehle login karo.")
            return
        }
        val job = viewModelScope.launch {
            val message = if (action.kind == LeadAction.Kind.STATUS) {
                crm.updateLeadStatusFromAIChat(action)
            } else {
                val effective = if (saveAsDraft) action.copy(kind = LeadAction.Kind.DRAFT) else action
                crm.saveLeadFromAIChat(effective)
            }
            repository.addLocalAssistantMessage(message)
        }
        activeRequestJob = job
        job.invokeOnCompletion { if (activeRequestJob === job) activeRequestJob = null }
    }

    /** Dismisses the pending lead confirmation card without saving. */
    fun cancelPendingLead() {
        repository.dismissPendingLead()
    }

    /**
     * Starts a fresh conversation. The previous conversation stays in Room as
     * history (never deleted); the next message is stored in a new session.
     */
    fun clearConversation() {
        activeRequestJob?.cancel()
        activeRequestJob = null
        _inputText.value = ""
        persistedMessageIds = emptySet()
        crmViewModel?.setActiveSession(null)
        repository.clearConversation()
    }

    /**
     * Opens a past chat session from history: makes [sessionId] the active
     * session and replaces the on-screen conversation with [messages].
     *
     * The messages are already loaded from Room by the caller (see
     * [CRMViewModel.dbChatSessions]) — this function does no database access.
     * Loaded message ids are marked as persisted so the mirroring collector
     * never re-saves them.
     */
    fun openSession(sessionId: String, messages: List<MockMessage>) {
        val crm = crmViewModel ?: return
        activeRequestJob?.cancel()
        activeRequestJob = null
        _inputText.value = ""
        // Activate the session BEFORE swapping the UI state, so if the
        // persistence collector observes the restored messages, it writes to
        // this session (insert is idempotent via REPLACE).
        crm.setActiveSession(sessionId)
        repository.clearConversation()
        repository.restoreMessages(messages.map { it.toChatMessage() })
        val state = repository.uiState.value
        persistedMessageIds = state.messages.map { it.id }.toSet()
        lastPersistedTimestamp = state.messages.map { it.timestamp }.maxOrNull() ?: 0L
    }

    private fun MockMessage.toChatMessage(): ChatMessage = ChatMessage(
        id = id,
        role = if (sender == Sender.USER) ChatRole.USER else ChatRole.ASSISTANT,
        content = text,
        timestamp = timestamp,
        isError = isError
    )

    private fun ChatMessage.toMockMessage(timestamp: Long = this.timestamp): MockMessage = MockMessage(
        id = id,
        text = content,
        sender = if (role == ChatRole.USER) Sender.USER else Sender.AI,
        timestamp = timestamp,
        isError = isError,
        isConfirmation = false,
        actionCardType = null
    )
}
