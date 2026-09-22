package com.example.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.repository.AIServiceRepository
import com.example.data.security.AIQuotaManager
import kotlinx.coroutines.launch

/**
 * Dedicated "AI API Keys" settings screen (Settings > AI API Keys).
 *
 * Holds all four BYOK keys in one place - Gemini (primary AI), Groq (fast
 * fallback AI), OpenRouter (second AI, many models with one key) and Tavily
 * (web search for the AI) - plus the Agent Mode toggle.
 */
@Composable
fun SettingsApiKeysScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Quota badge
    var isUnlimited by remember { mutableStateOf(AIQuotaManager.isUnlimited(context)) }
    var remainingQuota by remember { mutableStateOf(AIQuotaManager.getRemainingQuota(context)) }

    // Agent Mode
    var agentMode by remember { mutableStateOf(AIQuotaManager.isAgentModeEnabled(context)) }
    var showAgentModeConfirm by remember { mutableStateOf(false) }

    // Key fields (one state block per provider)
    var geminiKey by remember { mutableStateOf(AIQuotaManager.getCustomGeminiKey(context) ?: "") }
    var geminiVisible by remember { mutableStateOf(false) }
    var isTestingGemini by remember { mutableStateOf(false) }
    var geminiStatus by remember { mutableStateOf<String?>(null) }
    var geminiStatusOk by remember { mutableStateOf(true) }

    var groqKey by remember { mutableStateOf(AIQuotaManager.getCustomGroqKey(context) ?: "") }
    var groqVisible by remember { mutableStateOf(false) }
    var isTestingGroq by remember { mutableStateOf(false) }
    var groqStatus by remember { mutableStateOf<String?>(null) }
    var groqStatusOk by remember { mutableStateOf(true) }

    var openRouterKey by remember { mutableStateOf(AIQuotaManager.getCustomOpenRouterKey(context) ?: "") }
    var openRouterVisible by remember { mutableStateOf(false) }
    var isTestingOpenRouter by remember { mutableStateOf(false) }
    var openRouterStatus by remember { mutableStateOf<String?>(null) }
    var openRouterStatusOk by remember { mutableStateOf(true) }

    var tavilyKey by remember { mutableStateOf(AIQuotaManager.getCustomTavilyKey(context) ?: "") }
    var tavilyVisible by remember { mutableStateOf(false) }
    var isTestingTavily by remember { mutableStateOf(false) }
    var tavilyStatus by remember { mutableStateOf<String?>(null) }
    var tavilyStatusOk by remember { mutableStateOf(true) }

    fun refreshQuota() {
        isUnlimited = AIQuotaManager.isUnlimited(context)
        remainingQuota = AIQuotaManager.getRemainingQuota(context)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 12.dp)
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("btn_api_keys_back")) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "AI API Keys",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Quota badge
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUnlimited) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                } else {
                    MaterialTheme.colorScheme.surface
                }
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Column {
                    Text(
                        text = "Daily AI quota",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (isUnlimited) {
                            "Unlimited - BYOK key set"
                        } else {
                            "$remainingQuota / ${AIQuotaManager.MAX_DAILY_FREE_QUOTA} free chats left today"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Agent Mode
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
        ) {
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                SettingsSwitchRow(
                    icon = Icons.Default.AutoAwesome,
                    title = "Agent Mode",
                    subtitle = "AI lead actions (save, draft, status, update) run automatically - no confirmation card. Complete leads go to Leads, incomplete to Drafts. Delete still asks for one tap (safety).",
                    checked = agentMode,
                    onCheckedChange = { newValue ->
                        if (newValue) {
                            showAgentModeConfirm = true
                        } else {
                            agentMode = false
                            AIQuotaManager.setAgentModeEnabled(context, false)
                        }
                    },
                    testTag = "agent_mode_switch"
                )
            }
        }

        // Gemini key
        ApiKeySection(
            title = "Gemini API Key",
            subtitle = "Primary AI (Google Gemini)",
            icon = Icons.Default.AutoAwesome,
            key = geminiKey,
            onKeyChange = { geminiKey = it; geminiStatus = null },
            isVisible = geminiVisible,
            onToggleVisible = { geminiVisible = !geminiVisible },
            onSave = {
                AIQuotaManager.saveCustomGeminiKey(context, geminiKey.trim())
                refreshQuota()
                geminiStatus = if (geminiKey.isBlank()) "Gemini key cleared." else "Gemini key saved!"
                geminiStatusOk = true
            },
            onTest = {
                val trimmed = geminiKey.trim()
                if (trimmed.isBlank()) {
                    geminiStatus = "Please enter an API key to test."
                    geminiStatusOk = false
                } else {
                    isTestingGemini = true
                    geminiStatus = "Testing key connectivity..."
                    geminiStatusOk = true
                    coroutineScope.launch {
                        val result = AIServiceRepository().testGeminiKey(trimmed)
                        isTestingGemini = false
                        result.fold(
                            onSuccess = { geminiStatus = "Key Valid & Connected!"; geminiStatusOk = true },
                            onFailure = { e ->
                                geminiStatus = "Key test failed: ${e.message?.takeIf { it.isNotBlank() } ?: "Unknown error"}"
                                geminiStatusOk = false
                            }
                        )
                    }
                }
            },
            onClear = {
                geminiKey = ""
                AIQuotaManager.saveCustomGeminiKey(context, null)
                refreshQuota()
                geminiStatus = "Gemini key cleared."
                geminiStatusOk = true
            },
            isTesting = isTestingGemini,
            status = geminiStatus,
            statusOk = geminiStatusOk,
            info = "Get a free Gemini key from aistudio.google.com. This is the main AI for your chats.",
            tagPrefix = "gemini"
        )

        // Groq key
        ApiKeySection(
            title = "Groq API Key",
            subtitle = "Fast fallback AI (Groq)",
            icon = Icons.Default.Speed,
            key = groqKey,
            onKeyChange = { groqKey = it; groqStatus = null },
            isVisible = groqVisible,
            onToggleVisible = { groqVisible = !groqVisible },
            onSave = {
                AIQuotaManager.saveCustomGroqKey(context, groqKey.trim())
                refreshQuota()
                groqStatus = if (groqKey.isBlank()) "Groq key cleared." else "Groq key saved!"
                groqStatusOk = true
            },
            onTest = {
                val trimmed = groqKey.trim()
                if (trimmed.isBlank()) {
                    groqStatus = "Please enter an API key to test."
                    groqStatusOk = false
                } else {
                    isTestingGroq = true
                    groqStatus = "Testing key connectivity..."
                    groqStatusOk = true
                    coroutineScope.launch {
                        val result = AIServiceRepository().testGroqKey(trimmed)
                        isTestingGroq = false
                        result.fold(
                            onSuccess = { groqStatus = "Key Valid & Connected!"; groqStatusOk = true },
                            onFailure = { e ->
                                groqStatus = "Key test failed: ${e.message?.takeIf { it.isNotBlank() } ?: "Unknown error"}"
                                groqStatusOk = false
                            }
                        )
                    }
                }
            },
            onClear = {
                groqKey = ""
                AIQuotaManager.saveCustomGroqKey(context, null)
                refreshQuota()
                groqStatus = "Groq key cleared."
                groqStatusOk = true
            },
            isTesting = isTestingGroq,
            status = groqStatus,
            statusOk = groqStatusOk,
            info = "Get your free Groq key from console.groq.com. If Gemini is slow or unavailable, Groq gives fast replies automatically.",
            tagPrefix = "groq"
        )

        // OpenRouter key
        ApiKeySection(
            title = "OpenRouter API Key",
            subtitle = "Second AI - many models, one key",
            icon = Icons.Default.Cloud,
            key = openRouterKey,
            onKeyChange = { openRouterKey = it; openRouterStatus = null },
            isVisible = openRouterVisible,
            onToggleVisible = { openRouterVisible = !openRouterVisible },
            onSave = {
                AIQuotaManager.saveCustomOpenRouterKey(context, openRouterKey.trim())
                refreshQuota()
                openRouterStatus = if (openRouterKey.isBlank()) "OpenRouter key cleared." else "OpenRouter key saved!"
                openRouterStatusOk = true
            },
            onTest = {
                val trimmed = openRouterKey.trim()
                if (trimmed.isBlank()) {
                    openRouterStatus = "Please enter an API key to test."
                    openRouterStatusOk = false
                } else {
                    isTestingOpenRouter = true
                    openRouterStatus = "Testing key connectivity..."
                    openRouterStatusOk = true
                    coroutineScope.launch {
                        val result = AIServiceRepository().testOpenRouterKey(trimmed)
                        isTestingOpenRouter = false
                        result.fold(
                            onSuccess = { openRouterStatus = it; openRouterStatusOk = true },
                            onFailure = { e ->
                                openRouterStatus = "Key test failed: ${e.message?.takeIf { it.isNotBlank() } ?: "Unknown error"}"
                                openRouterStatusOk = false
                            }
                        )
                    }
                }
            },
            onClear = {
                openRouterKey = ""
                AIQuotaManager.saveCustomOpenRouterKey(context, null)
                refreshQuota()
                openRouterStatus = "OpenRouter key cleared."
                openRouterStatusOk = true
            },
            isTesting = isTestingOpenRouter,
            status = openRouterStatus,
            statusOk = openRouterStatusOk,
            info = "Get a free OpenRouter key from openrouter.ai. One key gives access to DeepSeek and many other models - used automatically when Gemini is down.",
            tagPrefix = "openrouter"
        )

        // Tavily key
        ApiKeySection(
            title = "Tavily API Key",
            subtitle = "Web search for LifeFresh AI",
            icon = Icons.Default.Search,
            key = tavilyKey,
            onKeyChange = { tavilyKey = it; tavilyStatus = null },
            isVisible = tavilyVisible,
            onToggleVisible = { tavilyVisible = !tavilyVisible },
            onSave = {
                AIQuotaManager.saveCustomTavilyKey(context, tavilyKey.trim())
                tavilyStatus = if (tavilyKey.isBlank()) "Tavily key cleared." else "Tavily key saved!"
                tavilyStatusOk = true
            },
            onTest = {
                val trimmed = tavilyKey.trim()
                if (trimmed.isBlank()) {
                    tavilyStatus = "Please enter an API key to test."
                    tavilyStatusOk = false
                } else {
                    isTestingTavily = true
                    tavilyStatus = "Testing key connectivity..."
                    tavilyStatusOk = true
                    coroutineScope.launch {
                        val result = AIServiceRepository().testTavilyKey(trimmed)
                        isTestingTavily = false
                        result.fold(
                            onSuccess = { tavilyStatus = it; tavilyStatusOk = true },
                            onFailure = { e ->
                                tavilyStatus = "Key test failed: ${e.message?.takeIf { it.isNotBlank() } ?: "Unknown error"}"
                                tavilyStatusOk = false
                            }
                        )
                    }
                }
            },
            onClear = {
                tavilyKey = ""
                AIQuotaManager.saveCustomTavilyKey(context, null)
                tavilyStatus = "Tavily key cleared."
                tavilyStatusOk = true
            },
            isTesting = isTestingTavily,
            status = tavilyStatus,
            statusOk = tavilyStatusOk,
            info = "Get a free Tavily key from tavily.com (1000 searches/month free). With a key, ask the AI to 'search karo' or 'web se batao' and it answers with live results + links.",
            tagPrefix = "tavily"
        )

        Spacer(modifier = Modifier.height(16.dp))
    }

    // Agent Mode confirm popup (only when turning ON)
    if (showAgentModeConfirm) {
        AlertDialog(
            onDismissRequest = { showAgentModeConfirm = false },
            title = { Text("Agent Mode ON karo?") },
            text = {
                Text(
                    "Jab Agent Mode ON hoga, LifeFresh AI lead actions (save, draft, status, update) seedha khud execute karega - confirmation card nahi aayega.\n\n" +
                        "- Complete lead data → Leads me save\n" +
                        "- Incomplete data → Drafts me save\n" +
                        "- Delete pehle bhi ek tap maangega (safety)\n\n" +
                        "Aap isse kisi bhi waqt yahin se OFF kar sakte ho."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        agentMode = true
                        AIQuotaManager.setAgentModeEnabled(context, true)
                        showAgentModeConfirm = false
                    }
                ) {
                    Text("Turn On")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAgentModeConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * One reusable key card: icon + title, password input with show/hide,
 * Save / Test / Clear buttons, a status line and an info hint.
 */
@Composable
private fun ApiKeySection(
    title: String,
    subtitle: String,
    icon: ImageVector,
    key: String,
    onKeyChange: (String) -> Unit,
    isVisible: Boolean,
    onToggleVisible: () -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onClear: () -> Unit,
    isTesting: Boolean,
    status: String?,
    statusOk: Boolean,
    info: String,
    tagPrefix: String,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedTextField(
                value = key,
                onValueChange = onKeyChange,
                placeholder = { Text("Paste $title here") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false
                ),
                visualTransformation =
                    if (isVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = onToggleVisible) {
                        Icon(
                            imageVector =
                                if (isVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (isVisible) "Hide Key" else "Show Key",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_${tagPrefix}_key")
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onSave,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .testTag("btn_save_${tagPrefix}_key")
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Save", style = MaterialTheme.typography.labelMedium)
                }

                Button(
                    onClick = onTest,
                    enabled = !isTesting,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1.1f)
                        .height(42.dp)
                        .testTag("btn_test_${tagPrefix}_key")
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Test Key", style = MaterialTheme.typography.labelMedium)
                }

                OutlinedButton(
                    onClick = onClear,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .weight(0.9f)
                        .height(42.dp)
                        .testTag("btn_clear_${tagPrefix}_key")
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear", style = MaterialTheme.typography.labelMedium)
                }
            }

            if (!status.isNullOrBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.testTag("txt_${tagPrefix}_key_status_message")
                ) {
                    Icon(
                        imageVector = if (statusOk) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (statusOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = status!!,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = if (statusOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier
                        .size(16.dp)
                        .padding(top = 2.dp)
                )
                Text(
                    text = info,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

