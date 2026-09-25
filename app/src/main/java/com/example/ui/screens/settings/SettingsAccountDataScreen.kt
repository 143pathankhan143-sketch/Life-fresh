package com.example.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.example.R

@Composable
fun SettingsAccountDataScreen(
    isGuest: Boolean,
    displayNameStr: String,
    emailStr: String,
    onBack: () -> Unit,
    onRequestAccountDeletion: () -> Unit,
    onGuestNotice: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.testTag("btn_back_to_settings")
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = stringResource(R.string.cd_back_settings)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.settings_account_data),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Account Summary Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isGuest) Icons.Default.PersonOutline else Icons.Default.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = displayNameStr,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isGuest) stringResource(R.string.guest_session_local) else emailStr,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 2. Account Deletion Policy Guide Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = stringResource(R.string.settings_deletion_policy),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                AccountPolicyItem(
                    icon = Icons.Default.HourglassTop,
                    title = stringResource(R.string.settings_grace_title),
                    description = stringResource(R.string.settings_grace_desc)
                )

                AccountPolicyItem(
                    icon = Icons.Default.LockReset,
                    title = stringResource(R.string.settings_reactivation_title),
                    description = stringResource(R.string.settings_reactivation_desc)
                )

                AccountPolicyItem(
                    icon = Icons.Default.DeleteSweep,
                    title = stringResource(R.string.settings_permanent_delete_title),
                    description = stringResource(R.string.settings_permanent_delete_desc)
                )

                AccountPolicyItem(
                    icon = Icons.Default.PersonAddAlt,
                    title = stringResource(R.string.settings_fresh_signin_title),
                    description = stringResource(R.string.settings_fresh_signin_desc)
                )
            }
        }

        // 3. Danger Zone Section
        val isDarkDanger = isSystemInDarkTheme()
        val dangerCardBg = if (isDarkDanger) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.12f)
        } else {
            Color(0xFFFEF2F2)
        }
        val dangerCardBorderColor = if (isDarkDanger) {
            MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
        } else {
            Color(0xFFFECACA)
        }
        val dangerPrimaryColor = if (isDarkDanger) {
            MaterialTheme.colorScheme.error
        } else {
            Color(0xFFDC2626)
        }
        val dangerTextColor = if (isDarkDanger) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            Color(0xFF4A1515)
        }
        val dangerButtonBg = if (isDarkDanger) {
            MaterialTheme.colorScheme.error
        } else {
            Color(0xFFDC2626)
        }

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = dangerCardBg
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    BorderStroke(1.dp, dangerCardBorderColor),
                    RoundedCornerShape(20.dp)
                )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = dangerPrimaryColor,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = stringResource(R.string.settings_danger_zone),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = dangerPrimaryColor
                    )
                }

                Text(
                    text = stringResource(R.string.settings_deletion_policy_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = dangerTextColor
                )

                Button(
                    onClick = {
                        if (isGuest) {
                            onGuestNotice()
                        } else {
                            onRequestAccountDeletion()
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = dangerButtonBg,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .testTag("btn_request_account_deletion")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteForever,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_request_deletion),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}
