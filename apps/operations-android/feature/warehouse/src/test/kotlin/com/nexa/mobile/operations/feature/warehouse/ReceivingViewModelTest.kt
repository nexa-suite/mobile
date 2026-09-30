package com.nexa.mobile.operations.feature.warehouse

import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReceivingViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun draftValidationAndUnavailableMetadataNeverDispatchOrClaimStock() = runTest {
        val gateway = FakeReceivingGateway()
        val storage = FakeReceivingMetadataStore(available = false)
        val viewModel = ReceivingViewModel(gateway, storage)
        viewModel.activate(authority(), ConfirmedReceivingProduct(product, 1))
        advanceUntilIdle()
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(0, gateway.receiveCalls)
        assertEquals(ReceivingMetadataStatus.Unavailable, viewModel.state.value.metadata)
        assertEquals(
            ReceivingValidationError.MetadataUnavailable,
            viewModel.state.value.validationError
        )
        assertNull(viewModel.state.value.confirmedLot)

        val storageReady = FakeReceivingMetadataStore()
        val secondGateway = FakeReceivingGateway()
        val readyViewModel = readyViewModel(secondGateway, storageReady)
        readyViewModel.quantityChanged("0")
        advanceUntilIdle()
        readyViewModel.submit()
        advanceUntilIdle()
        assertEquals(0, secondGateway.receiveCalls)
        assertEquals(
            ReceivingValidationError.QuantityMustBePositive,
            readyViewModel.state.value.validationError
        )
    }

    @Test
    fun unknownOutcomeRequiresExplicitReplayWithTheSameKeyAndFrozenDecimalPayload() = runTest {
        val gateway = FakeReceivingGateway().apply {
            submitResults += ReceivingSubmitResult.UnknownOutcome
            submitResults += ReceivingSubmitResult.Confirmed(facts(request()))
        }
        val storage = FakeReceivingMetadataStore()
        val viewModel = readyViewModel(gateway, storage)
        viewModel.quantityChanged("0.0100")
        viewModel.temperatureReadingChanged("-1.250")
        advanceUntilIdle()
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(1, gateway.receiveCalls)
        assertEquals(ReceivingCommandStatus.UnknownOutcome, viewModel.state.value.command)
        assertNull(viewModel.state.value.confirmedLot)
        assertEquals(ReceivingIntentMetadataStatus.UnknownOutcome, storage.intent?.status)
        assertTrue(viewModel.state.value.isIntentFrozen)
        val frozen = storage.intent ?: error("intent metadata should be persisted")
        assertEquals("0.0100", frozen.request.quantity.toPlainString())
        assertEquals("-1.250", frozen.request.temperatureReading?.toPlainString())
        assertEquals("LOT-REAL-7", frozen.request.batchNumber)

        viewModel.retryUnknownOutcome()
        advanceUntilIdle()

        assertEquals(2, gateway.receiveCalls)
        assertEquals(listOf(gateway.keys.first()), gateway.keys.distinct())
        assertEquals(gateway.requests.first(), gateway.requests.last())
        assertEquals(ReceivingCommandStatus.Confirmed, viewModel.state.value.command)
        assertEquals(BigDecimal("0.0100"), viewModel.state.value.confirmedLot?.onHand)
        assertNull(storage.intent)
    }

    @Test
    fun rapidReplayTapsPersistOnePendingIntentAndDispatchOnlyOnce() = runTest {
        val gateway = FakeReceivingGateway().apply {
            submitResults += ReceivingSubmitResult.UnknownOutcome
            submitResults += ReceivingSubmitResult.UnknownOutcome
        }
        val storage = FakeReceivingMetadataStore()
        val viewModel = readyViewModel(gateway, storage)
        viewModel.submit()
        advanceUntilIdle()
        assertEquals(ReceivingCommandStatus.UnknownOutcome, viewModel.state.value.command)
        assertEquals(1, gateway.receiveCalls)

        storage.saveIntentStarted = CompletableDeferred()
        storage.saveIntentGate = CompletableDeferred()
        viewModel.retryUnknownOutcome()
        assertEquals(ReceivingCommandStatus.PersistingIntent, viewModel.state.value.command)
        viewModel.retryUnknownOutcome()
        runCurrent()
        assertEquals(1, gateway.receiveCalls)

        storage.saveIntentGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, gateway.receiveCalls)
        assertEquals(ReceivingCommandStatus.UnknownOutcome, viewModel.state.value.command)
    }

    @Test
    fun lateTerminalCleanupCannotOverwriteNewScopeAndRetainsUnknownIntent() = runTest {
        val gateway = FakeReceivingGateway().apply {
            submitResults += ReceivingSubmitResult.Confirmed(facts(request()))
        }
        val storage = FakeReceivingMetadataStore().apply {
            clearIntentStarted = CompletableDeferred()
            clearIntentGate = CompletableDeferred()
        }
        val viewModel = readyViewModel(gateway, storage)
        viewModel.submit()
        runCurrent()
        storage.clearIntentStarted?.await()

        val nextAuthority = authority(epoch = 2).copy(membershipId = "membership-2")
        viewModel.activate(nextAuthority, ConfirmedReceivingProduct(product, 2))
        assertEquals(ReceivingCommandStatus.Editing, viewModel.state.value.command)
        storage.clearIntentGate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(2L, viewModel.state.value.authorityEpoch)
        assertEquals(ReceivingCommandStatus.Editing, viewModel.state.value.command)
        assertNull(viewModel.state.value.confirmedLot)
        assertEquals(authority().scope, storage.intent?.scope)
        assertEquals(ReceivingIntentMetadataStatus.UnknownOutcome, storage.intent?.status)
    }

    @Test
    fun serverConfirmedLotIsTheOnlyDisplayedStockAndCanStartANewArrival() = runTest {
        val gateway = FakeReceivingGateway().apply {
            submitResults += ReceivingSubmitResult.Confirmed(
                facts(request(quantity = "2.500")).copy(available = BigDecimal("2.500"))
            )
        }
        val storage = FakeReceivingMetadataStore()
        val viewModel = readyViewModel(gateway, storage)
        viewModel.quantityChanged("2.500")
        viewModel.batchNumberChanged("LOT-REAL-7")
        advanceUntilIdle()
        viewModel.submit()
        advanceUntilIdle()

        val confirmed = viewModel.state.value.confirmedLot
        assertEquals(ReceivingCommandStatus.Confirmed, viewModel.state.value.command)
        assertEquals("server-lot-1", confirmed?.id)
        assertEquals(LocalDate.parse("2099-06-30"), confirmed?.expirationDate)
        assertEquals(BigDecimal("2.500"), confirmed?.onHand)
        assertEquals("KG", confirmed?.unit)
        assertNull(storage.intent)

        viewModel.startAnotherReceipt()
        advanceUntilIdle()
        assertEquals(ReceivingCommandStatus.Editing, viewModel.state.value.command)
        assertNull(viewModel.state.value.confirmedLot)
        assertEquals("", viewModel.state.value.batchNumber)
        assertEquals("", viewModel.state.value.quantityText)
    }

    @Test
    fun processReconstructionTurnsPendingIntoUnknownAndOldProductNeedsReconfirmation() = runTest {
        val oldScope = authority().scope
        val savedRequest = request()
        val storage = FakeReceivingMetadataStore().apply {
            drafts[oldScope] = ReceivingDraftMetadata(
                selectedProduct = product,
                warehouseId = warehouse.id,
                zoneId = zone.id,
                batchNumber = savedRequest.batchNumber,
                expirationDateText = savedRequest.expirationDate.toString(),
                quantityText = savedRequest.quantity.toPlainString(),
                unit = savedRequest.unit,
                temperatureReadingText = ""
            )
            intent = ReceivingIntentMetadata(
                scope = oldScope,
                idempotencyKey = "pending-arrival",
                request = savedRequest,
                status = ReceivingIntentMetadataStatus.Pending
            )
        }
        val gateway = FakeReceivingGateway().apply {
            submitResults += ReceivingSubmitResult.Confirmed(facts(savedRequest))
        }
        val viewModel = ReceivingViewModel(gateway, storage)

        viewModel.activate(authority(epoch = 2))
        advanceUntilIdle()
        assertEquals(ReceivingCommandStatus.UnknownOutcome, viewModel.state.value.command)
        assertEquals(null, viewModel.state.value.productVerifiedEpoch)
        assertEquals("0.0100", viewModel.state.value.quantityText)
        assertEquals("pending-arrival", storage.intent?.idempotencyKey)

        viewModel.submit()
        advanceUntilIdle()
        assertEquals(0, gateway.receiveCalls)
        assertEquals(ReceivingCommandStatus.UnknownOutcome, viewModel.state.value.command)

        viewModel.retryUnknownOutcome()
        advanceUntilIdle()
        assertEquals(1, gateway.receiveCalls)
        assertEquals("pending-arrival", gateway.keys.single())
        assertEquals(ReceivingCommandStatus.Confirmed, viewModel.state.value.command)
    }

    @Test
    fun logoutClearsVisibleWorkButSameScopeReauthorizationCanRecoverFrozenIntent() = runTest {
        val storage = FakeReceivingMetadataStore()
        val deferred = CompletableDeferred<ReceivingSubmitResult>()
        val gateway = FakeReceivingGateway().apply { submitDeferred = deferred }
        val viewModel = readyViewModel(gateway, storage)
        viewModel.submit()
        runCurrent()
        assertEquals(1, gateway.receiveCalls)
        val intentKey = storage.intent?.idempotencyKey

        viewModel.invalidate()
        assertNull(viewModel.state.value.product)
        assertNull(viewModel.state.value.confirmedLot)
        deferred.complete(ReceivingSubmitResult.Confirmed(facts(request())))
        advanceUntilIdle()
        assertEquals(ReceivingCommandStatus.Editing, viewModel.state.value.command)
        assertNull(viewModel.state.value.confirmedLot)
        assertEquals(intentKey, storage.intent?.idempotencyKey)

        viewModel.activate(authority(epoch = 2), ConfirmedReceivingProduct(product, 2))
        advanceUntilIdle()
        assertEquals(ReceivingCommandStatus.UnknownOutcome, viewModel.state.value.command)
        assertEquals(intentKey, storage.intent?.idempotencyKey)
    }

    @Test
    fun warehouseAndReceivePermissionsAreIndependentAndChoicesMustComeFromServer() = runTest {
        val gateway = FakeReceivingGateway()
        val storage = FakeReceivingMetadataStore()
        val viewModel = ReceivingViewModel(gateway, storage)
        viewModel.activate(
            authority(permissions = setOf("warehouse.read")),
            ConfirmedReceivingProduct(product, 1)
        )
        advanceUntilIdle()
        assertEquals(1, gateway.warehouseCalls)
        assertFalse(viewModel.state.value.canReceive)
        viewModel.submit()
        advanceUntilIdle()
        assertEquals(0, gateway.receiveCalls)

        viewModel.selectWarehouse("invented-warehouse")
        advanceUntilIdle()
        assertNull(viewModel.state.value.selectedWarehouseId)
    }

    @Test
    fun lateResponseFromInvalidatedAuthorityCannotShowAReceiptAsConfirmed() = runTest {
        val deferred = CompletableDeferred<ReceivingSubmitResult>()
        val gateway = FakeReceivingGateway().apply { submitDeferred = deferred }
        val storage = FakeReceivingMetadataStore()
        val viewModel = readyViewModel(gateway, storage)
        viewModel.submit()
        runCurrent()
        assertEquals(1, gateway.receiveCalls)
        viewModel.activate(authority(epoch = 2), ConfirmedReceivingProduct(product, 2))
        deferred.complete(ReceivingSubmitResult.Confirmed(facts(request())))
        advanceUntilIdle()

        assertNull(viewModel.state.value.confirmedLot)
        assertEquals(ReceivingCommandStatus.UnknownOutcome, viewModel.state.value.command)
        assertEquals(ReceivingIntentMetadataStatus.UnknownOutcome, storage.intent?.status)
    }

    private suspend fun TestScope.readyViewModel(
        gateway: FakeReceivingGateway,
        storage: FakeReceivingMetadataStore
    ): ReceivingViewModel {
        val viewModel = ReceivingViewModel(gateway, storage)
        viewModel.activate(authority(), ConfirmedReceivingProduct(product, 1))
        advanceUntilIdle()
        viewModel.selectWarehouse(warehouse.id)
        advanceUntilIdle()
        viewModel.selectZone(zone.id)
        viewModel.batchNumberChanged("LOT-REAL-7")
        viewModel.expirationDateChanged("2099-06-30")
        viewModel.quantityChanged("0.0100")
        viewModel.unitChanged("KG")
        advanceUntilIdle()
        return viewModel
    }

    private fun authority(
        epoch: Long = 1,
        permissions: Set<String> = setOf("warehouse.read", "inventory.receive")
    ) = ReceivingAuthority(
        userId = "user-1",
        tenantId = "tenant-1",
        workspaceId = "workspace-1",
        membershipId = "membership-1",
        permissions = permissions,
        authorityEpoch = epoch
    )

    private fun request(quantity: String = "0.0100", batch: String = "LOT-REAL-7") =
        InboundReceiptRequest(
            warehouseId = warehouse.id,
            zoneId = zone.id,
            catalogItemId = product.catalogItemId,
            skuId = product.skuId,
            batchNumber = batch,
            expirationDate = LocalDate.parse("2099-06-30"),
            quantity = BigDecimal(quantity),
            unit = "KG",
            temperatureReading = BigDecimal("-1.250")
        )

    private fun facts(request: InboundReceiptRequest) = ReceivedLotFacts(
        id = "server-lot-1",
        warehouseId = request.warehouseId,
        zoneId = request.zoneId,
        catalogItemId = request.catalogItemId,
        skuId = request.skuId,
        batchNumber = request.batchNumber,
        expirationDate = request.expirationDate,
        receivedAt = "2026-09-30T15:00:00Z",
        onHand = request.quantity,
        reserved = BigDecimal.ZERO,
        available = request.quantity,
        unit = request.unit,
        status = "AVAILABLE",
        version = 1
    )

    private class FakeReceivingGateway : ReceivingGateway {
        var warehouseCalls = 0
        var receiveCalls = 0
        var submitDeferred: CompletableDeferred<ReceivingSubmitResult>? = null
        val submitResults = mutableListOf<ReceivingSubmitResult>()
        val requests = mutableListOf<InboundReceiptRequest>()
        val keys = mutableListOf<String>()

        override suspend fun warehouses(authority: ReceivingAuthority): ReceivingLookupResult {
            warehouseCalls++
            return ReceivingLookupResult.Warehouses(listOf(warehouse))
        }

        override suspend fun zones(
            warehouseId: String,
            authority: ReceivingAuthority
        ): ReceivingLookupResult = ReceivingLookupResult.Zones(
            listOf(zone.copy(warehouseId = warehouseId))
        )

        override suspend fun receive(
            request: InboundReceiptRequest,
            idempotencyKey: String,
            authority: ReceivingAuthority
        ): ReceivingSubmitResult {
            receiveCalls++
            requests += request
            keys += idempotencyKey
            submitDeferred?.let { return it.await() }
            return submitResults.removeFirstOrNull() ?: ReceivingSubmitResult.UnknownOutcome
        }
    }

    private class FakeReceivingMetadataStore(private val available: Boolean = true) :
        ReceivingMetadataStore {
        val drafts = mutableMapOf<ReceivingScopeIdentity, ReceivingDraftMetadata>()
        var intent: ReceivingIntentMetadata? = null
        var saveIntentStarted: CompletableDeferred<Unit>? = null
        var saveIntentGate: CompletableDeferred<Unit>? = null
        var clearIntentStarted: CompletableDeferred<Unit>? = null
        var clearIntentGate: CompletableDeferred<Unit>? = null

        override suspend fun loadDraft(
            scope: ReceivingScopeIdentity
        ): ReceivingMetadataRead<ReceivingDraftMetadata> = if (available) {
            ReceivingMetadataRead.Available(drafts[scope])
        } else {
            ReceivingMetadataRead.Unavailable
        }

        override suspend fun saveDraft(
            scope: ReceivingScopeIdentity,
            draft: ReceivingDraftMetadata
        ): ReceivingMetadataWrite = if (available) {
            drafts[scope] = draft
            ReceivingMetadataWrite.Saved
        } else {
            ReceivingMetadataWrite.Unavailable
        }

        override suspend fun loadIntent(
            scope: ReceivingScopeIdentity
        ): ReceivingMetadataRead<ReceivingIntentMetadata> = if (available) {
            ReceivingMetadataRead.Available(intent?.takeIf { it.scope == scope })
        } else {
            ReceivingMetadataRead.Unavailable
        }

        override suspend fun saveIntent(intent: ReceivingIntentMetadata): ReceivingMetadataWrite {
            saveIntentStarted?.complete(Unit)
            saveIntentGate?.await()
            return if (available) {
                this.intent = intent
                ReceivingMetadataWrite.Saved
            } else {
                ReceivingMetadataWrite.Unavailable
            }
        }

        override suspend fun clearIntent(
            scope: ReceivingScopeIdentity,
            idempotencyKey: String
        ): ReceivingMetadataWrite {
            clearIntentStarted?.complete(Unit)
            clearIntentGate?.await()
            return if (available) {
                if (intent?.scope == scope &&
                    intent?.idempotencyKey == idempotencyKey
                ) {
                    intent = null
                }
                ReceivingMetadataWrite.Saved
            } else {
                ReceivingMetadataWrite.Unavailable
            }
        }
    }

    private companion object {
        val product = ReceivingProductReference(
            catalogItemId = "CAT-0017",
            skuId = "8f060338-9547-4c09-9fa1-2e048bbc2a03",
            displayName = "Confirmed product",
            skuCode = "SKU-17",
            unit = "KG"
        )
        val warehouse = ReceivingWarehouseChoice("warehouse-1", "WH-1", "Warehouse 1", "ACTIVE")
        val zone = ReceivingZoneChoice("zone-1", "warehouse-1", "ZONE-1", "Zone 1", "ACTIVE")
    }
}
