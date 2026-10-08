package com.nexa.mobile.operations.feature.warehouse

import com.nexa.mobile.operations.feature.warehouse.application.PickingWorkListGateway
import com.nexa.mobile.operations.feature.warehouse.model.PickingAuthority
import com.nexa.mobile.operations.feature.warehouse.model.PickingWorkItem
import com.nexa.mobile.operations.feature.warehouse.model.PickingWorkListResult
import com.nexa.mobile.operations.feature.warehouse.model.PickingWorkPage
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PickingWorkListViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun loadsScopedServerWorkAndPaginatesFromServerCount() = runTest {
        val gateway = FakeWorkListGateway().apply {
            results += page(page = 0, totalItems = 26)
            results += page(page = 1, totalItems = 26, fulfillmentId = OTHER_FULFILLMENT_ID)
        }
        val viewModel = PickingWorkListViewModel(gateway)

        viewModel.activate(authority())
        advanceUntilIdle()
        assertEquals(PickingWorkListStatus.Ready, viewModel.state.value.status)
        assertEquals(FULFILLMENT_ID, viewModel.state.value.items.single().fulfillmentId)
        assertEquals(26L, viewModel.state.value.totalItems)

        viewModel.nextPage()
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.page)
        assertEquals(OTHER_FULFILLMENT_ID, viewModel.state.value.items.single().fulfillmentId)
        assertEquals(listOf(0, 1), gateway.calls.map { it.page })
        assertTrue(gateway.calls.all { it.size == 25 && it.authority == authority() })
    }

    @Test
    fun permissionDenialMakesNoGatewayCallAndInvalidationDropsLateResults() = runTest {
        val gateway = FakeWorkListGateway().apply { results += page() }
        val viewModel = PickingWorkListViewModel(gateway)
        val denied = authority().copy(permissions = emptySet())
        viewModel.activate(denied)
        advanceUntilIdle()
        assertEquals(PickingWorkListStatus.PermissionDenied, viewModel.state.value.status)
        assertTrue(gateway.calls.isEmpty())

        val gate = CompletableDeferred<Unit>()
        gateway.gate = gate
        gateway.results += page()
        viewModel.activate(authority())
        runCurrent()
        viewModel.invalidate()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(PickingWorkListStatus.NotRequested, viewModel.state.value.status)
        assertTrue(viewModel.state.value.items.isEmpty())
    }

    private fun authority() = PickingAuthority(
        userId = USER_ID,
        tenantId = TENANT_ID,
        workspaceId = WORKSPACE_ID,
        membershipId = MEMBERSHIP_ID,
        permissions = setOf("fulfillment.read"),
        authorityEpoch = 4
    )

    private fun page(page: Int = 0, totalItems: Long = 1, fulfillmentId: String = FULFILLMENT_ID) =
        PickingWorkListResult.Loaded(
            PickingWorkPage(
                items = listOf(
                    PickingWorkItem(
                        fulfillmentId = fulfillmentId,
                        salesOrderId = SALES_ORDER_ID,
                        status = "ALLOCATED",
                        version = 2,
                        physicalAllocationId = ALLOCATION_ID,
                        allocationVersion = 3,
                        lineCount = 1
                    )
                ),
                page = page,
                size = 25,
                totalItems = totalItems,
                asOf = Instant.parse("2026-09-30T12:00:00Z")
            )
        )

    private data class Request(val authority: PickingAuthority, val page: Int, val size: Int)

    private class FakeWorkListGateway : PickingWorkListGateway {
        val calls = mutableListOf<Request>()
        val results = ArrayDeque<PickingWorkListResult>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun list(
            authority: PickingAuthority,
            page: Int,
            size: Int
        ): PickingWorkListResult {
            calls += Request(authority, page, size)
            gate?.await()
            return results.removeFirst()
        }
    }

    private companion object {
        const val USER_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413001"
        const val TENANT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413002"
        const val WORKSPACE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413003"
        const val MEMBERSHIP_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413004"
        const val FULFILLMENT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413005"
        const val OTHER_FULFILLMENT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413006"
        const val SALES_ORDER_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413007"
        const val ALLOCATION_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413008"
    }
}
