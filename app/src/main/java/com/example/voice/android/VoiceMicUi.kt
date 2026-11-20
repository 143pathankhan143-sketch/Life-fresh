package com.example.voice.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R

/**
 * The one voice entry point for the whole app: a mic button that is always
 * there on the main tabs (work.md §12 — the friendliest entry for a user who
 * cannot read). Red + stop icon while the mic is open.
 */
@Composable
fun VoiceMicButton(
    listening: Boolean,
    sessionActive: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    FloatingActionButton(
        onClick = onTap,
        containerColor = if (listening) {
            MaterialTheme.colorScheme.error
        } else if (sessionActive) {
            MaterialTheme.colorScheme.tertiary
        } else {
            MaterialTheme.colorScheme.primary
        },
        contentColor = Color.White,
        modifier = modifier.testTag("btn_fab_voice")
    ) {
        Icon(
            imageVector = if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = stringResource(
                if (listening) R.string.cd_stop_voice else R.string.cd_voice_input
            ),
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * Visual echo of the spoken confirmation ("Delete the cloud backup forever?").
 * The audio is the primary channel; this card is there for a user looking at
 * the screen — and it disappears the moment the gate closes.
 */
@Composable
fun VoiceConfirmBanner(
    prompt: String?,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = prompt != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("voice_confirm_card")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = prompt ?: "",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}
