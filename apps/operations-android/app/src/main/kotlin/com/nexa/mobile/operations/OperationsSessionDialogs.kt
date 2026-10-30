package com.nexa.mobile.operations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.tenantaccessgovernance.application.access.OtherSessionsRevocationResult
import com.nexa.mobile.operations.tenantaccessgovernance.application.access.OwnPasswordChangeResult
import kotlinx.coroutines.launch

@Composable
internal fun OtherSessionsRevocationDialog(
    onDismiss: () -> Unit,
    onRevoke: suspend () -> OtherSessionsRevocationResult
) {
    val scope = rememberCoroutineScope()
    val revokedMessage = stringResource(R.string.work_nav_sessions_revoked)
    val expiredMessage = stringResource(R.string.work_nav_password_session_expired)
    val unavailableMessage = stringResource(R.string.work_nav_sessions_unavailable)
    var submitting by remember { mutableStateOf(false) }
    var revoked by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(R.string.work_nav_revoke_other_sessions)) },
        text = {
            Column {
                Text(
                    if (revoked) {
                        revokedMessage
                    } else {
                        stringResource(R.string.work_nav_sessions_confirmation)
                    }
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting,
                onClick = {
                    if (revoked) {
                        onDismiss()
                    } else {
                        submitting = true
                        error = null
                        scope.launch {
                            when (onRevoke()) {
                                OtherSessionsRevocationResult.Revoked -> revoked = true

                                OtherSessionsRevocationResult.SessionExpired ->
                                    error =
                                        expiredMessage

                                OtherSessionsRevocationResult.Unavailable ->
                                    error =
                                        unavailableMessage
                            }
                            submitting = false
                        }
                    }
                }
            ) {
                Text(
                    if (revoked) {
                        stringResource(R.string.work_nav_done)
                    } else if (submitting) {
                        stringResource(R.string.work_nav_saving)
                    } else {
                        stringResource(R.string.work_nav_revoke)
                    }
                )
            }
        },
        dismissButton = {
            if (!revoked) {
                TextButton(enabled = !submitting, onClick = onDismiss) {
                    Text(stringResource(R.string.work_nav_cancel))
                }
            }
        }
    )
}

@Composable
internal fun OwnPasswordChangeDialog(
    onDismiss: () -> Unit,
    onChangePassword: suspend (String, String) -> OwnPasswordChangeResult
) {
    val scope = rememberCoroutineScope()
    val mismatchMessage = stringResource(R.string.work_nav_password_mismatch)
    val rejectedMessage = stringResource(R.string.work_nav_password_rejected)
    val expiredMessage = stringResource(R.string.work_nav_password_session_expired)
    val unavailableMessage = stringResource(R.string.work_nav_password_unavailable)
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var changed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(R.string.work_nav_change_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (changed) {
                    Text(stringResource(R.string.work_nav_password_changed))
                } else {
                    OutlinedTextField(
                        value = currentPassword,
                        onValueChange = {
                            currentPassword = it
                            error = null
                        },
                        label = { Text(stringResource(R.string.work_nav_current_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        enabled = !submitting
                    )
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = {
                            newPassword = it
                            error = null
                        },
                        label = { Text(stringResource(R.string.work_nav_new_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        enabled = !submitting
                    )
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = {
                            confirmation = it
                            error = null
                        },
                        label = { Text(stringResource(R.string.work_nav_confirm_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        enabled = !submitting
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting && (
                    changed || (
                        currentPassword.isNotEmpty() &&
                            newPassword.isNotEmpty() && confirmation.isNotEmpty()
                        )
                    ),
                onClick = {
                    if (changed) {
                        onDismiss()
                    } else if (newPassword != confirmation) {
                        error = mismatchMessage
                    } else {
                        submitting = true
                        error = null
                        scope.launch {
                            when (onChangePassword(currentPassword, newPassword)) {
                                OwnPasswordChangeResult.Changed -> {
                                    currentPassword = ""
                                    newPassword = ""
                                    confirmation = ""
                                    changed = true
                                }

                                OwnPasswordChangeResult.Rejected -> error = rejectedMessage

                                OwnPasswordChangeResult.SessionExpired -> error = expiredMessage

                                OwnPasswordChangeResult.Unavailable -> error = unavailableMessage
                            }
                            submitting = false
                        }
                    }
                }
            ) {
                Text(
                    if (changed) {
                        stringResource(R.string.work_nav_done)
                    } else if (submitting) {
                        stringResource(R.string.work_nav_saving)
                    } else {
                        stringResource(R.string.work_nav_save)
                    }
                )
            }
        },
        dismissButton = {
            if (!changed) {
                TextButton(enabled = !submitting, onClick = onDismiss) {
                    Text(stringResource(R.string.work_nav_cancel))
                }
            }
        }
    )
}
