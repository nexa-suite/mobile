package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters

import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingConfirmationCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingFulfillmentSnapshot
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingIntentCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingLoadResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingMutationResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.publicapi.CurrentPickingAllocationResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.warehouse.PickingGateway
import com.nexa.mobile.operations.fulfillmentdelivery.domain.model.warehouse.FulfillmentPickingSnapshot
import com.nexa.mobile.operations.inventoryavailability.application.publicapi.PhysicalAllocationProjection
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentPickingAllocationQueryAdapterTest {
    @Test
    fun exposesAllocationFromTheGuardedPickingRead() = runTest {
        val allocation = PhysicalAllocationProjection(
            allocationId = "allocation-1",
            status = "ALLOCATED",
            version = 4,
            asOf = Instant.parse("2026-10-08T10:00:00Z"),
            lines = emptyList()
        )
        val gateway = FakePickingGateway(
            PickingLoadResult.Loaded(
                PickingFulfillmentSnapshot(
                    FulfillmentPickingSnapshot("fulfillment-1", "PICKING", 3, emptyList()),
                    allocation
                )
            )
        )

        val result = OperationsCurrentPickingAllocationQuery(gateway).current(
            "fulfillment-1",
            authority
        )

        assertEquals(CurrentPickingAllocationResult.Loaded(allocation), result)
        assertEquals("fulfillment-1", gateway.loadedFulfillmentId)
        assertEquals(authority, gateway.loadedAuthority)
    }

    @Test
    fun preservesMissingAndPermissionFailuresAsTypedResults() = runTest {
        val fulfillment = FulfillmentPickingSnapshot("fulfillment-1", "PICKING", 3, emptyList())
        val gateway = FakePickingGateway(PickingLoadResult.AllocationUnavailable(fulfillment))
        val query = OperationsCurrentPickingAllocationQuery(gateway)

        assertEquals(
            CurrentPickingAllocationResult.NotFound,
            query.current("fulfillment-1", authority)
        )

        gateway.result = PickingLoadResult.PermissionDenied
        assertEquals(
            CurrentPickingAllocationResult.PermissionDenied,
            query.current("fulfillment-1", authority)
        )
    }

    private val authority = PickingAuthority(
        userId = "user-1",
        tenantId = "tenant-1",
        workspaceId = "workspace-1",
        membershipId = "membership-1",
        permissions = setOf("fulfillment.read"),
        authorityEpoch = 1
    )

    private class FakePickingGateway(var result: PickingLoadResult) : PickingGateway {
        var loadedFulfillmentId: String? = null
        var loadedAuthority: PickingAuthority? = null

        override suspend fun load(
            fulfillmentId: String,
            authority: PickingAuthority
        ): PickingLoadResult {
            loadedFulfillmentId = fulfillmentId
            loadedAuthority = authority
            return result
        }

        override suspend fun startPicking(
            command: PickingIntentCommand.Start,
            idempotencyKey: String,
            authority: PickingAuthority
        ): PickingMutationResult = error("not used by this query test")

        override suspend fun confirmPicking(
            command: PickingConfirmationCommand,
            idempotencyKey: String,
            authority: PickingAuthority
        ): PickingMutationResult = error("not used by this query test")
    }
}
