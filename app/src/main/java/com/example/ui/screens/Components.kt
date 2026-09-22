package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.data.database.LeadEntity
import com.example.ui.viewmodel.CRMViewModel
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import androidx.compose.ui.res.stringResource
import com.example.R

@Composable
fun PolicyDialog(
    type: String, // "privacy", "terms", "support"
    onDismiss: () -> Unit
) {
    val title = when (type) {
        "privacy" -> "Privacy Policy"
        "terms" -> "Terms of Use"
        else -> "Contact Support"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = when (type) {
                        "privacy" -> Icons.Default.Shield
                        "terms" -> Icons.Default.Description
                        else -> Icons.Default.SupportAgent
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(text = title, style = MaterialTheme.typography.titleLarge)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (type) {
                    "privacy" -> {
                        Text(
                            text = stringResource(R.string.legal_last_updated, "September 2026"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(text = stringResource(R.string.legal_welcome))
                        Text(text = stringResource(R.string.legal_intro))

                        Text(text = stringResource(R.string.legal_health_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(text = stringResource(R.string.legal_health_body))

                        Text(text = stringResource(R.string.legal_coppa_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(text = stringResource(R.string.legal_coppa_body))

                        Text(text = stringResource(R.string.legal_data_retention_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(text = stringResource(R.string.legal_data_retention_body))

                        Text(text = stringResource(R.string.legal_data_privacy_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(text = stringResource(R.string.legal_data_privacy_body))

                        Text(text = stringResource(R.string.legal_auth_sync_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(text = stringResource(R.string.legal_auth_sync_body))

                        Text(text = stringResource(R.string.legal_ai_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(text = stringResource(R.string.legal_ai_body))

                        Text(text = stringResource(R.string.legal_security_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(text = stringResource(R.string.legal_security_body))
                    }
                    "terms" -> {
                        Text(
                            text = stringResource(R.string.legal_last_updated, "June 2026"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(text = stringResource(R.string.legal_terms_agree))
                        Text(text = stringResource(R.string.legal_terms_body))
                    }
                    "support" -> {
                        Text(text = stringResource(R.string.legal_need_help), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(text = stringResource(R.string.legal_contact_us))
                        
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "lifefresh101@gmail.com",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("policy_dialog_close")
            ) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
}

@Composable
fun AlarmRingingDialog(
    lead: LeadEntity,
    title: String,
    viewModel: CRMViewModel
) {
    val parsedDiseases = try {
        val array = JSONArray(lead.diseases)
        val list = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val d = array.getString(i)
            list.add(if (d == "Other" && lead.otherDisease.isNotEmpty()) lead.otherDisease else d)
        }
        list.joinToString(", ")
    } catch (e: Exception) {
        "None"
    }

    // Modern Spring Entrance Animation
    val scale = remember { androidx.compose.animation.core.Animatable(0.92f) }
    val alpha = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        launch {
            scale.animateTo(
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                )
            )
        }
        launch {
            alpha.animateTo(
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.tween(durationMillis = 200)
            )
        }
    }

    Dialog(
        onDismissRequest = { /* Prevent back dismiss to force deliberate dismiss or snooze actions */ },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .graphicsLayer(
                    scaleX = scale.value,
                    scaleY = scale.value,
                    alpha = alpha.value
                )
                .testTag("alarm_ringing_dialog"),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Premium Title Block
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f), androidx.compose.foundation.shape.CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column {
                        Text(
                            text = title.uppercase(java.util.Locale.US),
                            style = MaterialTheme.typography.labelSmall.copy(
                                letterSpacing = 1.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = stringResource(R.string.common_wellness_alert),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Hero Client Identity & Name (Very large and prominent)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Initials Avatar Circle
                    val initials = remember(lead.name) {
                        lead.name.split(" ")
                            .filter { it.isNotEmpty() }
                            .take(2)
                            .joinToString("") { it.take(1) }
                            .uppercase(java.util.Locale.US)
                    }
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, androidx.compose.foundation.shape.CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = initials,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = lead.name,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 22.sp,
                                letterSpacing = (-0.5).sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.common_wellness_client),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Grid Details
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    DetailRow(
                        label = stringResource(R.string.common_mobile),
                        value = lead.mobile
                    )
                    DetailRow(
                        label = stringResource(R.string.common_issues),
                        value = parsedDiseases
                    )
                    DetailRow(
                        label = stringResource(R.string.common_scheduled_date),
                        value = if (lead.reminderDate.isEmpty()) "Today" else formatTime12Hour(lead.reminderDate).ifEmpty { lead.reminderDate }
                    )
                    DetailRow(
                        label = stringResource(R.string.common_scheduled_time),
                        value = if (lead.reminderTime.isEmpty()) "Not Specified" else formatTime12Hour(lead.reminderTime)
                    )
                }

                // Note block
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notes,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.profile_reminder_note),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(14.dp)
                    ) {
                        Text(
                            text = lead.reminderNote.ifEmpty { "Follow-up Session" },
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                            fontStyle = FontStyle.Normal,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Snooze options section
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Snooze,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.common_snooze_reminder),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val snoozeOptions = listOf(5, 10, 30)
                        snoozeOptions.forEach { mins ->
                            OutlinedButton(
                                onClick = { viewModel.snoozeActiveAlarm(mins) },
                                shape = RoundedCornerShape(24.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("snooze_${mins}_button")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Default.AccessTime, contentDescription = null, modifier = Modifier.size(12.dp))
                                    Text("$mins Min", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                }
                            }
                        }
                    }
                }

                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Dismiss and Complete actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { viewModel.dismissActiveAlarm() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("alarm_dismiss_button")
                    ) {
                        Text(stringResource(R.string.common_dismiss), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                    }

                    Button(
                        onClick = { viewModel.markActiveAlarmComplete() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF10B981) // Web's custom success color
                        ),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("alarm_complete_button")
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
                            Text(stringResource(R.string.common_complete), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(0.6f)
        )
    }
}

private fun formatTime12Hour(timeStr: String): String {
    if (timeStr.isEmpty()) return ""
    return try {
        val parser = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        val date = parser.parse(timeStr) ?: return timeStr
        val formatter = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.US)
        formatter.format(date).uppercase(java.util.Locale.US)
    } catch (e: Exception) {
        timeStr
    }
}

/**
 * Reusable Voice Note Recorder Dialog with runtime RECORD_AUDIO permission checking,
 * informative denial rationale Toast, and strict MediaRecorder lifecycle management
 * (stopping, resetting, and releasing in try-catch and DisposableEffect) to prevent memory leaks.
 */
@Composable
fun VoiceNoteRecorderDialog(
    onDismiss: () -> Unit,
    onRecordingComplete: (File) -> Unit
) {
    val context = LocalContext.current
    var isRecording by remember { mutableStateOf(false) }
    var currentRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var currentOutputFile by remember { mutableStateOf<File?>(null) }
    var recordingDurationSeconds by remember { mutableStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(
                context,
                "Microphone permission is required to record voice notes.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                if (isRecording) {
                    currentRecorder?.stop()
                }
                currentRecorder?.reset()
                currentRecorder?.release()
            } catch (e: Exception) {
                android.util.Log.w("VoiceNoteRecorder", "Error releasing MediaRecorder on dispose", e)
            } finally {
                currentRecorder = null
                isRecording = false
            }
        }
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingDurationSeconds = 0
            while (isRecording) {
                kotlinx.coroutines.delay(1000)
                recordingDurationSeconds++
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (isRecording) {
                try {
                    currentRecorder?.stop()
                } catch (e: Exception) {
                    android.util.Log.w("VoiceNoteRecorder", "Error stopping MediaRecorder", e)
                }
                try {
                    currentRecorder?.reset()
                    currentRecorder?.release()
                } catch (e: Exception) {
                    android.util.Log.w("VoiceNoteRecorder", "Error releasing MediaRecorder", e)
                }
                currentRecorder = null
                isRecording = false
            }
            onDismiss()
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (isRecording) "Recording Voice Note..." else "Voice Note Recorder",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isRecording) {
                    Text(
                        text = stringResource(R.string.common_recording, "${recordingDurationSeconds / 60}:${String.format(java.util.Locale.US, "%02d", recordingDurationSeconds % 60)}"),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = stringResource(R.string.common_speak_clearly),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = stringResource(R.string.common_voice_note_sub),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        confirmButton = {
            if (isRecording) {
                Button(
                    onClick = {
                        try {
                            currentRecorder?.stop()
                        } catch (e: Exception) {
                            android.util.Log.w("VoiceNoteRecorder", "Error stopping MediaRecorder", e)
                        }
                        try {
                            currentRecorder?.reset()
                            currentRecorder?.release()
                        } catch (e: Exception) {
                            android.util.Log.w("VoiceNoteRecorder", "Error releasing MediaRecorder", e)
                        }
                        currentRecorder = null
                        isRecording = false

                        val file = currentOutputFile
                        if (file != null && file.exists() && file.length() > 0) {
                            onRecordingComplete(file)
                        }
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.testTag("btn_stop_recording")
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.common_stop_save))
                }
            } else {
                Button(
                    onClick = {
                        val hasPermission = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED

                        if (!hasPermission) {
                            Toast.makeText(
                                context,
                                "Microphone permission is required to record voice notes.",
                                Toast.LENGTH_LONG
                            ).show()
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@Button
                        }

                        try {
                            val audioDir = File(context.cacheDir, "voice_notes").apply { mkdirs() }
                            val audioFile = File(audioDir, "audio_note_${System.currentTimeMillis()}.m4a")
                            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                MediaRecorder(context)
                            } else {
                                @Suppress("DEPRECATION")
                                MediaRecorder()
                            }
                            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                            recorder.setAudioEncodingBitRate(128000)
                            recorder.setAudioSamplingRate(44100)
                            recorder.setOutputFile(audioFile.absolutePath)
                            recorder.prepare()
                            recorder.start()

                            currentRecorder = recorder
                            currentOutputFile = audioFile
                            isRecording = true
                        } catch (e: Exception) {
                            android.util.Log.e("VoiceNoteRecorder", "Failed to start MediaRecorder", e)
                            Toast.makeText(
                                context,
                                "Failed to initialize recorder: ${e.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                            try {
                                currentRecorder?.reset()
                                currentRecorder?.release()
                            } catch (ignored: Exception) {}
                            currentRecorder = null
                            isRecording = false
                        }
                    },
                    modifier = Modifier.testTag("btn_start_recording")
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.common_start_recording))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (isRecording) {
                        try {
                            currentRecorder?.stop()
                        } catch (ignored: Exception) {}
                        try {
                            currentRecorder?.reset()
                            currentRecorder?.release()
                        } catch (ignored: Exception) {}
                        currentRecorder = null
                        isRecording = false
                    }
                    onDismiss()
                },
                modifier = Modifier.testTag("btn_cancel_recording")
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

