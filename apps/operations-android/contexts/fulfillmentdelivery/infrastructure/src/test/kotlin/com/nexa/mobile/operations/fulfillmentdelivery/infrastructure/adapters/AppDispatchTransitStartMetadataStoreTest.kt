package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters

import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartIntentStatus
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartScopeIdentity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDispatchTransitStartMetadataStoreTest {
    @Test
    fun reopeningTheAdapterConvertsPendingToUnknownWithExactCommandIdentity() = runTest {
        val local = MemoryScopedMetadataStore()
        val store = AppDispatchTransitStartMetadataStore(local)
        val intent = intent()

        assertEquals(DispatchTransitStartMetadataWrite.Saved, store.saveIntent(intent))
        val reopened = AppDispatchTransitStartMetadataStore(local)
        val restored = reopened.loadIntent(SCOPE, DELIVERY_ID)

        assertEquals(
            DispatchTransitStartMetadataRead.Available(
                intent.copy(status = DispatchTransitStartIntentStatus.UnknownOutcome)
            ),
            restored
        )
    }

    @Test
    fun unknownOutcomeCannotBeOverwrittenWithANewKeyAndClearIsKeyFenced() = runTest {
        val local = MemoryScopedMetadataStore()
        val store = AppDispatchTransitStartMetadataStore(local)
        val intent = intent(DispatchTransitStartIntentStatus.UnknownOutcome)

        assertEquals(DispatchTransitStartMetadataWrite.Saved, store.saveIntent(intent))
        assertEquals(
            DispatchTransitStartMetadataWrite.Conflict,
            store.saveIntent(
                intent.copy(command = intent.command.copy(idempotencyKey = "replacement"))
            )
        )
        assertEquals(
            DispatchTransitStartMetadataWrite.Stale,
            store.clearIntent(SCOPE, DELIVERY_ID, "other-key")
        )
        assertEquals(
            DispatchTransitStartMetadataRead.Available(intent),
            store.loadIntent(SCOPE, DELIVERY_ID)
        )
    }

    @Test
    fun metadataIsIsolatedByCompleteVerifiedScopeAndCorruptionFailsClosed() = runTest {
        val local = MemoryScopedMetadataStore()
        val store = AppDispatchTransitStartMetadataStore(local)
        assertEquals(DispatchTransitStartMetadataWrite.Saved, store.saveIntent(intent()))

        assertEquals(
            DispatchTransitStartMetadataRead.Available(null),
            store.loadIntent(SCOPE.copy(workspaceId = OTHER_WORKSPACE_ID), DELIVERY_ID)
        )
        val scope = ScopedMetadataScope(
            SCOPE.userId,
            SCOPE.tenantId,
            SCOPE.workspaceId,
            SCOPE.membershipId
        )
        local.values[scope] = "not-json"
        assertEquals(
            DispatchTransitStartMetadataRead.Unavailable,
            AppDispatchTransitStartMetadataStore(local).loadIntent(SCOPE, DELIVERY_ID)
        )
        assertTrue(local.values.isNotEmpty())
    }

    @Test
    fun clearingExactCommandRemovesPersistedIntent() = runTest {
        val local = MemoryScopedMetadataStore()
        val store = AppDispatchTransitStartMetadataStore(local)
        val command = intent().command
        store.saveIntent(intent())

        assertEquals(
            DispatchTransitStartMetadataWrite.Saved,
            store.clearIntent(SCOPE, DELIVERY_ID, command.idempotencyKey)
        )
        val read = store.loadIntent(
            SCOPE,
            DELIVERY_ID
        ) as DispatchTransitStartMetadataRead.Available
        assertNull(read.intent)
    }

    private fun intent(
        status: DispatchTransitStartIntentStatus = DispatchTransitStartIntentStatus.Pending
    ) = DispatchTransitStartIntent(
        scope = SCOPE,
        command = DispatchTransitStartCommand(
            deliveryId = DELIVERY_ID,
            fulfillmentId = FULFILLMENT_ID,
            deliveryEtag = "\"17\"",
            idempotencyKey = "transit-stable-key"
        ),
        status = status
    )

    private class MemoryScopedMetadataStore : ScopedMetadataStore {
        val values = mutableMapOf<ScopedMetadataScope, String>()

        override suspend fun load(scope: ScopedMetadataScope): ScopedMetadataRead =
            ScopedMetadataRead.Value(values[scope])

        override suspend fun save(scope: ScopedMetadataScope, payload: String): Boolean {
            values[scope] = payload
            return true
        }

        override suspend fun clear(scope: ScopedMetadataScope): Boolean {
            values.remove(scope)
            return true
        }
    }

    private companion object {
        val SCOPE = DispatchTransitStartScopeIdentity(
            userId = "b8c24a46-57d9-4f64-8fa7-6a641b413901",
            tenantId = "b8c24a46-57d9-4f64-8fa7-6a641b413902",
            workspaceId = "b8c24a46-57d9-4f64-8fa7-6a641b413903",
            membershipId = "b8c24a46-57d9-4f64-8fa7-6a641b413904"
        )
        const val OTHER_WORKSPACE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413905"
        const val DELIVERY_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413501"
        const val FULFILLMENT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413201"
    }
}
