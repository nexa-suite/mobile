package com.nexa.mobile.operations.feature.warehouse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** A reference selects a server read; it never confirms allocation or authority. */
@Composable
fun PickingEntryScreen(
    reference: String,
    onReferenceChanged: (String) -> Unit,
    onBack: () -> Unit,
    onOpen: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.stock_condition_back)) }
            Text(
                stringResource(R.string.picking_title),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(stringResource(R.string.picking_entry_support))
            OutlinedTextField(
                value = reference,
                onValueChange = onReferenceChanged,
                label = { Text(stringResource(R.string.picking_reference)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = onOpen, enabled = reference.isNotBlank()) {
                Text(stringResource(R.string.picking_open))
            }
        }
    }
}
