package com.example.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.VolumeUp
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.ai.chat.voice.AiVoicePlayer
import com.example.ai.chat.voice.GeminiTtsClient
import com.example.data.repository.AIServiceRepository
import com.example.data.security.AIQuotaManager
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.example.R

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
    val mapKeyStatus: (String) -> String = { raw ->
        when {
            raw == "Connected successfully (key valid)" -> context.getString(R.string.settings_key_connected_valid)
            raw == "Connected successfully (web search ready)" -> context.getString(R.string.settings_key_connected_web)
            raw.startsWith("Connected successfully (") -> context.getString(R.string.settings_key_connected_prefix) + raw.removePrefix("Connected successfully")
            else -> raw
        }
    }
    val coroutineScope = rememberCoroutineScope()

    // Quota badge
    var isUnlimited by remember { mutableStateOf(AIQuotaManager.isUnlimited(context)) }
    var remainingQuota by remember { mutableStateOf(AIQuotaManager.getRemainingQuota(context)) }

    // Agent Mode
    var agentMode by remember { mutableStateOf(AIQuotaManager.isAgentModeEnabled(context)) }
    var showAgentModeConfirm by remember { mutableStateOf(false) }

    // AI Voice - natural cloud voice (Gemini free tier) + voice picker
    var naturalTts by remember { mutableStateOf(AIQuotaManager.isNaturalTtsEnabled(context)) }
    var ttsVoice by remember { mutableStateOf(AIQuotaManager.getTtsVoiceName(context)) }
    var showVoicePicker by remember { mutableStateOf(false) }
    var isTestingVoice by remember { mutableStateOf(false) }

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
                text = stringResource(R.string.settings_api_keys),
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
                        text = stringResource(R.string.settings_daily_quota),
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
                    title = stringResource(R.string.settings_agent_mode),
                    subtitle = stringResource(R.string.settings_agent_mode_sub),
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

        // AI Voice - spoken replies in a natural voice, no cost
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
                    icon = Icons.Default.VolumeUp,
                    title = stringResource(R.string.settings_ai_voice),
                    subtitle = stringResource(R.string.settings_ai_voice_sub),
                    checked = naturalTts,
                    onCheckedChange = { newValue ->
                        naturalTts = newValue
                        AIQuotaManager.setNaturalTtsEnabled(context, newValue)
                    },
                    testTag = "ai_natural_voice_switch"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { showVoicePicker = true }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_ai_voice_pick) + ": " +
                            if (ttsVoice == "auto") stringResource(R.string.ai_voice_auto) else ttsVoice,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { showVoicePicker = true },
                        modifier = Modifier.testTag("btn_ai_voice_pick")
                    ) {
                        Text(stringResource(R.string.settings_ai_voice_pick))
                    }
                    TextButton(
                        onClick = {
                            if (!isTestingVoice) {
                                isTestingVoice = true
                                coroutineScope.launch {
                                    AiVoicePlayer.testSpeak(
                                        context,
                                        context.getString(R.string.ai_tts_test_sample)
                                    )
                                    isTestingVoice = false
                                }
                            }
                        },
                        modifier = Modifier.testTag("btn_ai_voice_test")
                    ) {
                        Text(
                            if (isTestingVoice) "…"
                            else stringResource(R.string.settings_ai_voice_test)
                        )
                    }
                }
            }
        }

        // Gemini key
        ApiKeySection(
            title = stringResource(R.string.settings_gemini_key_title),
            subtitle = stringResource(R.string.settings_gemini_key_sub),
            icon = Icons.Default.AutoAwesome,
            key = geminiKey,
            onKeyChange = { geminiKey = it; geminiStatus = null },
            isVisible = geminiVisible,
            onToggleVisible = { geminiVisible = !geminiVisible },
            onSave = {
                AIQuotaManager.saveCustomGeminiKey(context, geminiKey.trim())
                refreshQuota()
                geminiStatus = if (geminiKey.isBlank()) context.getString(R.string.settings_key_cleared, "Gemini") else context.getString(R.string.settings_key_saved, "Gemini")
                geminiStatusOk = true
            },
            onTest = {
                val trimmed = geminiKey.trim()
                if (trimmed.isBlank()) {
                    geminiStatus = context.getString(R.string.settings_key_enter_to_test)
                    geminiStatusOk = false
                } else {
                    isTestingGemini = true
                    geminiStatus = context.getString(R.string.settings_key_testing)
                    geminiStatusOk = true
                    coroutineScope.launch {
                        val result = AIServiceRepository().testGeminiKey(trimmed)
                        isTestingGemini = false
                        result.fold(
                            onSuccess = { geminiStatus = context.getString(R.string.settings_key_valid); geminiStatusOk = true },
                            onFailure = { e ->
                                geminiStatus = context.getString(R.string.settings_key_test_failed, e.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.common_unknown_error))
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
                geminiStatus = context.getString(R.string.settings_key_cleared, "Gemini")
                geminiStatusOk = true
            },
            isTesting = isTestingGemini,
            status = geminiStatus,
            statusOk = geminiStatusOk,
            info = stringResource(R.string.settings_gemini_key_info),
            tagPrefix = "gemini"
        )

        // Groq key
        ApiKeySection(
            title = stringResource(R.string.settings_groq_key_title),
            subtitle = stringResource(R.string.settings_groq_key_sub),
            icon = Icons.Default.Speed,
            key = groqKey,
            onKeyChange = { groqKey = it; groqStatus = null },
            isVisible = groqVisible,
            onToggleVisible = { groqVisible = !groqVisible },
            onSave = {
                AIQuotaManager.saveCustomGroqKey(context, groqKey.trim())
                refreshQuota()
                groqStatus = if (groqKey.isBlank()) context.getString(R.string.settings_key_cleared, "Groq") else context.getString(R.string.settings_key_saved, "Groq")
                groqStatusOk = true
            },
            onTest = {
                val trimmed = groqKey.trim()
                if (trimmed.isBlank()) {
                    groqStatus = context.getString(R.string.settings_key_enter_to_test)
                    groqStatusOk = false
                } else {
                    isTestingGroq = true
                    groqStatus = context.getString(R.string.settings_key_testing)
                    groqStatusOk = true
                    coroutineScope.launch {
                        val result = AIServiceRepository().testGroqKey(trimmed)
                        isTestingGroq = false
                        result.fold(
                            onSuccess = { groqStatus = context.getString(R.string.settings_key_valid); groqStatusOk = true },
                            onFailure = { e ->
                                groqStatus = context.getString(R.string.settings_key_test_failed, e.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.common_unknown_error))
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
                groqStatus = context.getString(R.string.settings_key_cleared, "Groq")
                groqStatusOk = true
            },
            isTesting = isTestingGroq,
            status = groqStatus,
            statusOk = groqStatusOk,
            info = stringResource(R.string.settings_groq_key_info),
            tagPrefix = "groq"
        )

        // OpenRouter key
        ApiKeySection(
            title = stringResource(R.string.settings_openrouter_key_title),
            subtitle = stringResource(R.string.settings_openrouter_key_sub),
            icon = Icons.Default.Cloud,
            key = openRouterKey,
            onKeyChange = { openRouterKey = it; openRouterStatus = null },
            isVisible = openRouterVisible,
            onToggleVisible = { openRouterVisible = !openRouterVisible },
            onSave = {
                AIQuotaManager.saveCustomOpenRouterKey(context, openRouterKey.trim())
                refreshQuota()
                openRouterStatus = if (openRouterKey.isBlank()) context.getString(R.string.settings_key_cleared, "OpenRouter") else context.getString(R.string.settings_key_saved, "OpenRouter")
                openRouterStatusOk = true
            },
            onTest = {
                val trimmed = openRouterKey.trim()
                if (trimmed.isBlank()) {
                    openRouterStatus = context.getString(R.string.settings_key_enter_to_test)
                    openRouterStatusOk = false
                } else {
                    isTestingOpenRouter = true
                    openRouterStatus = context.getString(R.string.settings_key_testing)
                    openRouterStatusOk = true
                    coroutineScope.launch {
                        val result = AIServiceRepository().testOpenRouterKey(trimmed)
                        isTestingOpenRouter = false
                        result.fold(
                            onSuccess = { openRouterStatus = mapKeyStatus(it); openRouterStatusOk = true },
                            onFailure = { e ->
                                openRouterStatus = context.getString(R.string.settings_key_test_failed, e.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.common_unknown_error))
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
                openRouterStatus = context.getString(R.string.settings_key_cleared, "OpenRouter")
                openRouterStatusOk = true
            },
            isTesting = isTestingOpenRouter,
            status = openRouterStatus,
            statusOk = openRouterStatusOk,
            info = stringResource(R.string.settings_openrouter_key_info),
            tagPrefix = "openrouter"
        )

        // Tavily key
        ApiKeySection(
            title = stringResource(R.string.settings_tavily_key_title),
            subtitle = stringResource(R.string.settings_tavily_key_sub),
            icon = Icons.Default.Search,
            key = tavilyKey,
            onKeyChange = { tavilyKey = it; tavilyStatus = null },
            isVisible = tavilyVisible,
            onToggleVisible = { tavilyVisible = !tavilyVisible },
            onSave = {
                AIQuotaManager.saveCustomTavilyKey(context, tavilyKey.trim())
                tavilyStatus = if (tavilyKey.isBlank()) context.getString(R.string.settings_key_cleared, "Tavily") else context.getString(R.string.settings_key_saved, "Tavily")
                tavilyStatusOk = true
            },
            onTest = {
                val trimmed = tavilyKey.trim()
                if (trimmed.isBlank()) {
                    tavilyStatus = context.getString(R.string.settings_key_enter_to_test)
                    tavilyStatusOk = false
                } else {
                    isTestingTavily = true
                    tavilyStatus = context.getString(R.string.settings_key_testing)
                    tavilyStatusOk = true
                    coroutineScope.launch {
                        val result = AIServiceRepository().testTavilyKey(trimmed)
                        isTestingTavily = false
                        result.fold(
                            onSuccess = { tavilyStatus = mapKeyStatus(it); tavilyStatusOk = true },
                            onFailure = { e ->
                                tavilyStatus = context.getString(R.string.settings_key_test_failed, e.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.common_unknown_error))
                                tavilyStatusOk = false
                            }
                        )
                    }
                }
            },
            onClear = {
                tavilyKey = ""
                AIQuotaManager.saveCustomTavilyKey(context, null)
                tavilyStatus = context.getString(R.string.settings_key_cleared, "Tavily")
                tavilyStatusOk = true
            },
            isTesting = isTestingTavily,
            status = tavilyStatus,
            statusOk = tavilyStatusOk,
            info = stringResource(R.string.settings_tavily_key_info),
            tagPrefix = "tavily"
        )

        Spacer(modifier = Modifier.height(16.dp))
    }

    // AI Voice picker (Gemini natural voices)
    if (showVoicePicker) {
        AlertDialog(
            onDismissRequest = { showVoicePicker = false },
            title = { Text(stringResource(R.string.settings_ai_voice_pick)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    GeminiTtsClient.VOICES.forEach { v ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    ttsVoice = v
                                    AIQuotaManager.setTtsVoiceName(context, v)
                                    showVoicePicker = false
                                }
                                .padding(vertical = 9.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = v == ttsVoice, onClick = null)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = if (v == "auto") stringResource(R.string.ai_voice_auto) else v,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showVoicePicker = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    // Agent Mode confirm popup (only when turning ON)
    if (showAgentModeConfirm) {
        AlertDialog(
            onDismissRequest = { showAgentModeConfirm = false },
            title = { Text(stringResource(R.string.settings_agent_mode_on)) },
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
                    Text(stringResource(R.string.common_turn_on))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAgentModeConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
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
                placeholder = { Text(stringResource(R.string.settings_paste_key, title)) },
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
                            contentDescription = if (isVisible) stringResource(R.string.cd_hide_key) else stringResource(R.string.cd_show_key),
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
                    Text(stringResource(R.string.common_save), style = MaterialTheme.typography.labelMedium)
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
                    Text(stringResource(R.string.common_test_key), style = MaterialTheme.typography.labelMedium)
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
                    Text(stringResource(R.string.common_clear), style = MaterialTheme.typography.labelMedium)
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

