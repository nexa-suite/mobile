package com.nexa.mobile.operations.inventoryavailability.application.warehouse

import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundReceiptRequest
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingAuthority
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingDraftMetadata
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingIntentMetadata
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingIntentMetadataStatus
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingLookupResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingMetadataRead
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingMetadataWrite
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingScopeIdentity
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingSubmitResult
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ReceivedLotFacts
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceivingIntentCoordinatorTest {
    @Test
    fun replayPreservesFullScopeIdempotencyKeyAndExactRequestPayload() = runTest {
        val events = mutableListOf<String>()
        val store = FakeReceivingMetadataStore(events)
        val gateway = FakeReceivingGateway(events).apply {
            results += ReceivingSubmitResult.UnknownOutcome
            results += ReceivingSubmitResult.Confirmed(facts(request))
        }
        val coordinator = ReceivingIntentCoordinator(gateway, store, Mutex())
        val authority = authority()
        val command = intent(authority)

        val first = coordinator.execute(
            command = command,
            draft = draft(),
            authority = authority,
            isCurrent = { true }
        )

        assertEquals(
            ReceivingIntentExecution.UnknownOutcome(ReceivingSubmitResult.UnknownOutcome, true),
            first
        )
        assertEquals(
            listOf("draft", "intent:Pending", "receive", "intent:UnknownOutcome"),
            events
        )
        assertEquals(authority.scope, store.intent?.scope)
        assertEquals(
            ReceivingScopeIdentity("user-17", "tenant-23", "workspace-31", "member-41"),
            store.intent?.scope
        )

        val frozen = store.intent ?: error("unknown outcome should retain the intent")
        val second = coordinator.execute(
            command = frozen.copy(status = ReceivingIntentMetadataStatus.Pending),
            draft = null,
            authority = authority,
            isCurrent = { true }
        )

        assertEquals(
            ReceivingIntentExecution.Terminal(
                ReceivingSubmitResult.Confirmed(facts(request)),
                intentCleared = true
            ),
            second
        )
        assertEquals(listOf("intent:Pending", "receive"), events.takeLast(3).take(2))
        assertEquals(2, gateway.requests.size)
        assertEquals(listOf(command.idempotencyKey, command.idempotencyKey), gateway.keys)
        assertEquals(listOf(request, request), gateway.requests)
        assertEquals("0.0100", gateway.requests.last().quantity.toPlainString())
        assertEquals("-1.250", gateway.requests.last().temperatureReading?.toPlainString())
        assertEquals(authority, gateway.authorities.last())
        assertNull(store.intent)
        assertTrue(events.contains("clear:${authority.scope}:receipt-key-001"))
    }

    @Test
    fun mismatchedConfirmedProjectionRemainsUnknownAndIsNeverCleared() = runTest {
        val events = mutableListOf<String>()
        val store = FakeReceivingMetadataStore(events)
        val gateway = FakeReceivingGateway(events).apply {
            results += ReceivingSubmitResult.Confirmed(
                facts(request).copy(onHand = BigDecimal("99.00"))
            )
        }
        val authority = authority()
        val command = intent(authority)
        val coordinator = ReceivingIntentCoordinator(gateway, store, Mutex())

        val result = coordinator.execute(
            command = command,
            draft = draft(),
            authority = authority,
            isCurrent = { true }
        )

        assertEquals(
            ReceivingIntentExecution.UnknownOutcome(ReceivingSubmitResult.UnknownOutcome, true),
            result
        )
        assertEquals(ReceivingIntentMetadataStatus.UnknownOutcome, store.intent?.status)
        assertFalse(events.any { it.startsWith("clear:") })
        assertEquals(request, gateway.requests.single())
        assertEquals(command.idempotencyKey, gateway.keys.single())
    }

    @Test
    fun lateTerminalClearRestoresUnknownIntentForOriginalScope() = runTest {
        val events = mutableListOf<String>()
        var current = true
        val store = FakeReceivingMetadataStore(events).apply {
            afterClear = { current = false }
        }
        val gateway = FakeReceivingGateway(events).apply {
            results += ReceivingSubmitResult.Confirmed(facts(request))
        }
        val authority = authority()
        val command = intent(authority)
        val coordinator = ReceivingIntentCoordinator(gateway, store, Mutex())

        val result = coordinator.execute(
            command = command,
            draft = draft(),
            authority = authority,
            isCurrent = { current }
        )

        assertEquals(ReceivingIntentExecution.Stale, result)
        assertEquals(authority.scope, store.intent?.scope)
        assertEquals(command.idempotencyKey, store.intent?.idempotencyKey)
        assertEquals(ReceivingIntentMetadataStatus.UnknownOutcome, store.intent?.status)
        assertTrue(
            events.takeLast(2).all {
                it == "intent:UnknownOutcome" || it.startsWith("clear:")
            }
        )
    }

    @Test
    fun responseFromAnOldAuthorityEpochDoesNotClearOrApplyTheIntent() = runTest {
        val events = mutableListOf<String>()
        var current = true
        val store = FakeReceivingMetadataStore(events)
        val gateway = FakeReceivingGateway(events).apply {
            results += ReceivingSubmitResult.Confirmed(facts(request))
            afterReceive = { current = false }
        }
        val authority = authority()
        val command = intent(authority)
        val coordinator = ReceivingIntentCoordinator(gateway, store, Mutex())

        val result = coordinator.execute(
            command = command,
            draft = draft(),
            authority = authority,
            isCurrent = { current }
        )

        assertEquals(ReceivingIntentExecution.Stale, result)
        assertEquals(ReceivingIntentMetadataStatus.Pending, store.intent?.status)
        assertFalse(events.any { it.startsWith("clear:") })
        assertEquals(command.idempotencyKey, store.intent?.idempotencyKey)
    }

    private class FakeReceivingGateway(private val events: MutableList<String>) : ReceivingGateway {
        val requests = mutableListOf<InboundReceiptRequest>()
        val keys = mutableListOf<String>()
        val authorities = mutableListOf<ReceivingAuthority>()
        val results = mutableListOf<ReceivingSubmitResult>()
        var afterReceive: (() -> Unit)? = null

        override suspend fun warehouses(authority: ReceivingAuthority): ReceivingLookupResult =
            ReceivingLookupResult.Warehouses(emptyList())

        override suspend fun zones(
            warehouseId: String,
            authority: ReceivingAuthority
        ): ReceivingLookupResult = ReceivingLookupResult.Zones(emptyList())

        override suspend fun receive(
            request: InboundReceiptRequest,
            idempotencyKey: String,
            authority: ReceivingAuthority
        ): ReceivingSubmitResult {
            events += "receive"
            requests += request
            keys += idempotencyKey
            authorities += authority
            afterReceive?.invoke()
            return results.removeAt(0)
        }
    }

    private class FakeReceivingMetadataStore(private val events: MutableList<String>) :
        ReceivingMetadataStore {
        var intent: ReceivingIntentMetadata? = null
        var afterClear: (() -> Unit)? = null

        override suspend fun loadDraft(
            scope: ReceivingScopeIdentity
        ): ReceivingMetadataRead<ReceivingDraftMetadata> = ReceivingMetadataRead.Available(null)

        override suspend fun saveDraft(
            scope: ReceivingScopeIdentity,
            draft: ReceivingDraftMetadata
        ): ReceivingMetadataWrite {
            events += "draft"
            return ReceivingMetadataWrite.Saved
        }

        override suspend fun loadIntent(
            scope: ReceivingScopeIdentity
        ): ReceivingMetadataRead<ReceivingIntentMetadata> =
            ReceivingMetadataRead.Available(intent?.takeIf { it.scope == scope })

        override suspend fun saveIntent(intent: ReceivingIntentMetadata): ReceivingMetadataWrite {
            events += "intent:${intent.status}"
            this.intent = intent
            return ReceivingMetadataWrite.Saved
        }

        override suspend fun clearIntent(
            scope: ReceivingScopeIdentity,
            idempotencyKey: String
        ): ReceivingMetadataWrite {
            events += "clear:$scope:$idempotencyKey"
            if (intent?.scope == scope && intent?.idempotencyKey == idempotencyKey) {
                intent = null
            }
            afterClear?.invoke()
            return ReceivingMetadataWrite.Saved
        }
    }

    private companion object {
        val request = InboundReceiptRequest(
            warehouseId = "warehouse-7",
            zoneId = "zone-9",
            catalogItemId = "CAT-0017",
            skuId = "8f060338-9547-4c09-9fa1-2e048bbc2a03",
            batchNumber = "BATCH-EXACT",
            expirationDate = LocalDate.parse("2027-04-16"),
            quantity = BigDecimal("0.0100"),
            unit = "KG",
            temperatureReading = BigDecimal("-1.250"),
            notes = "received with cold-chain evidence",
            temperatureEvidenceObjectId = "b8c24a46-57d9-4f64-8fa7-6a641b413501"
        )

        fun authority() = ReceivingAuthority(
            userId = "user-17",
            tenantId = "tenant-23",
            workspaceId = "workspace-31",
            membershipId = "member-41",
            permissions = setOf("inventory.receive"),
            authorityEpoch = 7
        )

        fun intent(authority: ReceivingAuthority) = ReceivingIntentMetadata(
            scope = authority.scope,
            idempotencyKey = "receipt-key-001",
            request = request,
            status = ReceivingIntentMetadataStatus.Pending
        )

        fun draft() = ReceivingDraftMetadata(
            selectedProduct = null,
            warehouseId = request.warehouseId,
            zoneId = request.zoneId,
            batchNumber = request.batchNumber,
            expirationDateText = request.expirationDate.toString(),
            quantityText = request.quantity.toPlainString(),
            unit = request.unit,
            temperatureReadingText = request.temperatureReading!!.toPlainString(),
            temperatureEvidenceObjectId = request.temperatureEvidenceObjectId
        )

        fun facts(request: InboundReceiptRequest) = ReceivedLotFacts(
            id = "lot-45",
            warehouseId = request.warehouseId,
            zoneId = request.zoneId,
            catalogItemId = request.catalogItemId,
            skuId = request.skuId,
            batchNumber = request.batchNumber,
            expirationDate = request.expirationDate,
            receivedAt = "2026-10-07T12:30:00Z",
            onHand = request.quantity,
            reserved = BigDecimal.ZERO,
            available = request.quantity,
            unit = request.unit,
            status = "AVAILABLE",
            version = 1
        )
    }
}
