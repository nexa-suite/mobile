package com.nexa.mobile.operations.salescommitment.presentation.commercial

import com.nexa.mobile.operations.salescommitment.application.commercial.SalesExportDestination
import com.nexa.mobile.operations.salescommitment.application.commercial.SalesOrderExportAuthority
import com.nexa.mobile.operations.salescommitment.application.commercial.SalesOrderExportFormat
import com.nexa.mobile.operations.salescommitment.application.commercial.SalesOrderExportGateway
import com.nexa.mobile.operations.salescommitment.application.commercial.SalesOrderExportSaveResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SalesOrderExportViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    @Test
    fun permissionIsCheckedBeforeStartingAnExport() = runTest {
        val gateway = FakeGateway()
        val viewModel = SalesOrderExportViewModel(gateway)

        viewModel.save(
            authority(permissions = emptySet()),
            ORDER_ID,
            SalesOrderExportFormat.PDF,
            SalesExportDestination("content://provider/document/1")
        )
        runCurrent()

        assertEquals(SalesOrderExportStatus.PermissionDenied, viewModel.state.value.status)
        assertEquals(0, gateway.calls)
    }

    @Test
    fun responseFromAnOrderThatWasDismissedCannotOverwriteCurrentState() = runTest {
        val result = CompletableDeferred<SalesOrderExportSaveResult>()
        val gateway = FakeGateway().apply { nextResult = result }
        val viewModel = SalesOrderExportViewModel(gateway)

        viewModel.save(
            authority(),
            ORDER_ID,
            SalesOrderExportFormat.PDF,
            SalesExportDestination("content://provider/document/1")
        )
        runCurrent()
        assertEquals(SalesOrderExportStatus.Loading, viewModel.state.value.status)
        viewModel.clear()
        result.complete(SalesOrderExportSaveResult.Saved)
        runCurrent()

        assertEquals(SalesOrderExportStatus.Idle, viewModel.state.value.status)
        assertTrue(viewModel.state.value.orderId == null)
    }

    @Test
    fun gatewaySuccessIsProjectedAsSaved() = runTest {
        val gateway = FakeGateway().apply { saveResult = SalesOrderExportSaveResult.Saved }
        val viewModel = SalesOrderExportViewModel(gateway)

        viewModel.save(
            authority(),
            ORDER_ID,
            SalesOrderExportFormat.CSV,
            SalesExportDestination("content://provider/document/2")
        )
        runCurrent()

        assertEquals(SalesOrderExportStatus.Saved, viewModel.state.value.status)
        assertEquals(SalesOrderExportFormat.CSV, viewModel.state.value.format)
        assertEquals(1, gateway.calls)
    }

    private fun authority(permissions: Set<String> = setOf("order_export.read")) =
        SalesOrderExportAuthority("user", "tenant", "workspace", "membership", permissions, 3)

    private class FakeGateway : SalesOrderExportGateway {
        var calls = 0
        var saveResult = SalesOrderExportSaveResult.Unavailable
        var nextResult: CompletableDeferred<SalesOrderExportSaveResult>? = null

        override suspend fun save(
            authority: SalesOrderExportAuthority,
            orderId: String,
            format: SalesOrderExportFormat,
            destination: SalesExportDestination
        ): SalesOrderExportSaveResult {
            calls++
            return nextResult?.await() ?: saveResult
        }
    }

    private companion object {
        const val ORDER_ID = "e7e5993b-2248-4ff1-b4ec-d291836bed3b"
    }
}
