package com.nexa.mobile.operations.salescommitment.presentation.commercial

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.nexa.mobile.operations.salescommitment.application.commercial.SalesExportDestination
import com.nexa.mobile.operations.salescommitment.application.commercial.SalesOrderExportAuthority
import com.nexa.mobile.operations.salescommitment.application.commercial.SalesOrderExportFormat
import com.nexa.mobile.operations.salescommitment.presentation.R

@Composable
fun SalesOrderExportActions(
    orderId: String,
    authority: SalesOrderExportAuthority,
    viewModel: SalesOrderExportViewModel
) {
    val state by viewModel.state.collectAsState()
    val currentState = state.takeIf { it.orderId == orderId } ?: SalesOrderExportState()
    fun save(uri: Uri?, format: SalesOrderExportFormat) {
        if (uri == null || currentState.status == SalesOrderExportStatus.Loading) return
        viewModel.save(authority, orderId, format, SalesExportDestination(uri.toString()))
    }
    val pdf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(SalesOrderExportFormat.PDF.mediaType)
    ) { uri -> save(uri, SalesOrderExportFormat.PDF) }
    val csv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(SalesOrderExportFormat.CSV.mediaType)
    ) { uri -> save(uri, SalesOrderExportFormat.CSV) }

    Row {
        TextButton(
            onClick = { pdf.launch("pedido.pdf") },
            enabled = authority.canReadOrderExports &&
                currentState.status != SalesOrderExportStatus.Loading
        ) {
            Text(stringResource(R.string.sales_export_pdf))
        }
        TextButton(
            onClick = { csv.launch("pedido.csv") },
            enabled = authority.canReadOrderExports &&
                currentState.status != SalesOrderExportStatus.Loading
        ) {
            Text(stringResource(R.string.sales_export_csv))
        }
    }
    when (currentState.status) {
        SalesOrderExportStatus.Loading -> Text(stringResource(R.string.sales_export_loading))

        SalesOrderExportStatus.Saved -> Text(stringResource(R.string.sales_export_saved))

        SalesOrderExportStatus.PermissionDenied -> Text(
            stringResource(R.string.sales_export_permission_denied)
        )

        SalesOrderExportStatus.ContextInvalidated,
        SalesOrderExportStatus.Unavailable -> Text(stringResource(R.string.sales_export_failed))

        SalesOrderExportStatus.Idle -> Unit
    }
}
