package com.nexa.mobile.operations.salescommitment.presentation.commercial

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.mobile.operations.salescommitment.application.commercial.SalesExportDestination
import com.nexa.mobile.operations.salescommitment.application.commercial.SalesOrderExportAuthority
import com.nexa.mobile.operations.salescommitment.application.commercial.SalesOrderExportFormat
import com.nexa.mobile.operations.salescommitment.application.commercial.SalesOrderExportGateway
import com.nexa.mobile.operations.salescommitment.application.commercial.SalesOrderExportSaveResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SalesOrderExportStatus {
    Idle,
    Loading,
    Saved,
    PermissionDenied,
    ContextInvalidated,
    Unavailable
}

data class SalesOrderExportState(
    val orderId: String? = null,
    val format: SalesOrderExportFormat? = null,
    val status: SalesOrderExportStatus = SalesOrderExportStatus.Idle
)

class SalesOrderExportViewModel(private val gateway: SalesOrderExportGateway) : ViewModel() {
    private val mutableState = MutableStateFlow(SalesOrderExportState())
    val state = mutableState.asStateFlow()
    private var generation = 0L

    fun save(
        authority: SalesOrderExportAuthority,
        orderId: String,
        format: SalesOrderExportFormat,
        destination: SalesExportDestination
    ) {
        val request = ++generation
        if (!authority.canReadOrderExports) {
            mutableState.value = SalesOrderExportState(
                orderId = orderId,
                format = format,
                status = SalesOrderExportStatus.PermissionDenied
            )
            return
        }
        mutableState.value = SalesOrderExportState(
            orderId = orderId,
            format = format,
            status = SalesOrderExportStatus.Loading
        )
        viewModelScope.launch {
            val result = try {
                gateway.save(authority, orderId, format, destination)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SalesOrderExportSaveResult.Unavailable
            }
            if (request != generation) return@launch
            mutableState.value = SalesOrderExportState(
                orderId = orderId,
                format = format,
                status = when (result) {
                    SalesOrderExportSaveResult.Saved -> SalesOrderExportStatus.Saved

                    SalesOrderExportSaveResult.PermissionDenied ->
                        SalesOrderExportStatus.PermissionDenied

                    SalesOrderExportSaveResult.ContextInvalidated ->
                        SalesOrderExportStatus.ContextInvalidated

                    SalesOrderExportSaveResult.Unavailable -> SalesOrderExportStatus.Unavailable
                }
            )
        }
    }

    fun clear() {
        generation++
        mutableState.value = SalesOrderExportState()
    }
}
