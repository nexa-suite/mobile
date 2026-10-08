package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters

import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingLoadResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.publicapi.CurrentPickingAllocationQuery
import com.nexa.mobile.operations.fulfillmentdelivery.application.publicapi.CurrentPickingAllocationResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.warehouse.PickingGateway
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** Publishes the existing guarded picking read as a narrow cross-context query. */
@Singleton
class OperationsCurrentPickingAllocationQuery @Inject constructor(
    private val pickingGateway: PickingGateway
) : CurrentPickingAllocationQuery {
    override suspend fun current(
        fulfillmentId: String,
        authority: PickingAuthority
    ): CurrentPickingAllocationResult = when (
        val result = pickingGateway.load(fulfillmentId, authority)
    ) {
        is PickingLoadResult.Loaded -> CurrentPickingAllocationResult.Loaded(
            result.snapshot.allocation
        )

        is PickingLoadResult.AllocationUnavailable,
        PickingLoadResult.NotFound -> CurrentPickingAllocationResult.NotFound

        PickingLoadResult.NetworkUnavailable ->
            CurrentPickingAllocationResult.NetworkUnavailable

        PickingLoadResult.ServiceUnavailable ->
            CurrentPickingAllocationResult.ServiceUnavailable

        PickingLoadResult.PermissionDenied -> CurrentPickingAllocationResult.PermissionDenied

        PickingLoadResult.ContextInvalidated -> CurrentPickingAllocationResult.ContextInvalidated

        PickingLoadResult.SessionInvalidated -> CurrentPickingAllocationResult.SessionInvalidated
    }
}

@Module
@InstallIn(SingletonComponent::class)
object CurrentPickingAllocationQueryModule {
    @Provides
    @Singleton
    fun currentPickingAllocationQuery(
        implementation: OperationsCurrentPickingAllocationQuery
    ): CurrentPickingAllocationQuery = implementation
}
