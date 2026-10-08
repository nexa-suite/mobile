package com.nexa.mobile.operations

import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataStore
import com.nexa.mobile.operations.data.AppDispatchTemperatureMetadataStore
import com.nexa.mobile.operations.feature.dispatch.model.DispatchTemperatureCommand
import com.nexa.mobile.operations.feature.dispatch.model.DispatchTemperatureIntent
import com.nexa.mobile.operations.feature.dispatch.model.DispatchTemperatureIntentStatus as TemperatureIntentStatus
import com.nexa.mobile.operations.feature.dispatch.model.DispatchTemperatureMetadataRead as TemperatureMetadataRead
import com.nexa.mobile.operations.feature.dispatch.model.DispatchTemperatureMetadataWrite as TemperatureMetadataWrite
import com.nexa.mobile.operations.feature.dispatch.model.DispatchTemperaturePhotoUploadIntent as TemperaturePhotoUploadIntent
import com.nexa.mobile.operations.feature.dispatch.model.DispatchTemperaturePhotoUploadMetadataRead as TemperaturePhotoUploadMetadataRead
import com.nexa.mobile.operations.feature.dispatch.model.DispatchTemperatureScopeIdentity as TemperatureScopeIdentity
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AppDispatchTemperatureMetadataStoreTest {
    @Test
    fun recoveryTurnsPendingIntoUnknownWithoutChangingBodyVersionOrKey() = runTest {
        val local = FakeScopedMetadataStore()
        val scope = scope()
        val command = command()
        val pending = DispatchTemperatureIntent(scope, command)
        assertEquals(
            TemperatureMetadataWrite.Saved,
            AppDispatchTemperatureMetadataStore(local).saveIntent(pending)
        )

        val recovered = AppDispatchTemperatureMetadataStore(local).loadIntent(scope, FULFILLMENT)

        assertEquals(
            TemperatureMetadataRead.Available(
                pending.copy(status = TemperatureIntentStatus.UnknownOutcome)
            ),
            recovered
        )
        assertEquals(
            TemperatureMetadataWrite.Conflict,
            AppDispatchTemperatureMetadataStore(local).saveIntent(
                pending.copy(command = command.copy(idempotencyKey = "new-key"))
            )
        )
    }

    @Test
    fun fullIdentityScopeDoesNotRestoreAnotherMembershipsCommand() = runTest {
        val local = FakeScopedMetadataStore()
        val originalScope = scope()
        val command = command()
        val store = AppDispatchTemperatureMetadataStore(local)
        assertEquals(
            TemperatureMetadataWrite.Saved,
            store.saveIntent(DispatchTemperatureIntent(originalScope, command))
        )

        val otherMembership = originalScope.copy(membershipId = OTHER_MEMBERSHIP)

        assertEquals(
            TemperatureMetadataRead.Available(null),
            store.loadIntent(otherMembership, FULFILLMENT)
        )
        assertEquals(
            TemperatureMetadataWrite.Saved,
            store.clearIntent(originalScope, FULFILLMENT, command.idempotencyKey)
        )
    }

    @Test
    fun excursionEvidenceIdAndExactBodySurviveEncryptedIntentRecovery() = runTest {
        val local = FakeScopedMetadataStore()
        val scope = scope()
        val original = command().let { command ->
            val withEvidence = command.copy(evidenceObjectId = EVIDENCE, expectedLotVersion = 3)
            withEvidence.copy(exactRequestBody = withEvidence.buildRequestBody())
        }
        val store = AppDispatchTemperatureMetadataStore(local)
        assertEquals(
            TemperatureMetadataWrite.Saved,
            store.saveIntent(DispatchTemperatureIntent(scope, original))
        )

        val recovered = AppDispatchTemperatureMetadataStore(local).loadIntent(scope, FULFILLMENT)

        assertEquals(
            TemperatureMetadataRead.Available(
                DispatchTemperatureIntent(
                    scope,
                    original,
                    TemperatureIntentStatus.UnknownOutcome
                )
            ),
            recovered
        )
        assertEquals(true, original.exactRequestBody.contains("\"evidenceObjectId\":\"$EVIDENCE\""))
        assertEquals(true, original.exactRequestBody.contains("\"expectedLotVersion\":3"))
    }

    @Test
    fun photoUploadRetryMetadataIsFullScopeBoundAndKeepsItsKeyUntilExplicitClear() = runTest {
        val local = FakeScopedMetadataStore()
        val scope = scope()
        val original = photoIntent(scope)
        val store = AppDispatchTemperatureMetadataStore(local)
        assertEquals(TemperatureMetadataWrite.Saved, store.savePhotoUploadIntent(original))

        val recovered = AppDispatchTemperatureMetadataStore(local)
            .loadPhotoUploadIntent(scope, FULFILLMENT, LOT)

        assertEquals(TemperaturePhotoUploadMetadataRead.Available(original), recovered)
        assertEquals(
            TemperaturePhotoUploadMetadataRead.Available(null),
            store.loadPhotoUploadIntent(
                scope.copy(membershipId = OTHER_MEMBERSHIP),
                FULFILLMENT,
                LOT
            )
        )
        assertEquals(
            TemperatureMetadataWrite.Conflict,
            store.savePhotoUploadIntent(original.copy(idempotencyKey = "replacement-key"))
        )
        assertEquals(
            TemperatureMetadataWrite.Stale,
            store.clearPhotoUploadIntent(scope, FULFILLMENT, LOT, "wrong-key")
        )
        assertEquals(
            TemperaturePhotoUploadMetadataRead.Available(original),
            store.loadPhotoUploadIntent(scope, FULFILLMENT, LOT)
        )
        assertEquals(
            TemperatureMetadataWrite.Saved,
            store.clearPhotoUploadIntent(scope, FULFILLMENT, LOT, original.idempotencyKey)
        )
        assertEquals(
            TemperaturePhotoUploadMetadataRead.Available(null),
            store.loadPhotoUploadIntent(scope, FULFILLMENT, LOT)
        )
    }

    private fun command(): DispatchTemperatureCommand {
        val partial = DispatchTemperatureCommand(
            fulfillmentId = FULFILLMENT,
            expectedFulfillmentVersion = 8,
            lotId = LOT,
            valueCelsius = BigDecimal("5.50"),
            occurredAt = Instant.parse("2026-10-01T10:15:30Z"),
            idempotencyKey = "temperature-key",
            exactRequestBody = ""
        )
        return partial.copy(exactRequestBody = partial.buildRequestBody())
    }

    private fun scope() = TemperatureScopeIdentity(USER, TENANT, WORKSPACE, MEMBERSHIP)

    private fun photoIntent(scope: TemperatureScopeIdentity) = TemperaturePhotoUploadIntent(
        scope = scope,
        fulfillmentId = FULFILLMENT,
        lotId = LOT,
        warehouseId = WAREHOUSE,
        idempotencyKey = "photo-upload-key",
        originalFilename = "temperature-proof.jpg",
        declaredContentType = "image/jpeg",
        byteSize = 128,
        checksumSha256 = "a".repeat(64)
    )

    private class FakeScopedMetadataStore : ScopedMetadataStore {
        private val records = mutableMapOf<ScopedMetadataScope, String>()

        override suspend fun load(scope: ScopedMetadataScope): ScopedMetadataRead =
            ScopedMetadataRead.Value(records[scope])

        override suspend fun save(scope: ScopedMetadataScope, payload: String): Boolean {
            records[scope] = payload
            return true
        }

        override suspend fun clear(scope: ScopedMetadataScope): Boolean {
            records.remove(scope)
            return true
        }
    }

    private companion object {
        const val USER = "user-id"
        const val TENANT = "tenant-id"
        const val WORKSPACE = "workspace-id"
        const val MEMBERSHIP = "member-one"
        const val OTHER_MEMBERSHIP = "member-two"
        const val FULFILLMENT = "11111111-1111-4111-8111-111111111111"
        const val LOT = "22222222-2222-4222-8222-222222222222"
        const val WAREHOUSE = "44444444-4444-4444-8444-444444444444"
        const val EVIDENCE = "33333333-3333-4333-8333-333333333333"
    }
}
