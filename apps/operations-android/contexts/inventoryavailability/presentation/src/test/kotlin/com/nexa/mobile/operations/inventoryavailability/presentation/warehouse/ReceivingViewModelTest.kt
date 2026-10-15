package com.nexa.mobile.operations.inventoryavailability.presentation.warehouse

import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundReceiptRequest
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingAuthority
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingCatalogCandidate
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingCatalogFilterChoice
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingCatalogFilterKind
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingCatalogFilterPage
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingCatalogOutcome
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingCatalogPage
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingDraftMetadata
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingEvidenceCandidate
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingEvidenceResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingIntentMetadata
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingIntentMetadataStatus
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingLookupResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingMetadataRead
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingMetadataWrite
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingScopeIdentity
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingSubmitResult
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.ReceivingGateway
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.ReceivingMetadataStore
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.ReceivingProductCatalogGateway
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.WarehouseEvidenceFileCandidate
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.WarehouseEvidenceFileSelectionPort
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.WarehouseEvidenceSelectionCoordinator
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ConfirmedReceivingProduct
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ReceivedLotFacts
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ReceivingEvidenceObject
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ReceivingProductReference
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ReceivingWarehouseChoice
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ReceivingZoneChoice
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
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
    fun receivingPickerUsesServerFiltersPagesAndConfirmedCatalogUnit() = runTest {
        val brand = ReceivingCatalogFilterChoice("brand-1", "Brand One")
        val category = ReceivingCatalogFilterChoice("category-1", "Cold goods")
        val first = ReceivingCatalogCandidate(
            "CAT-0001",
            "Milk",
            "1 L",
            "MILK-1L",
            brand.name,
            category.name
        )
        val second = ReceivingCatalogCandidate(
            "CAT-0002",
            "Cream",
            "500 mL",
            "CREAM-500",
            brand.name,
            category.name
        )
        val catalog = FakeReceivingProductCatalogGateway().apply {
            filterResults += ReceivingCatalogOutcome.Success(
                ReceivingCatalogFilterPage(listOf(brand), page = 0, size = 100, total = 101)
            )
            filterResults += ReceivingCatalogOutcome.Success(
                ReceivingCatalogFilterPage(listOf(brand.copy(id = "brand-2")), 1, 100, 101)
            )
            filterResults += ReceivingCatalogOutcome.Success(
                ReceivingCatalogFilterPage(listOf(category), page = 0, size = 100, total = 1)
            )
            searchResults += ReceivingCatalogOutcome.Success(
                ReceivingCatalogPage(listOf(first), nextPageKey = "1")
            )
            searchResults += ReceivingCatalogOutcome.Success(
                ReceivingCatalogPage(listOf(second), nextPageKey = null)
            )
            detailResults[first.catalogItemId] = ReceivingCatalogOutcome.Success(
                ReceivingProductReference(
                    first.catalogItemId,
                    null,
                    first.itemName,
                    first.skuCode,
                    "L"
                )
            )
        }
        val store = FakeReceivingMetadataStore()
        val viewModel = ReceivingViewModel(
            FakeReceivingGateway(),
            store,
            productCatalog = catalog
        )
        viewModel.activate(authority(permissions = setOf("inventory.receive", "catalog.read")))
        advanceUntilIdle()

        viewModel.openProductPicker()
        viewModel.openProductFilterOptions(ReceivingCatalogFilterKind.Brand)
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.productPicker.nextFilterPage)
        viewModel.loadProductFilterOptions(
            ReceivingCatalogFilterKind.Brand,
            "",
            page = 1,
            append = true
        )
        advanceUntilIdle()
        assertEquals(2, viewModel.state.value.productPicker.filterOptions.size)
        viewModel.selectProductFilter(ReceivingCatalogFilterKind.Brand, brand)

        viewModel.openProductFilterOptions(ReceivingCatalogFilterKind.Category)
        advanceUntilIdle()
        viewModel.selectProductFilter(ReceivingCatalogFilterKind.Category, category)
        viewModel.productPickerQueryChanged("  milk  ")
        viewModel.searchProductCatalog()
        advanceUntilIdle()
        assertEquals("milk", catalog.searchRequests.first().query)
        assertEquals(brand.name, catalog.searchRequests.first().brandName)
        assertEquals(category.name, catalog.searchRequests.first().categoryName)
        assertNull(catalog.searchRequests.first().pageKey)
        assertEquals("1", viewModel.state.value.productPicker.nextPageKey)

        viewModel.loadMoreProductCatalog()
        advanceUntilIdle()
        assertEquals("1", catalog.searchRequests.last().pageKey)
        assertEquals(
            listOf("CAT-0001", "CAT-0002"),
            viewModel.state.value.productPicker
                .candidates.map { it.catalogItemId }
        )

        viewModel.selectProductCatalogCandidate(first.catalogItemId)
        advanceUntilIdle()
        assertEquals(first.catalogItemId, viewModel.state.value.product?.catalogItemId)
        assertEquals("L", viewModel.state.value.product?.unit)
        assertEquals("L", viewModel.state.value.unit)
        assertEquals(1L, viewModel.state.value.productVerifiedEpoch)
        assertFalse(viewModel.state.value.productPicker.isOpen)
        assertEquals(
            first.catalogItemId,
            store.drafts.values.single().selectedProduct?.catalogItemId
        )
    }

    @Test
    fun receivingPickerRejectsProductDetailWithoutConfirmedUnit() = runTest {
        val candidate = ReceivingCatalogCandidate(
            "CAT-0003",
            "Coffee",
            "250 g",
            "COFFEE-250",
            null,
            null
        )
        val catalog = FakeReceivingProductCatalogGateway().apply {
            searchResults += ReceivingCatalogOutcome.Success(
                ReceivingCatalogPage(listOf(candidate), nextPageKey = null)
            )
            detailResults[candidate.catalogItemId] = ReceivingCatalogOutcome.Success(
                ReceivingProductReference(
                    candidate.catalogItemId,
                    null,
                    candidate.itemName,
                    candidate.skuCode,
                    ""
                )
            )
        }
        val viewModel = ReceivingViewModel(
            FakeReceivingGateway(),
            FakeReceivingMetadataStore(),
            productCatalog = catalog
        )
        viewModel.activate(authority(permissions = setOf("inventory.receive", "catalog.read")))
        advanceUntilIdle()
        viewModel.openProductPicker()
        viewModel.productPickerQueryChanged("coffee")
        viewModel.searchProductCatalog()
        advanceUntilIdle()
        viewModel.selectProductCatalogCandidate(candidate.catalogItemId)
        advanceUntilIdle()

        assertNull(viewModel.state.value.product)
        assertNull(viewModel.state.value.productVerifiedEpoch)
        assertEquals(
            ReceivingCatalogSelectionNotice.ServiceUnavailable,
            viewModel.state.value.productPicker.selectionNotice
        )
        assertTrue(viewModel.state.value.productPicker.isOpen)
    }

    @Test
    fun receivingPickerClearsResultsWhenServerRevokesCatalogRead() = runTest {
        val brand = ReceivingCatalogFilterChoice("brand-1", "Brand One")
        val candidate = ReceivingCatalogCandidate(
            "CAT-0004",
            "Tea",
            "100 g",
            "TEA-100",
            brand.name,
            null
        )
        val catalog = FakeReceivingProductCatalogGateway().apply {
            searchResults += ReceivingCatalogOutcome.Success(
                ReceivingCatalogPage(listOf(candidate), nextPageKey = "1")
            )
            searchResults += ReceivingCatalogOutcome.PermissionDenied
            filterResults += ReceivingCatalogOutcome.Success(
                ReceivingCatalogFilterPage(listOf(brand), page = 0, size = 100, total = 101)
            )
            filterResults += ReceivingCatalogOutcome.PermissionDenied
        }
        val viewModel = ReceivingViewModel(
            FakeReceivingGateway(),
            FakeReceivingMetadataStore(),
            productCatalog = catalog
        )
        viewModel.activate(authority(permissions = setOf("inventory.receive", "catalog.read")))
        advanceUntilIdle()
        viewModel.openProductPicker()
        viewModel.productPickerQueryChanged("tea")
        viewModel.searchProductCatalog()
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.productPicker.candidates.size)
        viewModel.loadMoreProductCatalog()
        advanceUntilIdle()
        assertEquals(
            ReceivingCatalogSearchStatus.PermissionDenied,
            viewModel.state.value.productPicker.searchStatus
        )
        assertTrue(viewModel.state.value.productPicker.candidates.isEmpty())
        assertNull(viewModel.state.value.productPicker.nextPageKey)

        viewModel.openProductFilterOptions(ReceivingCatalogFilterKind.Brand)
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.productPicker.filterOptions.size)
        viewModel.loadProductFilterOptions(
            ReceivingCatalogFilterKind.Brand,
            "",
            page = 1,
            append = true
        )
        advanceUntilIdle()
        assertEquals(
            ReceivingCatalogFilterStatus.PermissionDenied,
            viewModel.state.value.productPicker.filterStatus
        )
        assertTrue(viewModel.state.value.productPicker.filterOptions.isEmpty())
        assertNull(viewModel.state.value.productPicker.nextFilterPage)
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
        assertEquals(LocalDate.parse("2026-09-29"), frozen.request.productionDate)
        assertEquals(Instant.parse("2026-09-30T15:00:00Z"), frozen.request.receivedAt)
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
    fun receiptCarriesOnlyPhotoVerifiedAvailableForTheSelectedWarehouse() = runTest {
        val evidenceId = "b8c24a46-57d9-4f64-8fa7-6a641b413501"
        val gateway = FakeReceivingGateway().apply {
            uploadEvidenceResult = ReceivingEvidenceResult.Loaded(
                ReceivingEvidenceObject(
                    id = evidenceId,
                    subjectType = "WAREHOUSE",
                    subjectId = warehouse.id,
                    lifecycleStatus = "QUARANTINED",
                    declaredContentType = "image/jpeg",
                    byteSize = 4
                )
            )
            evidenceStatusResult = ReceivingEvidenceResult.Loaded(
                ReceivingEvidenceObject(
                    id = evidenceId,
                    subjectType = "WAREHOUSE",
                    subjectId = warehouse.id,
                    lifecycleStatus = "AVAILABLE",
                    declaredContentType = "image/jpeg",
                    byteSize = 4
                )
            )
            submitResults += ReceivingSubmitResult.Confirmed(facts(request()))
        }
        val storage = FakeReceivingMetadataStore()
        val viewModel = readyViewModel(
            gateway,
            storage,
            permissions = setOf(
                "warehouse.read",
                "inventory.receive",
                "document.upload",
                "document.read"
            )
        )
        viewModel.temperatureReadingChanged("-1.250")
        advanceUntilIdle()
        val selection = viewModel.temperatureEvidenceSelectionContext()
            ?: error("active warehouse should permit photo selection")
        val file = File.createTempFile("receiving-evidence", ".jpg")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            viewModel.uploadTemperatureEvidence(
                ReceivingEvidenceCandidate(
                    file = file,
                    originalFilename = "temperature.jpg",
                    declaredContentType = "image/jpeg",
                    byteSize = file.length(),
                    checksumSha256 = "a".repeat(64)
                ),
                selection = selection
            )
            advanceUntilIdle()

            assertEquals(warehouse.id, gateway.uploadedWarehouseId)
            assertEquals(
                ReceivingEvidenceStatus.Available,
                viewModel.state.value.temperatureEvidenceStatus
            )
            assertEquals(evidenceId, viewModel.state.value.temperatureEvidenceObjectId)
            assertEquals(evidenceId, storage.drafts.values.single().temperatureEvidenceObjectId)
            viewModel.submit()
            advanceUntilIdle()
            assertEquals(evidenceId, gateway.requests.single().temperatureEvidenceObjectId)
        } finally {
            file.delete()
        }
    }

    @Test
    fun returnedSelectionFromAnotherScopeIsRejectedBeforeCopyOrUpload() = runTest {
        val files = MemoryWarehouseEvidenceSelection()
        val gateway = FakeReceivingGateway()
        val viewModel = readyViewModel(
            gateway,
            FakeReceivingMetadataStore(),
            permissions = evidencePermissions,
            files = files
        )
        val selection = viewModel.temperatureEvidenceSelectionContext()
            ?: error("active warehouse should permit photo selection")

        viewModel.activate(
            authority(epoch = 2, membershipId = "membership-2", permissions = evidencePermissions),
            ConfirmedReceivingProduct(product, 2)
        )
        advanceUntilIdle()

        assertFalse(viewModel.uploadReturnedTemperatureEvidence(selection, "content://picker/a"))
        assertEquals(0, files.prepareCalls)
        assertEquals(0, gateway.temperatureEvidenceUploadCalls)
    }

    @Test
    fun sameScopeReactivationRequiresFreshUploadCapabilityBeforeCopy() = runTest {
        val files = MemoryWarehouseEvidenceSelection()
        val gateway = FakeReceivingGateway()
        val viewModel = readyViewModel(
            gateway,
            FakeReceivingMetadataStore(),
            permissions = evidencePermissions,
            files = files
        )
        val selection = viewModel.temperatureEvidenceSelectionContext()
            ?: error("active warehouse should permit photo selection")

        viewModel.activate(
            authority(epoch = 2, permissions = setOf("warehouse.read", "inventory.receive")),
            ConfirmedReceivingProduct(product, 2)
        )
        advanceUntilIdle()

        assertFalse(viewModel.uploadReturnedTemperatureEvidence(selection, "content://picker/a"))
        assertEquals(0, files.prepareCalls)
        assertEquals(0, gateway.temperatureEvidenceUploadCalls)
    }

    @Test
    fun failedPrivateCopyNeverUploadsEvidence() = runTest {
        val files = MemoryWarehouseEvidenceSelection().apply { failPreparation = true }
        val gateway = FakeReceivingGateway()
        val viewModel = readyViewModel(
            gateway,
            FakeReceivingMetadataStore(),
            permissions = evidencePermissions,
            files = files
        )
        val selection = viewModel.temperatureEvidenceSelectionContext()
            ?: error("active warehouse should permit photo selection")

        assertFalse(viewModel.uploadReturnedTemperatureEvidence(selection, "content://picker/a"))

        assertEquals(1, files.prepareCalls)
        assertEquals(0, gateway.temperatureEvidenceUploadCalls)
    }

    @Test
    fun cancellationDuringEvidenceUploadDeletesPrivatePickerCopy() = runTest {
        val files = MemoryWarehouseEvidenceSelection()
        val gateway = FakeReceivingGateway().apply {
            uploadEvidenceFailure = CancellationException("cancelled")
        }
        val viewModel = readyViewModel(
            gateway,
            FakeReceivingMetadataStore(),
            permissions = evidencePermissions,
            files = files
        )
        val selection = viewModel.temperatureEvidenceSelectionContext()
            ?: error("active warehouse should permit photo selection")

        val failure = runCatching {
            viewModel.uploadReturnedTemperatureEvidence(selection, "content://picker/a")
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(1, gateway.temperatureEvidenceUploadCalls)
        assertTrue(files.discarded)
        assertFalse(files.lastFile?.exists() ?: true)
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
        storage: FakeReceivingMetadataStore,
        permissions: Set<String> = setOf("warehouse.read", "inventory.receive"),
        files: WarehouseEvidenceFileSelectionPort? = null
    ): ReceivingViewModel {
        val viewModel = ReceivingViewModel(
            gateway,
            storage,
            files?.let(::WarehouseEvidenceSelectionCoordinator)
        )
        viewModel.activate(
            authority(permissions = permissions),
            ConfirmedReceivingProduct(product, 1)
        )
        advanceUntilIdle()
        viewModel.selectWarehouse(warehouse.id)
        advanceUntilIdle()
        viewModel.selectZone(zone.id)
        viewModel.batchNumberChanged("LOT-REAL-7")
        viewModel.expirationDateChanged("2099-06-30")
        viewModel.productionDateChanged("2026-09-29")
        viewModel.receivedAtChanged("2026-09-30T15:00:00Z")
        viewModel.quantityChanged("0.0100")
        advanceUntilIdle()
        return viewModel
    }

    private fun authority(
        epoch: Long = 1,
        permissions: Set<String> = setOf("warehouse.read", "inventory.receive"),
        membershipId: String = "membership-1"
    ) = ReceivingAuthority(
        userId = "user-1",
        tenantId = "tenant-1",
        workspaceId = "workspace-1",
        membershipId = membershipId,
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
            productionDate = LocalDate.parse("2026-09-29"),
            receivedAt = Instant.parse("2026-09-30T15:00:00Z"),
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
        productionDate = request.productionDate,
        receivedAt = request.receivedAt?.toString() ?: "2026-09-30T15:00:00Z",
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
        var uploadEvidenceResult: ReceivingEvidenceResult =
            ReceivingEvidenceResult.ServiceUnavailable
        var evidenceStatusResult: ReceivingEvidenceResult =
            ReceivingEvidenceResult.ServiceUnavailable
        var uploadedWarehouseId: String? = null
        var temperatureEvidenceUploadCalls = 0
        var uploadEvidenceFailure: Throwable? = null
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

        override suspend fun uploadTemperatureEvidence(
            warehouseId: String,
            candidate: ReceivingEvidenceCandidate,
            idempotencyKey: String,
            authority: ReceivingAuthority
        ): ReceivingEvidenceResult {
            temperatureEvidenceUploadCalls++
            uploadedWarehouseId = warehouseId
            uploadEvidenceFailure?.let { throw it }
            return uploadEvidenceResult
        }

        override suspend fun temperatureEvidenceStatus(
            evidenceId: String,
            warehouseId: String,
            authority: ReceivingAuthority
        ): ReceivingEvidenceResult = evidenceStatusResult
    }

    private class FakeReceivingProductCatalogGateway : ReceivingProductCatalogGateway {
        data class SearchRequest(
            val query: String,
            val brandName: String?,
            val categoryName: String?,
            val pageKey: String?
        )

        val searchRequests = mutableListOf<SearchRequest>()
        val searchResults = mutableListOf<ReceivingCatalogOutcome<ReceivingCatalogPage>>()
        val filterResults = mutableListOf<ReceivingCatalogOutcome<ReceivingCatalogFilterPage>>()
        val detailResults = mutableMapOf<
            String,
            ReceivingCatalogOutcome<ReceivingProductReference>
            >()

        override suspend fun search(
            query: String,
            brandName: String?,
            categoryName: String?,
            pageKey: String?,
            authority: ReceivingAuthority
        ): ReceivingCatalogOutcome<ReceivingCatalogPage> {
            searchRequests += SearchRequest(query, brandName, categoryName, pageKey)
            return searchResults.removeFirstOrNull()
                ?: ReceivingCatalogOutcome.ServiceUnavailable
        }

        override suspend fun filterOptions(
            kind: ReceivingCatalogFilterKind,
            search: String,
            page: Int,
            authority: ReceivingAuthority
        ): ReceivingCatalogOutcome<ReceivingCatalogFilterPage> =
            filterResults.removeFirstOrNull() ?: ReceivingCatalogOutcome.ServiceUnavailable

        override suspend fun detail(
            candidate: ReceivingCatalogCandidate,
            authority: ReceivingAuthority
        ): ReceivingCatalogOutcome<ReceivingProductReference> =
            detailResults[candidate.catalogItemId]
                ?: ReceivingCatalogOutcome.CandidateUnavailable
    }

    private class MemoryWarehouseEvidenceSelection : WarehouseEvidenceFileSelectionPort {
        var prepareCalls = 0
        var failPreparation = false
        var discarded = false
        var lastFile: File? = null

        override suspend fun prepare(
            sourceUri: String,
            scopeKey: String
        ): WarehouseEvidenceFileCandidate? {
            prepareCalls++
            if (failPreparation) return null
            val file = File.createTempFile("receiving-picker", ".jpg")
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            lastFile = file
            return WarehouseEvidenceFileCandidate(
                file,
                "temperature.jpg",
                "image/jpeg",
                file.length(),
                "a".repeat(64)
            )
        }

        override fun discard(candidate: WarehouseEvidenceFileCandidate) {
            discarded = true
            candidate.file.delete()
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
        val evidencePermissions = setOf(
            "warehouse.read",
            "inventory.receive",
            "document.upload",
            "document.read"
        )
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
