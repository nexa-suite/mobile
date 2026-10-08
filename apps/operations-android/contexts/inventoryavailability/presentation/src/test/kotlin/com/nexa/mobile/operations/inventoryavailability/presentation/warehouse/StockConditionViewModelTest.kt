package com.nexa.mobile.operations.inventoryavailability.presentation.warehouse

import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockConditionGatewayResult
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.StockConditionGateway
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.StockConditionAvailability
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.StockConditionLot
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.ActiveOperationsContext
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.VerifiedOperationsIdentity
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StockConditionViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test fun permissionUnknownDoesNotCallGatewayOrShowStock() {
        val gateway = FakeStockConditionGateway()
        val viewModel = StockConditionViewModel(gateway)

        viewModel.activate(context(epoch = 1, permissions = emptySet()))

        assertEquals(StockConditionStatus.PermissionUnknown, viewModel.state.value.status)
        assertTrue(viewModel.state.value.lots.isEmpty())
        assertEquals(0, gateway.lotListCalls)
    }

    @Test fun legacyWarehouseReadAliasesPermitAuthorizedStockReads() = runTest {
        listOf("warehouse:read", "inventory.read").forEachIndexed { index, permission ->
            val gateway = FakeStockConditionGateway().apply {
                lotListResults += StockConditionGatewayResult.Lots(emptyList())
            }
            val viewModel = StockConditionViewModel(gateway)

            viewModel.activate(context(epoch = index + 1L, permissions = setOf(permission)))
            advanceUntilIdle()

            assertEquals(StockConditionStatus.Empty, viewModel.state.value.status)
            assertEquals(1, gateway.lotListCalls)
        }
    }

    @Test fun currentLotAndWarehouseSkuSellableStaySeparate() = runTest {
        val gateway = FakeStockConditionGateway().apply {
            lotListResults += StockConditionGatewayResult.Lots(listOf(lot()))
            lotResults += StockConditionGatewayResult.Lot(lot())
            availabilityResults += StockConditionGatewayResult.Availability(
                StockConditionAvailability(
                    catalogItemId = "CAT-42",
                    status = "AVAILABLE",
                    asOf = Instant.parse("2026-09-30T10:15:30Z"),
                    physicalQuantity = BigDecimal("24.50"),
                    safetyStock = BigDecimal("4.00"),
                    sellableQuantity = BigDecimal("20.50")
                )
            )
        }
        val viewModel = StockConditionViewModel(gateway) { Instant.parse("2026-09-30T10:16:00Z") }
        viewModel.activate(context(epoch = 1))
        advanceUntilIdle()
        viewModel.selectLot(LOT_ID)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(StockConditionStatus.Current, state.status)
        assertEquals(Instant.parse("2026-09-30T10:16:00Z"), state.listObservedAt)
        assertEquals(StockConditionDetailStatus.Current, state.detailStatus)
        assertEquals(BigDecimal("12.50"), state.selectedLot?.onHand)
        assertEquals(BigDecimal("2.25"), state.selectedLot?.reserved)
        assertEquals(BigDecimal("10.25"), state.selectedLot?.physicalRemaining)
        assertEquals(StockConditionAvailabilityStatus.Current, state.availabilityStatus)
        assertEquals(BigDecimal("20.50"), state.availability?.sellableQuantity)
        assertEquals("CAT-42", gateway.availabilityRequests.single().catalogItemId)
        assertEquals(WAREHOUSE_ID, gateway.availabilityRequests.single().warehouseId)
    }

    @Test fun failedRefreshRemovesPreviouslyDisplayedFactsUntilAnotherSuccessfulRead() = runTest {
        val gateway = FakeStockConditionGateway().apply {
            lotListResults += StockConditionGatewayResult.Lots(listOf(lot()))
            lotListResults += StockConditionGatewayResult.NetworkUnavailable
        }
        val viewModel = StockConditionViewModel(gateway)
        viewModel.activate(context(epoch = 1))
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.lots.size)

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(StockConditionStatus.NetworkUnavailable, viewModel.state.value.status)
        assertTrue(viewModel.state.value.lots.isEmpty())
        assertNull(viewModel.state.value.listObservedAt)
    }

    @Test fun lateOldScopeResponseCannotReplaceNewScopeFacts() = runTest {
        val oldResponse = CompletableDeferred<StockConditionGatewayResult>()
        val gateway = FakeStockConditionGateway().apply {
            lotListHandlers += { oldResponse.await() }
            lotListHandlers += { StockConditionGatewayResult.Lots(listOf(lot(id = OTHER_LOT_ID))) }
        }
        val viewModel = StockConditionViewModel(gateway)

        viewModel.activate(context(epoch = 1))
        runCurrent()
        viewModel.activate(context(epoch = 2, membershipId = "membership-2"))
        advanceUntilIdle()
        oldResponse.complete(StockConditionGatewayResult.Lots(listOf(lot())))
        advanceUntilIdle()

        assertEquals(2L, viewModel.state.value.authorityEpoch)
        assertEquals(listOf(OTHER_LOT_ID), viewModel.state.value.lots.map { it.id })
    }

    @Test fun permissionLossAndRouteExitClearLotAndSellableFacts() = runTest {
        val gateway = FakeStockConditionGateway().apply {
            lotListResults += StockConditionGatewayResult.Lots(listOf(lot()))
            lotResults += StockConditionGatewayResult.Lot(lot())
            availabilityResults += StockConditionGatewayResult.Availability(
                StockConditionAvailability(
                    "CAT-42",
                    "AVAILABLE",
                    Instant.parse("2026-09-30T10:15:30Z"),
                    BigDecimal("24.50"),
                    BigDecimal("4.00"),
                    BigDecimal("20.50")
                )
            )
            lotListResults += StockConditionGatewayResult.Lots(listOf(lot()))
        }
        val viewModel = StockConditionViewModel(gateway)
        viewModel.activate(context(epoch = 1))
        advanceUntilIdle()
        viewModel.selectLot(LOT_ID)
        advanceUntilIdle()
        assertEquals(BigDecimal("20.50"), viewModel.state.value.availability?.sellableQuantity)

        viewModel.activate(context(epoch = 2, permissions = setOf("catalog.read")))
        advanceUntilIdle()
        assertEquals(StockConditionStatus.PermissionDenied, viewModel.state.value.status)
        assertTrue(viewModel.state.value.lots.isEmpty())
        assertNull(viewModel.state.value.selectedLot)
        assertNull(viewModel.state.value.availability)

        viewModel.deactivate()
        assertEquals(StockConditionUiState(), viewModel.state.value)
    }

    @Test fun serverDenialAfterLotListClearsPreviouslyVisibleQuantities() = runTest {
        val gateway = FakeStockConditionGateway().apply {
            lotListResults += StockConditionGatewayResult.Lots(listOf(lot()))
            lotResults += StockConditionGatewayResult.PermissionDenied
        }
        val viewModel = StockConditionViewModel(gateway)
        viewModel.activate(context(epoch = 1))
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.lots.size)

        viewModel.selectLot(LOT_ID)
        advanceUntilIdle()

        assertEquals(StockConditionStatus.PermissionDenied, viewModel.state.value.status)
        assertTrue(viewModel.state.value.lots.isEmpty())
        assertNull(viewModel.state.value.selectedLot)
        assertNull(viewModel.state.value.listObservedAt)
    }

    @Test fun detailFromDifferentWarehouseNeverReplacesSelectedAuthorizedLot() = runTest {
        val gateway = FakeStockConditionGateway().apply {
            lotListResults += StockConditionGatewayResult.Lots(listOf(lot()))
            lotResults +=
                StockConditionGatewayResult.Lot(lot().copy(warehouseId = OTHER_WAREHOUSE_ID))
        }
        val viewModel = StockConditionViewModel(gateway)
        viewModel.activate(context(epoch = 1))
        advanceUntilIdle()

        viewModel.selectLot(LOT_ID)
        advanceUntilIdle()

        assertEquals(
            StockConditionDetailStatus.ServiceUnavailable,
            viewModel.state.value.detailStatus
        )
        assertNull(viewModel.state.value.selectedLot)
        assertNull(viewModel.state.value.availability)
        assertTrue(gateway.availabilityRequests.isEmpty())
    }

    @Test fun expiryHoldQuarantineAndAllocationEligibilityStayServerAuthoritative() = runTest {
        val gateway = FakeStockConditionGateway()
        val viewModel = StockConditionViewModel(gateway)
        listOf("EXPIRED", "HOLD", "QUARANTINED", "AVAILABLE").forEachIndexed { index, status ->
            val restricted = lot().copy(status = status)
            gateway.lotListResults += StockConditionGatewayResult.Lots(listOf(restricted))
            gateway.lotResults += StockConditionGatewayResult.Lot(restricted)
            gateway.availabilityResults += StockConditionGatewayResult.Availability(
                StockConditionAvailability(
                    catalogItemId = "CAT-42",
                    status = "UNAVAILABLE",
                    asOf = Instant.parse("2026-09-30T10:15:30Z"),
                    physicalQuantity = BigDecimal("12.50"),
                    safetyStock = BigDecimal("2.00"),
                    sellableQuantity = BigDecimal.ZERO
                )
            )
            viewModel.activate(context(epoch = index + 1L))
            advanceUntilIdle()
            viewModel.selectLot(LOT_ID)
            advanceUntilIdle()

            assertEquals(status, viewModel.state.value.selectedLot?.status)
            assertEquals(BigDecimal("10.25"), viewModel.state.value.selectedLot?.physicalRemaining)
            assertEquals(BigDecimal.ZERO, viewModel.state.value.availability?.sellableQuantity)
            assertEquals("UNAVAILABLE", viewModel.state.value.availability?.status)
        }
    }

    private class FakeStockConditionGateway : StockConditionGateway {
        val lotListResults = ArrayDeque<StockConditionGatewayResult>()
        val lotListHandlers = ArrayDeque<suspend () -> StockConditionGatewayResult>()
        val lotResults = ArrayDeque<StockConditionGatewayResult>()
        val availabilityResults = ArrayDeque<StockConditionGatewayResult>()
        val availabilityRequests = mutableListOf<AvailabilityRequest>()
        var lotListCalls = 0

        override suspend fun lots(context: ActiveOperationsContext): StockConditionGatewayResult {
            lotListCalls++
            if (lotListHandlers.isNotEmpty()) return lotListHandlers.removeFirst().invoke()
            return lotListResults.removeFirst()
        }

        override suspend fun lot(
            lotId: String,
            context: ActiveOperationsContext
        ): StockConditionGatewayResult = lotResults.removeFirst()

        override suspend fun availability(
            warehouseId: String,
            catalogItemId: String,
            context: ActiveOperationsContext
        ): StockConditionGatewayResult {
            availabilityRequests += AvailabilityRequest(warehouseId, catalogItemId)
            return availabilityResults.removeFirst()
        }
    }

    private data class AvailabilityRequest(val warehouseId: String, val catalogItemId: String)

    private fun context(
        epoch: Long,
        membershipId: String = "membership-1",
        permissions: Set<String> = setOf("warehouse.read")
    ) = ActiveOperationsContext(
        companyName = "Company",
        workspaceName = "Workspace",
        authorityEpoch = epoch,
        verifiedIdentity = VerifiedOperationsIdentity(
            userId = "user-1",
            tenantId = "tenant-1",
            workspaceId = "workspace-1",
            membershipId = membershipId,
            permissions = permissions
        )
    )

    private fun lot(id: String = LOT_ID) = StockConditionLot(
        id = id,
        warehouseId = WAREHOUSE_ID,
        zoneId = ZONE_ID,
        catalogItemId = "CAT-42",
        skuId = SKU_ID,
        batchNumber = "LOT-1",
        expirationDate = LocalDate.parse("2027-02-15"),
        receivedAt = Instant.parse("2026-09-29T18:00:00Z"),
        onHand = BigDecimal("12.50"),
        reserved = BigDecimal("2.25"),
        physicalRemaining = BigDecimal("10.25"),
        unit = "EA",
        status = "QUARANTINED",
        version = 4
    )

    private companion object {
        const val WAREHOUSE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413101"
        const val ZONE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413201"
        const val SKU_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413301"
        const val LOT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413401"
        const val OTHER_LOT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413402"
        const val OTHER_WAREHOUSE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413103"
    }
}
