package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.PhoneCallback
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.database.CallOutcomes

/**
 * Small popup shown right after the dialer opens from the app: the user taps
 * how the call went and the app stores it in the lead's call history.
 * "Skip" closes it without writing anything (only lastCall is kept in that
 * case, marked by the caller).
 */
@Composable
fun CallOutcomeDialog(
    leadName: String,
    onDismiss: () -> Unit,
    onResult: (outcome: String, note: String) -> Unit
) {
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.call_log_title, leadName),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutcomeButton(
                    label = stringResource(R.string.call_outcome_answered),
                    icon = Icons.Default.Call,
                    color = Color(0xFF43A047),
                    testTag = "call_outcome_answered",
                    onClick = { onResult(CallOutcomes.ANSWERED, note) }
                )
                OutcomeButton(
                    label = stringResource(R.string.call_outcome_no_answer),
                    icon = Icons.Default.CallEnd,
                    color = Color(0xFFE53935),
                    testTag = "call_outcome_no_answer",
                    onClick = { onResult(CallOutcomes.NO_ANSWER, note) }
                )
                OutcomeButton(
                    label = stringResource(R.string.call_outcome_callback),
                    icon = Icons.Default.PhoneCallback,
                    color = Color(0xFFFB8C00),
                    testTag = "call_outcome_callback",
                    onClick = { onResult(CallOutcomes.CALLBACK, note) }
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.call_note_ph)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2,
                    textStyle = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.call_outcome_skip))
            }
        }
    )
}

@Composable
private fun OutcomeButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    testTag: String,
    onClick: () -> Unit
) {
        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = color.copy(alpha = 0.14f),
            contentColor = color
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}


