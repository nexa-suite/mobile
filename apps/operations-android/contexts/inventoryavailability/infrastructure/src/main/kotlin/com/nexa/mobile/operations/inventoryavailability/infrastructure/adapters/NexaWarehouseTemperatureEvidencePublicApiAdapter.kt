package com.nexa.mobile.operations.inventoryavailability.infrastructure.adapters

import com.nexa.mobile.operations.inventoryavailability.application.publicapi.WarehouseTemperatureEvidenceProjection
import com.nexa.mobile.operations.inventoryavailability.application.publicapi.WarehouseTemperatureEvidencePublicApi
import com.nexa.mobile.operations.inventoryavailability.application.publicapi.WarehouseTemperatureEvidenceResult
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.NexaReceivingGateway
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.ReceivingEvidenceProjection
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.ReceivingNetworkOutcome
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NexaWarehouseTemperatureEvidencePublicApiAdapter @Inject constructor(
    private val receiving: NexaReceivingGateway
) : WarehouseTemperatureEvidencePublicApi {
    override suspend fun uploadTemperatureEvidence(
        warehouseId: String,
        idempotencyKey: String,
        file: java.io.File,
        originalFilename: String,
        declaredContentType: String,
        byteSize: Long,
        checksumSha256: String
    ): WarehouseTemperatureEvidenceResult = receiving.uploadTemperatureEvidence(
        warehouseId = warehouseId,
        idempotencyKey = idempotencyKey,
        file = file,
        originalFilename = originalFilename,
        declaredContentType = declaredContentType,
        byteSize = byteSize,
        checksumSha256 = checksumSha256
    ).toPublicResult()

    override suspend fun temperatureEvidenceStatus(
        evidenceId: String,
        warehouseId: String
    ): WarehouseTemperatureEvidenceResult = receiving.temperatureEvidenceStatus(
        evidenceId = evidenceId,
        warehouseId = warehouseId
    ).toPublicResult()

    private fun ReceivingNetworkOutcome.toPublicResult(): WarehouseTemperatureEvidenceResult =
        when (this) {
            is ReceivingNetworkOutcome.EvidenceUploaded ->
                WarehouseTemperatureEvidenceResult.Uploaded(evidence.toPublicProjection())

            is ReceivingNetworkOutcome.EvidenceStatus ->
                WarehouseTemperatureEvidenceResult.Status(evidence.toPublicProjection())

            is ReceivingNetworkOutcome.Rejected ->
                WarehouseTemperatureEvidenceResult.Rejected(code)

            ReceivingNetworkOutcome.UnknownOutcome ->
                WarehouseTemperatureEvidenceResult.UnknownOutcome

            ReceivingNetworkOutcome.NetworkUnavailable ->
                WarehouseTemperatureEvidenceResult.NetworkUnavailable

            ReceivingNetworkOutcome.ServiceUnavailable ->
                WarehouseTemperatureEvidenceResult.ServiceUnavailable

            ReceivingNetworkOutcome.PermissionDenied ->
                WarehouseTemperatureEvidenceResult.PermissionDenied

            ReceivingNetworkOutcome.ContextInvalidated ->
                WarehouseTemperatureEvidenceResult.ContextInvalidated

            ReceivingNetworkOutcome.SessionInvalidated ->
                WarehouseTemperatureEvidenceResult.SessionInvalidated

            else -> WarehouseTemperatureEvidenceResult.ServiceUnavailable
        }

    private fun ReceivingEvidenceProjection.toPublicProjection() =
        WarehouseTemperatureEvidenceProjection(
            id = id,
            subjectType = subjectType,
            subjectId = subjectId,
            lifecycleStatus = lifecycleStatus
        )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WarehouseTemperatureEvidencePublicApiModule {
    @Binds
    @Singleton
    abstract fun bindWarehouseTemperatureEvidencePublicApi(
        adapter: NexaWarehouseTemperatureEvidencePublicApiAdapter
    ): WarehouseTemperatureEvidencePublicApi
}
