package com.nexa.mobile.operations

import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataStore
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureCommand
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureIntent
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureIntentStatus
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureMetadataRead
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureMetadataWrite
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureScopeIdentity
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
        assertEquals(DispatchTemperatureMetadataWrite.Saved,
            AppDispatchTemperatureMetadataStore(local).saveIntent(pending))

        val recovered = AppDispatchTemperatureMetadataStore(local).loadIntent(scope, FULFILLMENT)

        assertEquals(
            DispatchTemperatureMetadataRead.Available(
                pending.copy(status = DispatchTemperatureIntentStatus.UnknownOutcome)
            ),
            recovered
        )
        assertEquals(DispatchTemperatureMetadataWrite.Conflict,
            AppDispatchTemperatureMetadataStore(local).saveIntent(
                pending.copy(command = command.copy(idempotencyKey = "new-key"))
            ))
    }

    @Test
    fun fullIdentityScopeDoesNotRestoreAnotherMembershipsCommand() = runTest {
        val local = FakeScopedMetadataStore()
        val originalScope = scope()
        val command = command()
        val store = AppDispatchTemperatureMetadataStore(local)
        assertEquals(DispatchTemperatureMetadataWrite.Saved,
            store.saveIntent(DispatchTemperatureIntent(originalScope, command)))

        val otherMembership = originalScope.copy(membershipId = OTHER_MEMBERSHIP)

        assertEquals(DispatchTemperatureMetadataRead.Available(null),
            store.loadIntent(otherMembership, FULFILLMENT))
        assertEquals(DispatchTemperatureMetadataWrite.Saved,
            store.clearIntent(originalScope, FULFILLMENT, command.idempotencyKey))
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

    private fun scope() = DispatchTemperatureScopeIdentity(USER, TENANT, WORKSPACE, MEMBERSHIP)

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
    }
}
