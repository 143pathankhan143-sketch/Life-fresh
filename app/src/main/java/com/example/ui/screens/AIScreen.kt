package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.font.FontFamily
import com.example.ai.chat.formatter.AIMessageFormatter
import com.example.ai.chat.formatter.FormattedBlock
import com.example.ai.chat.lead.LeadAction
import com.example.ai.chat.model.ChatMessage
import com.example.ai.chat.model.ChatRole
import com.example.ai.chat.viewmodel.AIChatViewModel
import com.example.ui.viewmodel.CRMViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AIScreen(
    viewModel: CRMViewModel? = null,
    chatViewModel: AIChatViewModel = viewModel(),
    onExit: () -> Unit = {},
    onAddLeadTrigger: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    LaunchedEffect(viewModel) {
        chatViewModel.attachCrmViewModel(viewModel)
    }

    val uiState by chatViewModel.uiState.collectAsStateWithLifecycle()
    val inputText by chatViewModel.inputText.collectAsStateWithLifecycle()

    // Chat history (sessions saved in Room, already scoped to the signed-in uid)
    val sessionsFlow = remember(viewModel) {
        viewModel?.dbChatSessions ?: MutableStateFlow<List<ChatSession>>(emptyList())
    }
    val sessions by sessionsFlow.collectAsStateWithLifecycle()
    val activeSessionId = viewModel?.activeSessionId?.value
    var showHistoryDialog by remember { mutableStateOf(false) }
    var pendingDeleteSession by remember { mutableStateOf<ChatSession?>(null) }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Automatically scroll to latest message, thinking state or pending lead card
    val hasPendingLeadCard = uiState.pendingLeadAction != null
    LaunchedEffect(uiState.messages.size, uiState.isThinking, hasPendingLeadCard) {
        val totalItems = uiState.messages.size +
            (if (uiState.isThinking) 1 else 0) +
            (if (hasPendingLeadCard) 1 else 0)
        if (totalItems > 0) {
            coroutineScope.launch {
                listState.animateScrollToItem(totalItems - 1)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .testTag("ai_chat_screen")
    ) {
        AIChatHeader(
            hasMessages = uiState.messages.isNotEmpty(),
            onExit = onExit,
            onClearChat = { chatViewModel.clearConversation() },
            onOpenHistory = { showHistoryDialog = true }
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (uiState.messages.isEmpty()) {
                AIEmptyState(
                    onSuggestionClick = { prompt ->
                        chatViewModel.sendMessage(prompt)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("chat_messages_list"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(
                        items = uiState.messages,
                        key = { it.id }
                    ) { message ->
                        when (message.role) {
                            ChatRole.USER -> UserMessageBubble(message)
                            ChatRole.ASSISTANT -> AssistantMessageBubble(
                                message = message,
                                canRetry = uiState.canRetry && message.id == uiState.messages.lastOrNull()?.id,
                                onRetry = { chatViewModel.retry() },
                                onNavigateToSettings = onNavigateToSettings
                            )
                            ChatRole.SYSTEM -> {}
                        }
                    }

                    if (uiState.isThinking) {
                        item(key = "thinking_state_item") {
                            AIThinkingBubble()
                        }
                    }

                    // Pending lead proposed by the AI - only the user's tap saves it
                    uiState.pendingLeadAction?.let { pendingAction ->
                        item(key = "pending_lead_action_card") {
                            PendingLeadActionCard(
                                action = pendingAction,
                                onSave = { chatViewModel.confirmPendingLead(saveAsDraft = false) },
                                onSaveAsDraft = { chatViewModel.confirmPendingLead(saveAsDraft = true) },
                                onDismiss = { chatViewModel.cancelPendingLead() }
                            )
                        }
                    }
                }
            }
        }

        // Bottom composer anchored cleanly above the keyboard
        AIChatComposer(
            inputText = inputText,
            isThinking = uiState.isThinking,
            onInputChange = { chatViewModel.onInputChanged(it) },
            onSend = {
                chatViewModel.sendMessage()
                keyboardController?.hide()
                focusManager.clearFocus()
            }
        )
    }

    if (showHistoryDialog) {
        AIChatHistoryDialog(
            sessions = sessions,
            activeSessionId = activeSessionId,
            onOpenSession = { session ->
                showHistoryDialog = false
                chatViewModel.openSession(session.id, session.messages)
            },
            onRequestDelete = { session -> pendingDeleteSession = session },
            onDismiss = { showHistoryDialog = false }
        )
    }

    pendingDeleteSession?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSession = null },
            title = { Text("Delete this chat?") },
            text = {
                Text(
                    text = "\"${session.title}\" will be removed permanently.",
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel?.deleteSession(session.id)
                        // If the deleted session was on screen, start fresh
                        if (session.id == activeSessionId) chatViewModel.clearConversation()
                        pendingDeleteSession = null
                        showHistoryDialog = false
                    },
                    modifier = Modifier.testTag("history_delete_confirm")
                ) {
                    Text(
                        text = "Delete",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSession = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun AIChatHeader(
    onExit: () -> Unit,
    hasMessages: Boolean,
    onClearChat: () -> Unit,
    onOpenHistory: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ai_header")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onExit,
                    modifier = Modifier.size(36.dp).testTag("btn_back_ai")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "LifeFresh AI",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = "LifeFresh AI",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.2).sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasMessages) {
                    IconButton(
                        onClick = onClearChat,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("btn_clear_chat")
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = "New Chat",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                IconButton(
                    onClick = onOpenHistory,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("btn_history_ai")
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = "Chat History",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AIChatHistoryDialog(
    sessions: List<ChatSession>,
    activeSessionId: String?,
    onOpenSession: (ChatSession) -> Unit,
    onRequestDelete: (ChatSession) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .testTag("history_dialog")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Chat History",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("history_close_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (sessions.isEmpty()) {
                    Text(
                        text = "No past chats yet. Start a conversation and it will appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp, horizontal = 8.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(sessions, key = { it.id }) { session ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onOpenSession(session) }
                                    .padding(horizontal = 10.dp, vertical = 10.dp)
                                    .testTag("history_session_row"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (session.id == activeSessionId) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = session.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Medium
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${formatSessionDate(session.timestamp)}  ·  ${session.messages.size} messages",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { onRequestDelete(session) },
                                    modifier = Modifier
                                        .size(32.dp)
                                        .testTag("history_delete_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete chat",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun formatSessionDate(timestamp: Long): String {
    val format = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
    return format.format(Date(timestamp))
}

@Composable
private fun AIEmptyState(
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(horizontal = 24.dp)
            .padding(bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "How can I help you today?",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.3).sp
            ),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Ask anything about your leads, follow-ups & CRM",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        val suggestions = listOf(
            "Aaj ke top 3 calls kaun se hain?",
            "What can you help me with?",
            "How to organize client follow-ups?",
            "नमस्ते! आप कैसे मदद कर सकते हैं?"
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            suggestions.forEach { suggestion ->
                CompactSuggestionChip(
                    text = suggestion,
                    onClick = { onSuggestionClick(suggestion) }
                )
            }
        }
    }
}

@Composable
private fun CompactSuggestionChip(
    text: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = androidx.compose.foundation.BorderStroke(
            0.5.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier.fillMaxWidth(0.92f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun UserMessageBubble(message: ChatMessage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("user_message_bubble"),
        horizontalArrangement = Arrangement.End
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 290.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = 18.dp,
                        bottomEnd = 4.dp
                    )
                )
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun AssistantMessageBubble(
    message: ChatMessage,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onNavigateToSettings: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ai_message_bubble"),
        horizontalArrangement = Arrangement.Start
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "LifeFresh AI",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.5.sp
                    ),
                    color = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }

            if (message.isError) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    border = androidx.compose.foundation.BorderStroke(
                        0.5.dp,
                        MaterialTheme.colorScheme.error.copy(alpha = 0.25f)
                    ),
                    modifier = Modifier.fillMaxWidth(0.92f)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodySmall.copy(
                                lineHeight = 18.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        val isKeyOrQuotaError = message.content.contains("key", ignoreCase = true) ||
                            message.content.contains("quota", ignoreCase = true) ||
                            message.content.contains("limit", ignoreCase = true) ||
                            message.content.contains("settings", ignoreCase = true) ||
                            message.content.contains("proxy", ignoreCase = true)

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (canRetry) {
                                OutlinedButton(
                                    onClick = onRetry,
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .height(32.dp)
                                        .testTag("retry_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Retry",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }

                            if (isKeyOrQuotaError) {
                                OutlinedButton(
                                    onClick = onNavigateToSettings,
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .height(32.dp)
                                        .testTag("btn_open_ai_settings")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Open AI Settings",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                val blocks = remember(message.content) {
                    AIMessageFormatter.parseMessageBlocks(message.content)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 2.dp, end = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (block in blocks) {
                        when (block) {
                            is FormattedBlock.Paragraph -> {
                                Text(
                                    text = block.text,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 15.sp,
                                        lineHeight = 23.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                            is FormattedBlock.CodeBlock -> {
                                AssistantCodeBlock(block = block)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantCodeBlock(block: FormattedBlock.CodeBlock) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = androidx.compose.foundation.BorderStroke(
            0.5.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            if (!block.language.isNullOrBlank()) {
                Text(
                    text = block.language,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            Text(
                text = block.code,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.horizontalScroll(rememberScrollState())
            )
        }
    }
}

/** Formats "yyyy-MM-dd" (+ optional "HH:mm") for the confirmation card display. */
private fun formatReminderDisplay(action: LeadAction): String {
    val pretty = try {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(action.reminderDate)
        if (parsed != null) SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(parsed)
        else action.reminderDate
    } catch (e: Exception) {
        action.reminderDate
    }
    return if (action.reminderTime.isNotBlank()) "$pretty, ${action.reminderTime}" else pretty
}

/** Same as above, but for the UPDATE card (setReminderDate/setReminderTime). */
private fun formatUpdateReminderDisplay(action: LeadAction): String {
    val pretty = try {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(action.setReminderDate)
        if (parsed != null) SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(parsed)
        else action.setReminderDate
    } catch (e: Exception) {
        action.setReminderDate
    }
    return if (action.setReminderTime.isNotBlank()) "$pretty, ${action.setReminderTime}" else pretty
}

@Composable
private fun PendingLeadActionCard(
    action: LeadAction,
    onSave: () -> Unit,
    onSaveAsDraft: () -> Unit,
    onDismiss: () -> Unit
) {
    val isDraft = action.kind == LeadAction.Kind.DRAFT
    val isStatus = action.kind == LeadAction.Kind.STATUS
    val isUpdate = action.kind == LeadAction.Kind.UPDATE
    val isDelete = action.kind == LeadAction.Kind.DELETE
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ai_lead_action_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = when {
                    isStatus -> "Status update karein?"
                    isUpdate -> "Changes apply karein?"
                    isDelete -> "Delete karein?"
                    isDraft -> "Draft save karein?"
                    else -> "Lead save karein?"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            if (isUpdate) {
                // UPDATE card: client + every change, one row each.
                Text(
                    text = "Client: ${action.name.ifBlank { "Unknown" }}",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (action.setMobile.isNotBlank()) {
                    Text(
                        text = "Naya number: ${action.setMobile}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (action.setName.isNotBlank()) {
                    Text(
                        text = "Naya naam: ${action.setName}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (action.addDiseases.isNotEmpty()) {
                    Text(
                        text = "Naya wellness: ${action.addDiseases.joinToString(", ")}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (action.note.isNotBlank()) {
                    Text(
                        text = "Note add: ${action.note}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                when {
                    action.removeReminder ->
                        Text(
                            text = "Reminder: hata dena",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    action.setReminderDate.isNotBlank() ->
                        Text(
                            text = "Naya reminder: ${formatUpdateReminderDisplay(action)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                }
            } else if (isDelete) {
                // DELETE card: light confirmation only, not a scary dialog.
                Text(
                    text = "Client: ${action.name.ifBlank { "Unknown" }}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Yeh lead hamesha ke liye delete ho jayega. Wapas nahi aa sakta.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = if (isStatus) {
                        "Client: ${action.name.ifBlank { "Unknown" }}"
                    } else {
                        "Naam: ${action.name.ifBlank { "Unknown" }}"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (action.mobile.isNotBlank()) {
                    Text(
                        text = "Mobile: ${action.mobile}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (action.diseases.isNotEmpty()) {
                    Text(
                        text = "Wellness: ${action.diseases.joinToString(", ")}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (action.note.isNotBlank()) {
                    Text(
                        text = "Note: ${action.note}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (action.reminderDate.isNotBlank()) {
                    Text(
                        text = "Reminder: ${formatReminderDisplay(action)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (isStatus) {
                    Text(
                        text = "Naya status: ${action.status}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when {
                    isStatus -> Button(
                        onClick = onSave,
                        modifier = Modifier.testTag("ai_lead_status_confirm")
                    ) {
                        Text("Update karo")
                    }
                    isUpdate -> Button(
                        onClick = onSave,
                        modifier = Modifier.testTag("ai_lead_update_confirm")
                    ) {
                        Text("Update karo")
                    }
                    isDelete -> OutlinedButton(
                        onClick = onSave,
                        modifier = Modifier.testTag("ai_lead_delete_confirm")
                    ) {
                        Text("Haan, delete karo")
                    }
                    else -> {
                        if (!isDraft) {
                            Button(
                                onClick = onSave,
                                modifier = Modifier.testTag("ai_lead_save_confirm")
                            ) {
                                Text("Save Lead")
                            }
                        }
                        OutlinedButton(
                            onClick = onSaveAsDraft,
                            modifier = Modifier.testTag("ai_lead_save_draft")
                        ) {
                            Text("Draft me rakho")
                        }
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("ai_lead_save_cancel")
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun AIThinkingBubble() {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha_pulse"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("thinking_indicator"),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = "Thinking...",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
            )
        }
    }
}

@Composable
private fun AIChatComposer(
    inputText: String,
    isThinking: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit
) {
    val isSendEnabled = inputText.isNotBlank() && !isThinking

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                border = androidx.compose.foundation.BorderStroke(
                    0.5.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = inputText,
                        onValueChange = onInputChange,
                        placeholder = {
                            Text(
                                text = "Message LifeFresh AI",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 14.5.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("message_input"),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent
                        ),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (isSendEnabled) {
                                    onSend()
                                }
                            }
                        ),
                        maxLines = 4
                    )

                    IconButton(
                        onClick = onSend,
                        enabled = isSendEnabled,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSendEnabled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                            )
                            .testTag("send_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (isSendEnabled) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
    }
}

// Shared chat data classes used by CRMViewModel and the AI chat screen
enum class Sender { USER, AI }

data class MockMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String = "",
    val sender: Sender = Sender.USER,
    val timestamp: Long = System.currentTimeMillis(),
    val isError: Boolean = false,
    val isOfflineWarning: Boolean = false,
    val isConfirmation: Boolean = false,
    val actionCardType: String? = null
)

data class ChatSession(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String = "",
    val messages: List<MockMessage> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false
)


