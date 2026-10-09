package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters

import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverExecutionTemperatureCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverExecutionTemperatureIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverExecutionTemperatureIntentStatus
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverExecutionTemperatureMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverExecutionTemperatureMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.delivery.JsonDeliveryRequestBodyCodec
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AppDriverExecutionTemperatureMetadataStoreTest {
    @Test
    fun storeRejectsMismatchedFrozenBodyAndRecoversTheOriginalBytes() = runTest {
        val local = FakeScopedMetadataStore()
        val codec = JsonDeliveryRequestBodyCodec()
        val scope = DriverAttemptScopeIdentity(USER, TENANT, WORKSPACE, MEMBERSHIP)
        val intent = intent(scope, codec)
        val store = AppDriverExecutionTemperatureMetadataStore(local, codec)

        assertEquals(
            DriverExecutionTemperatureMetadataWrite.Conflict,
            store.saveIntent(
                intent.copy(
                    command = (intent.command as DriverExecutionTemperatureCommand.Reading)
                        .copy(frozenBody = "{}")
                )
            )
        )
        assertEquals(DriverExecutionTemperatureMetadataWrite.Saved, store.saveIntent(intent))
        assertEquals(
            DriverExecutionTemperatureMetadataRead.Available(
                intent.copy(status = DriverExecutionTemperatureIntentStatus.UnknownOutcome)
            ),
            AppDriverExecutionTemperatureMetadataStore(local, codec).loadIntent(scope)
        )
    }

    private fun intent(
        scope: DriverAttemptScopeIdentity,
        codec: JsonDeliveryRequestBodyCodec
    ): DriverExecutionTemperatureIntent {
        val draft = DriverExecutionTemperatureCommand.Reading(
            deliveryId = DELIVERY,
            expectedDeliveryVersion = 6,
            fulfillmentLineId = LINE,
            skuId = SKU,
            affectedQuantity = BigDecimal("2.50"),
            valueCelsius = BigDecimal("4.2"),
            occurredAt = Instant.parse("2026-10-01T10:15:30Z"),
            sourceIncidentId = null,
            evidenceObjectId = null,
            idempotencyKey = "reading-key",
            frozenBody = ""
        )
        val command = draft.copy(frozenBody = codec.driverExecutionTemperatureBody(draft))
        return DriverExecutionTemperatureIntent(
            scope,
            command,
            Instant.parse("2026-10-01T10:16:00Z"),
            DriverExecutionTemperatureIntentStatus.Pending
        )
    }

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
        const val DELIVERY = "11111111-1111-4111-8111-111111111111"
        const val LINE = "22222222-2222-4222-8222-222222222222"
        const val SKU = "33333333-3333-4333-8333-333333333333"
    }
}
