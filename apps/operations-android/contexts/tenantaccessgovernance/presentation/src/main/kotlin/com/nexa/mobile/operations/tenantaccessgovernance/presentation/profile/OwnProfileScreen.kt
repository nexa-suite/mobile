package com.nexa.mobile.operations.tenantaccessgovernance.presentation.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.profile.OwnProfileEdit
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.R

@Composable
fun OwnProfileScreen(
    state: OwnProfileState,
    onSave: (OwnProfileEdit) -> Unit,
    onReload: () -> Unit
) {
    val profile = state.profile
    var name by remember(profile) { mutableStateOf(profile?.displayName.orEmpty()) }
    var first by remember(profile) { mutableStateOf(profile?.firstName.orEmpty()) }
    var last by remember(profile) { mutableStateOf(profile?.lastName.orEmpty()) }
    var phone by remember(profile) { mutableStateOf(profile?.phone.orEmpty()) }
    var language by remember(profile) { mutableStateOf(profile?.preferredLanguage ?: "es") }
    val editable = profile != null && !state.busy && !state.mustReload
    val phoneValid = phone.isBlank() || phone.matches(Regex("\\+[1-9][0-9]{6,14}"))
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.notice?.let { notice ->
            Text(
                stringResource(
                    when (notice) {
                        OwnProfileNotice.Saved -> R.string.own_profile_saved
                        OwnProfileNotice.Invalid -> R.string.own_profile_invalid
                        OwnProfileNotice.Denied -> R.string.own_profile_denied
                        OwnProfileNotice.Conflict -> R.string.own_profile_conflict
                        OwnProfileNotice.Unavailable -> R.string.own_profile_unavailable
                    }
                ),
                color = if (notice ==
                    OwnProfileNotice.Saved
                ) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }
        if (profile == null || state.mustReload) {
            OutlinedButton(
                onClick = onReload,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.own_profile_reload))
            }
        }
        if (profile != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(profile.email, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.own_profile_managed),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            OutlinedTextField(
                name,
                { if (it.length <= 160) name = it },
                enabled = editable,
                label = {
                    Text(stringResource(R.string.own_profile_name))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                first,
                { if (it.length <= 100) first = it },
                enabled = editable,
                label = {
                    Text(stringResource(R.string.own_profile_first))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                last,
                { if (it.length <= 100) last = it },
                enabled = editable,
                label = {
                    Text(stringResource(R.string.own_profile_last))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                phone, { if (it.length <= 16) phone = it }, enabled = editable,
                label = { Text(stringResource(R.string.own_profile_phone)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone
                ),
                isError = !phoneValid,
                supportingText = {
                    Text(stringResource(R.string.own_profile_phone_hint))
                }, modifier = Modifier.fillMaxWidth()
            )
            Text(
                stringResource(R.string.own_profile_language),
                style = MaterialTheme.typography.labelLarge
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = language == "es",
                    onClick = {
                        language = "es"
                    },
                    enabled = editable,
                    label = { Text("Español") },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
                FilterChip(
                    selected = language == "en",
                    onClick = {
                        language = "en"
                    },
                    enabled = editable,
                    label = { Text("English") },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
            Button(
                onClick = { onSave(OwnProfileEdit(name, first, last, phone, language)) },
                enabled = editable && name.isNotBlank() && phoneValid,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.own_profile_save))
            }
        }
    }
}
