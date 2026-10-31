package com.nexa.mobile.operations

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.core.designsystem.NexaColors
import com.nexa.mobile.operations.core.designsystem.NexaShapes
import com.nexa.mobile.operations.core.designsystem.R as DesignR
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.profile.OwnProfile

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun OperationsProfileSheet(
    profile: OwnProfile?,
    fallbackEmail: String,
    company: String,
    role: String,
    onDismiss: () -> Unit,
    onDetails: (() -> Unit)?,
    onPassword: () -> Unit,
    onOtherSessions: () -> Unit,
    onChangeWorkspace: () -> Unit,
    onLogout: () -> Unit
) {
    var securityExpanded by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = NexaColors.Canvas
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProfileIdentityCard(
                profile = profile,
                fallbackEmail = fallbackEmail,
                company = company,
                role = role
            )

            Surface(
                shape = NexaShapes.card,
                color = NexaColors.CardSurface,
                border = null
            ) {
                Column {
                    onDetails?.let {
                        ProfileActionRow(
                            title = stringResource(R.string.profile_personal_details),
                            icon = DesignR.drawable.ic_identity,
                            onClick = it
                        )
                        HorizontalDivider(color = NexaColors.Border)
                    }
                    ProfileActionRow(
                        title = stringResource(R.string.profile_security),
                        icon = DesignR.drawable.ic_lock,
                        onClick = { securityExpanded = !securityExpanded },
                        expanded = securityExpanded
                    )
                    if (securityExpanded) {
                        Column(
                            Modifier.fillMaxWidth().padding(
                                start = 16.dp,
                                end = 16.dp,
                                bottom = 8.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            BiometricProfileSetting()
                            ProfileActionRow(
                                title = stringResource(R.string.work_nav_change_password),
                                icon = DesignR.drawable.ic_lock,
                                onClick = onPassword
                            )
                            ProfileActionRow(
                                title = stringResource(R.string.work_nav_revoke_other_sessions),
                                icon = DesignR.drawable.ic_sync,
                                onClick = onOtherSessions
                            )
                        }
                    }
                    HorizontalDivider(color = NexaColors.Border)
                    ProfileActionRow(
                        title = stringResource(R.string.work_nav_change_context),
                        icon = DesignR.drawable.ic_sync,
                        onClick = onChangeWorkspace
                    )
                }
            }

            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = NexaShapes.button,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = NexaColors.Surface,
                    contentColor = NexaColors.Danger
                ),
                border = BorderStroke(1.dp, NexaColors.DangerBorder)
            ) {
                Text(stringResource(R.string.work_nav_logout))
            }
        }
    }
}

@Composable
private fun ProfileIdentityCard(
    profile: OwnProfile?,
    fallbackEmail: String,
    company: String,
    role: String
) {
    Surface(
        shape = NexaShapes.card,
        color = NexaColors.CardSurface,
        border = null
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                Modifier.size(52.dp),
                shape = CircleShape,
                color = NexaColors.PrimaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(DesignR.drawable.ic_identity),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = NexaColors.PrimaryStrong
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    profile?.displayName?.takeIf(String::isNotBlank)
                        ?: stringResource(R.string.profile_my_profile),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = NexaColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val email = profile?.email?.takeIf(String::isNotBlank)
                    ?: fallbackEmail.takeIf { '@' in it }
                email?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = NexaColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    listOf(company, role).filter(String::isNotBlank).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = NexaColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ProfileActionRow(
    title: String,
    icon: Int,
    onClick: () -> Unit,
    expanded: Boolean = false
) {
    Surface(onClick = onClick, color = NexaColors.CardSurface, shape = NexaShapes.row) {
        Row(
            Modifier.fillMaxWidth().heightIn(
                min = 56.dp
            ).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(Modifier.size(36.dp), shape = NexaShapes.row, color = NexaColors.InfoSurface) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(icon),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = NexaColors.Info
                    )
                }
            }
            Text(
                title,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = NexaColors.TextPrimary
            )
            Icon(
                painterResource(DesignR.drawable.ic_chevron_right),
                contentDescription = null,
                modifier = Modifier.size(18.dp).graphicsLayer {
                    rotationZ = if (expanded) 90f else 0f
                },
                tint = NexaColors.TextMuted
            )
        }
    }
}
