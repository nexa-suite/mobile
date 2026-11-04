package com.nexa.mobile.operations.fulfillmentdelivery.presentation.warehouse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.core.designsystem.NexaColors
import com.nexa.mobile.operations.core.designsystem.NexaTopAppBar
import com.nexa.mobile.operations.core.designsystem.NexaWorkSection
import com.nexa.mobile.operations.fulfillmentdelivery.domain.model.warehouse.PickingWorkItem
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.R
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.common.OperationsGlyph
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.common.OperationsGlyphKind

@Composable
fun WarehouseBatchScreen(
    state: WarehouseBatchUiState,
    work: PickingWorkListUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onNextPage: () -> Unit,
    onPreviousPage: () -> Unit,
    onAdd: (PickingWorkItem) -> Unit,
    onMove: (String, Int) -> Unit,
    onOpen: (String) -> Unit,
    onReview: (String, String) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        NexaTopAppBar(title = stringResource(R.string.batch_visual_title), onBack = onBack)
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(
                rememberScrollState()
            ).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            NexaWorkSection(stringResource(R.string.batch_visual_group)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OperationsGlyph(OperationsGlyphKind.Picking)
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.batch_visual_count, state.items.size),
                            style = MaterialTheme.typography.titleLarge,
                            color = NexaColors.PrimaryStrong
                        )
                        Text(
                            stringResource(R.string.batch_visual_support),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NexaColors.TextSecondary
                        )
                    }
                }
            }
            NexaWorkSection(stringResource(R.string.batch_work_available)) {
                if (work.status == PickingWorkListStatus.Loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.batch_visual_loading))
                } else if (work.status != PickingWorkListStatus.Ready) {
                    Text(
                        stringResource(
                            if (work.status == PickingWorkListStatus.PermissionDenied) {
                                R.string.batch_visual_denied
                            } else {
                                R.string.batch_visual_unavailable
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NexaColors.TextSecondary
                    )
                }
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = work.status != PickingWorkListStatus.Loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = NexaColors.Surface
                    )
                ) {
                    Text(stringResource(R.string.batch_visual_refresh))
                }
                if (work.status == PickingWorkListStatus.Ready) {
                    if (work.items.isEmpty()) {
                        Text(
                            stringResource(R.string.batch_visual_no_work),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NexaColors.TextSecondary
                        )
                    }
                    work.items.forEachIndexed { index, item ->
                        val selected = state.items.any {
                            it.prepared.fulfillmentId ==
                                item.fulfillmentId
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OperationsGlyph(OperationsGlyphKind.Picking)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(
                                        R.string.batch_visual_job,
                                        work.page * work.size + index + 1
                                    ),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    pluralStringResource(
                                        R.plurals.batch_visual_lines,
                                        item.lineCount,
                                        item.lineCount
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NexaColors.TextSecondary
                                )
                            }
                            OutlinedButton(
                                onClick = { onAdd(item) },
                                enabled =
                                    !selected && state.items.size < 25
                            ) {
                                Text(
                                    stringResource(
                                        if (selected) {
                                            R.string.batch_visual_added
                                        } else {
                                            R.string.batch_visual_add
                                        }
                                    )
                                )
                            }
                        }
                        HorizontalDivider(color = NexaColors.Border)
                    }
                    if (work.totalItems > work.size) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(onClick = onPreviousPage, enabled = work.page > 0) {
                                Text(stringResource(R.string.batch_visual_previous))
                            }
                            TextButton(
                                onClick = onNextPage,
                                enabled = (work.page.toLong() + 1) * work.size < work.totalItems
                            ) {
                                Text(stringResource(R.string.batch_visual_next))
                            }
                        }
                    }
                }
            }
            if (state.items.isEmpty()) {
                NexaWorkSection(stringResource(R.string.batch_visual_empty_title)) {
                    Text(
                        stringResource(R.string.batch_visual_empty_support),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NexaColors.TextSecondary
                    )
                }
            }
            state.items.forEachIndexed { index, item ->
                val id = item.prepared.fulfillmentId
                NexaWorkSection(stringResource(R.string.batch_work_position, index + 1)) {
                    Text(
                        pluralStringResource(
                            R.plurals.batch_visual_lines,
                            item.prepared.lineCount,
                            item.prepared.lineCount
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NexaColors.TextSecondary
                    )
                    item.confirmed?.let { result ->
                        Text(
                            stringResource(R.string.batch_visual_result),
                            color = NexaColors.Success,
                            style = MaterialTheme.typography.labelLarge
                        )
                        result.lines.forEach { line ->
                            Text(
                                stringResource(
                                    R.string.batch_visual_quantities,
                                    line.pickedQuantity.toPlainString(),
                                    line.remainingQuantity.toPlainString(),
                                    line.unit
                                ),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    item.reviewNote?.let { note ->
                        Text(
                            note,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NexaColors.Warning
                        )
                    }
                    Row {
                        TextButton(onClick = { onMove(id, -1) }, enabled = index > 0) {
                            Text(stringResource(R.string.batch_visual_up))
                        }
                        TextButton(
                            onClick = { onMove(id, 1) },
                            enabled =
                                index < state.items.lastIndex
                        ) {
                            Text(stringResource(R.string.batch_visual_down))
                        }
                    }
                    Button(onClick = {
                        onOpen(id)
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(stringResource(R.string.batch_visual_open))
                    }
                    var noteVisible by remember(state.authorityEpoch, id) { mutableStateOf(false) }
                    var note by remember(state.authorityEpoch, id) { mutableStateOf("") }
                    TextButton(onClick = { noteVisible = !noteVisible }) {
                        Text(stringResource(R.string.batch_visual_note))
                    }
                    if (noteVisible) {
                        OutlinedTextField(
                            note,
                            { note = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.batch_visual_note_label)) }
                        )
                        TextButton(onClick = {
                            onReview(id, note)
                            note = ""
                            noteVisible = false
                        }, enabled = note.isNotBlank()) {
                            Text(stringResource(R.string.batch_visual_save_note))
                        }
                    }
                }
            }
        }
    }
}
