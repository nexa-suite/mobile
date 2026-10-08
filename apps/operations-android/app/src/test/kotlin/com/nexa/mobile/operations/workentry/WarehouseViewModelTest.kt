package com.nexa.mobile.operations.workentry

import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.CandidateConfirmationResult
import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.ProductSearchResult
import com.nexa.mobile.operations.catalogcommercialpolicy.application.warehouse.WarehouseGateway
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.CatalogOperationsContext
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ConfirmedSkuProjection
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ProductCandidate
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse.ProductSearchStatus
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse.ProductSearchUiState
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.ActiveOperationsContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WarehouseViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun scannerRouteReturnsToWorkEntryAndManualSearchRemainsAvailable() {
        val gateway = FakeWarehouseGateway()
        val viewModel = WarehouseViewModel(gateway)
        viewModel.enterOperations(context(1), TaskVisibilityHint.Available)

        viewModel.openScanner()

        assertEquals(WarehouseRoute.Scanner, viewModel.state.value.route)
        assertNull(viewModel.state.value.search)
        viewModel.back()
        assertEquals(WarehouseRoute.WorkEntry, viewModel.state.value.route)
        viewModel.openProductSearch()
        assertEquals(WarehouseRoute.ProductSearch, viewModel.state.value.route)
        assertEquals(0, gateway.searchCalls)
        assertEquals(0, gateway.confirmationCalls)
    }

    @Test
    fun searchIsExplicitAndOneCandidateStillNeedsConfirmation() = runTest {
        val gateway = FakeWarehouseGateway().apply {
            searchResult = ProductSearchResult.Page(listOf(candidate), null)
        }
        val viewModel = WarehouseViewModel(gateway)
        viewModel.enterOperations(context(1), TaskVisibilityHint.Available)
        viewModel.openProductSearch()
        viewModel.queryChanged("  gouda  ")

        assertEquals(0, gateway.searchCalls)
        viewModel.submitSearch()
        advanceUntilIdle()

        assertEquals(1, gateway.searchCalls)
        assertEquals("gouda", gateway.lastQuery)
        assertEquals(ProductSearchStatus.OneCandidate, viewModel.state.value.search?.status)
        assertEquals(WarehouseRoute.ProductSearch, viewModel.state.value.route)
        assertNull(viewModel.state.value.confirmedSku)
    }

    @Test
    fun confirmedSkuReturnsToSameSearchWhileEpochIsCurrent() = runTest {
        val gateway = FakeWarehouseGateway().apply {
            searchResult = ProductSearchResult.Page(listOf(candidate), null)
            confirmationResult = CandidateConfirmationResult.Confirmed(confirmed(candidate, 1))
        }
        val viewModel = WarehouseViewModel(gateway)
        viewModel.enterOperations(context(1), TaskVisibilityHint.Available)
        viewModel.openProductSearch()
        viewModel.queryChanged("gouda")
        viewModel.submitSearch()
        advanceUntilIdle()
        viewModel.selectCandidate(candidate.key)
        advanceUntilIdle()

        assertEquals(WarehouseRoute.ConfirmedSku, viewModel.state.value.route)
        assertEquals(candidate.sku, viewModel.state.value.confirmedSku?.sku)
        viewModel.back()
        assertEquals(WarehouseRoute.ProductSearch, viewModel.state.value.route)
        assertEquals("gouda", viewModel.state.value.search?.query)
        assertEquals(ProductSearchStatus.OneCandidate, viewModel.state.value.search?.status)
        assertNull(viewModel.state.value.confirmedSku)
    }

    @Test
    fun lateSearchFromOldContextCannotReplaceNewContextState() = runTest {
        val deferred = CompletableDeferred<ProductSearchResult>()
        val gateway = FakeWarehouseGateway().apply { searchDeferred = deferred }
        val viewModel = WarehouseViewModel(gateway)
        viewModel.enterOperations(context(1), TaskVisibilityHint.Available)
        viewModel.openProductSearch()
        viewModel.queryChanged("gouda")
        viewModel.submitSearch()
        runCurrent()

        viewModel.authorityReplaced(context(2), TaskVisibilityHint.Available)
        deferred.complete(ProductSearchResult.Page(listOf(candidate), null))
        advanceUntilIdle()

        assertEquals(2L, viewModel.state.value.authorityEpoch)
        assertEquals(WarehouseRoute.WorkEntry, viewModel.state.value.route)
        assertEquals(2L, viewModel.state.value.activeContext?.authorityEpoch)
        assertNull(viewModel.state.value.search)
        assertTrue(viewModel.state.value.toString().contains("authorityEpoch=2"))
    }

    @Test
    fun unknownConfirmationDoesNotCreateConfirmedSkuAndContextInvalidationClearsState() = runTest {
        val gateway = FakeWarehouseGateway().apply {
            searchResult = ProductSearchResult.Page(listOf(candidate), null)
            confirmationResult = CandidateConfirmationResult.UnknownOutcome
        }
        val viewModel = WarehouseViewModel(gateway)
        viewModel.enterOperations(context(1), TaskVisibilityHint.Available)
        viewModel.openProductSearch()
        viewModel.queryChanged("gouda")
        viewModel.submitSearch()
        advanceUntilIdle()
        viewModel.selectCandidate(candidate.key)
        advanceUntilIdle()

        assertEquals(WarehouseRoute.ProductSearch, viewModel.state.value.route)
        assertEquals(ProductSearchStatus.CandidateUnavailable, viewModel.state.value.search?.status)
        assertNull(viewModel.state.value.confirmedSku)
        viewModel.contextInvalidated()
        assertEquals(WorkEntryStatus.ContextInvalidated, viewModel.state.value.workEntryStatus)
        assertEquals(1L, viewModel.state.value.invalidatedFromAuthorityEpoch)
        assertNull(viewModel.state.value.activeContext)
        assertNull(viewModel.state.value.search)
        assertNull(viewModel.state.value.confirmedSku)
    }

    @Test
    fun unknownPermissionProjectionKeepsTaskClosedWithoutClaimingDenial() {
        val viewModel = WarehouseViewModel(FakeWarehouseGateway())
        viewModel.enterOperations(context(1), TaskVisibilityHint.Unknown)
        viewModel.openProductSearch()
        assertEquals(WorkEntryStatus.PermissionUnknown, viewModel.state.value.workEntryStatus)
        assertEquals(WarehouseRoute.WorkEntry, viewModel.state.value.route)
        assertNull(viewModel.state.value.search)
    }

    @Test
    fun blankAndOversizedQueriesNeverReachGateway() = runTest {
        val gateway = FakeWarehouseGateway()
        val viewModel = readySearch(gateway)
        for (query in listOf("   ", "ñ".repeat(121))) {
            viewModel.queryChanged(query)
            viewModel.submitSearch()
            advanceUntilIdle()
            assertEquals(ProductSearchStatus.InvalidQuery, viewModel.state.value.search?.status)
        }
        assertEquals(0, gateway.searchCalls)
    }

    @Test
    fun emptyAndMultipleCandidatesStayUnconfirmed() = runTest {
        val gateway = FakeWarehouseGateway()
        val viewModel = readySearch(gateway)
        viewModel.submitSearch()
        advanceUntilIdle()
        assertEquals(ProductSearchStatus.Empty, viewModel.state.value.search?.status)
        gateway.searchResult = ProductSearchResult.Page(
            listOf(candidate, candidate.copy(key = "second")),
            null
        )
        viewModel.submitSearch()
        advanceUntilIdle()
        assertEquals(ProductSearchStatus.MultipleCandidates, viewModel.state.value.search?.status)
        assertNull(viewModel.state.value.confirmedSku)
        assertEquals(0, gateway.confirmationCalls)
    }

    @Test
    fun pagesAppendWithoutDuplicatesAndRepeatedTapRequestsOnlyOnce() = runTest {
        val gateway = FakeWarehouseGateway().apply {
            searchResult = ProductSearchResult.Page(listOf(candidate), "1")
        }
        val viewModel = readySearch(gateway)
        viewModel.submitSearch()
        viewModel.submitSearch()
        advanceUntilIdle()
        assertEquals(listOf<String?>(null), gateway.pageKeys)
        val deferred = CompletableDeferred<ProductSearchResult>()
        gateway.searchDeferred = deferred
        viewModel.loadMore()
        viewModel.loadMore()
        runCurrent()
        assertEquals(listOf(null, "1"), gateway.pageKeys)
        val second = candidate.copy(key = "second", sku = "SKU-DEMO-002")
        deferred.complete(ProductSearchResult.Page(listOf(candidate, second), null))
        advanceUntilIdle()
        assertEquals(listOf(candidate, second), viewModel.state.value.search?.candidates)
        assertNull(viewModel.state.value.search?.nextPageKey)
    }

    @Test
    fun retainedCandidateCanConfirmAfterLoadingMoreFails() = runTest {
        val gateway = FakeWarehouseGateway().apply {
            searchResult = ProductSearchResult.Page(listOf(candidate), "1")
            confirmationResult = CandidateConfirmationResult.Confirmed(
                confirmed(candidate, 1).copy(sku = "FRESH-SKU")
            )
        }
        val viewModel = readySearch(gateway)
        viewModel.submitSearch()
        advanceUntilIdle()

        val nextPage = CompletableDeferred<ProductSearchResult>()
        gateway.searchDeferred = nextPage
        viewModel.loadMore()
        runCurrent()
        nextPage.complete(ProductSearchResult.ServiceUnavailable)
        advanceUntilIdle()

        assertEquals(ProductSearchStatus.LoadMoreFailed, viewModel.state.value.search?.status)
        assertEquals(listOf(candidate), viewModel.state.value.search?.candidates)

        gateway.searchDeferred = null
        viewModel.selectCandidate(candidate.key)
        advanceUntilIdle()

        assertEquals(1, gateway.confirmationCalls)
        assertEquals(WarehouseRoute.ConfirmedSku, viewModel.state.value.route)
        assertEquals("FRESH-SKU", viewModel.state.value.confirmedSku?.sku)
    }

    @Test
    fun oldPageCannotAppendAfterQueryChanges() = runTest {
        val gateway = FakeWarehouseGateway().apply {
            searchResult = ProductSearchResult.Page(listOf(candidate), "1")
        }
        val viewModel = readySearch(gateway)
        viewModel.submitSearch()
        advanceUntilIdle()
        val deferred = CompletableDeferred<ProductSearchResult>()
        gateway.searchDeferred = deferred
        viewModel.loadMore()
        runCurrent()
        viewModel.queryChanged("nuevo")
        deferred.complete(ProductSearchResult.Page(listOf(candidate), null))
        advanceUntilIdle()
        assertEquals("nuevo", viewModel.state.value.search?.query)
        assertTrue(viewModel.state.value.search!!.candidates.isEmpty())
        assertEquals(ProductSearchStatus.Typing, viewModel.state.value.search?.status)
    }

    @Test
    fun lateConfirmationCannotSurviveReplacementOrLogout() = runTest {
        for (logout in listOf(false, true)) {
            val deferred = CompletableDeferred<CandidateConfirmationResult>()
            val gateway = FakeWarehouseGateway().apply {
                searchResult = ProductSearchResult.Page(listOf(candidate), null)
                confirmationDeferred = deferred
            }
            val viewModel = readySearch(gateway)
            viewModel.submitSearch()
            advanceUntilIdle()
            viewModel.selectCandidate(candidate.key)
            runCurrent()
            assertEquals(candidate.key, gateway.selectedCandidate?.key)
            if (logout) {
                viewModel.sessionInvalidated()
                assertEquals(1L, viewModel.state.value.invalidatedFromAuthorityEpoch)
            } else {
                viewModel.authorityReplaced(context(2), TaskVisibilityHint.Available)
            }
            deferred.complete(CandidateConfirmationResult.Confirmed(confirmed(candidate, 1)))
            advanceUntilIdle()
            assertEquals(WarehouseRoute.WorkEntry, viewModel.state.value.route)
            assertNull(viewModel.state.value.confirmedSku)
            assertNull(viewModel.state.value.search)
        }
    }

    @Test
    fun confirmationUsesFreshDetailAndFailureNeverConfirms() = runTest {
        val gateway = FakeWarehouseGateway().apply {
            searchResult = ProductSearchResult.Page(listOf(candidate), null)
        }
        val viewModel = readySearch(gateway)
        for (
        (result, expected) in listOf(
            CandidateConfirmationResult.CandidateUnavailable to
                ProductSearchStatus.CandidateUnavailable,
            CandidateConfirmationResult.NetworkUnavailable to
                ProductSearchStatus.NetworkUnavailable,
            CandidateConfirmationResult.ServiceUnavailable to
                ProductSearchStatus.ServiceUnavailable
        )
        ) {
            gateway.confirmationResult = result
            viewModel.submitSearch()
            advanceUntilIdle()
            viewModel.selectCandidate(candidate.key)
            advanceUntilIdle()
            assertEquals(expected, viewModel.state.value.search?.status)
            assertNull(viewModel.state.value.confirmedSku)
        }
        gateway.confirmationResult = CandidateConfirmationResult.Confirmed(
            confirmed(candidate, 1).copy(sku = "FRESH-SKU", productDisplayName = "Nombre vigente")
        )
        viewModel.submitSearch()
        advanceUntilIdle()
        viewModel.selectCandidate(candidate.key)
        advanceUntilIdle()
        assertEquals("FRESH-SKU", viewModel.state.value.confirmedSku?.sku)
        assertEquals("Nombre vigente", viewModel.state.value.confirmedSku?.productDisplayName)
    }

    @Test
    fun serverDenialClosesTaskDespiteAvailableHint() = runTest {
        for (duringConfirmation in listOf(false, true)) {
            val gateway = FakeWarehouseGateway().apply {
                searchResult = if (duringConfirmation) {
                    ProductSearchResult.Page(listOf(candidate), null)
                } else {
                    ProductSearchResult.PermissionDenied
                }
                confirmationResult = CandidateConfirmationResult.PermissionDenied
            }
            val viewModel = readySearch(gateway)
            viewModel.submitSearch()
            advanceUntilIdle()
            if (duringConfirmation) {
                viewModel.selectCandidate(candidate.key)
                advanceUntilIdle()
            }
            assertEquals(WarehouseRoute.WorkEntry, viewModel.state.value.route)
            assertEquals(TaskVisibilityHint.Unavailable, viewModel.state.value.permissionHint)
            assertEquals(
                WorkEntryStatus.PermissionUnavailable,
                viewModel.state.value.workEntryStatus
            )
            assertNull(viewModel.state.value.search)
            assertNull(viewModel.state.value.confirmedSku)
            viewModel.openProductSearch()
            assertEquals(WarehouseRoute.WorkEntry, viewModel.state.value.route)
        }
    }

    @Test
    fun permissionRevalidationClosesSearchAndIgnoresItsLateResponse() = runTest {
        val deferred = CompletableDeferred<ProductSearchResult>()
        val gateway = FakeWarehouseGateway().apply { searchDeferred = deferred }
        val viewModel = readySearch(gateway)
        viewModel.submitSearch()
        runCurrent()
        viewModel.authorityReplaced(context(2), TaskVisibilityHint.Unavailable)
        deferred.complete(ProductSearchResult.Page(listOf(candidate), null))
        advanceUntilIdle()
        assertEquals(WorkEntryStatus.PermissionUnavailable, viewModel.state.value.workEntryStatus)
        assertNull(viewModel.state.value.search)
        assertNull(viewModel.state.value.confirmedSku)
    }

    @Test
    fun sameEpochPermissionLossClearsConfirmedDataBeforePermissionReturns() = runTest {
        for (lostHint in listOf(TaskVisibilityHint.Unavailable, TaskVisibilityHint.Unknown)) {
            val gateway = FakeWarehouseGateway().apply {
                searchResult = ProductSearchResult.Page(listOf(candidate), null)
                confirmationResult = CandidateConfirmationResult.Confirmed(confirmed(candidate, 1))
            }
            val viewModel = readySearch(gateway)
            viewModel.submitSearch()
            advanceUntilIdle()
            viewModel.selectCandidate(candidate.key)
            advanceUntilIdle()
            assertEquals(WarehouseRoute.ConfirmedSku, viewModel.state.value.route)
            assertTrue(viewModel.state.value.search?.candidates?.isNotEmpty() == true)
            assertEquals(candidate.sku, viewModel.state.value.confirmedSku?.sku)

            viewModel.permissionHintChanged(lostHint)

            assertEquals(1L, viewModel.state.value.authorityEpoch)
            assertEquals(WarehouseRoute.WorkEntry, viewModel.state.value.route)
            assertEquals(lostHint, viewModel.state.value.permissionHint)
            assertNull(viewModel.state.value.search)
            assertNull(viewModel.state.value.confirmedSku)
            assertEquals(
                if (lostHint == TaskVisibilityHint.Unavailable) {
                    WorkEntryStatus.PermissionUnavailable
                } else {
                    WorkEntryStatus.PermissionUnknown
                },
                viewModel.state.value.workEntryStatus
            )

            viewModel.permissionHintChanged(TaskVisibilityHint.Available)

            assertEquals(1L, viewModel.state.value.authorityEpoch)
            assertEquals(WarehouseRoute.WorkEntry, viewModel.state.value.route)
            assertEquals(WorkEntryStatus.TaskAvailable, viewModel.state.value.workEntryStatus)
            assertNull(viewModel.state.value.search)
            assertNull(viewModel.state.value.confirmedSku)
            viewModel.openProductSearch()
            assertEquals(ProductSearchStatus.Initial, viewModel.state.value.search?.status)
            assertTrue(viewModel.state.value.search?.candidates.orEmpty().isEmpty())
        }
    }

    private fun readySearch(gateway: WarehouseGateway) = WarehouseViewModel(gateway).apply {
        enterOperations(context(1), TaskVisibilityHint.Available)
        openProductSearch()
        queryChanged("gouda")
    }

    private class FakeWarehouseGateway : WarehouseGateway {
        var searchResult: ProductSearchResult = ProductSearchResult.Page(emptyList(), null)
        var confirmationResult: CandidateConfirmationResult =
            CandidateConfirmationResult.CandidateUnavailable
        var searchDeferred: CompletableDeferred<ProductSearchResult>? = null
        var confirmationDeferred: CompletableDeferred<CandidateConfirmationResult>? = null
        var confirmationCalls = 0
        var selectedCandidate: ProductCandidate? = null
        val pageKeys = mutableListOf<String?>()
        var searchCalls = 0
        var lastQuery: String? = null

        override suspend fun search(
            query: String,
            pageKey: String?,
            authorityEpoch: Long
        ): ProductSearchResult {
            searchCalls++
            pageKeys += pageKey
            lastQuery = query
            return searchDeferred?.await() ?: searchResult
        }

        override suspend fun confirm(
            candidate: ProductCandidate,
            authorityEpoch: Long,
            context: CatalogOperationsContext
        ): CandidateConfirmationResult {
            confirmationCalls++
            selectedCandidate = candidate
            return confirmationDeferred?.await() ?: confirmationResult
        }
    }

    private fun context(epoch: Long) = ActiveOperationsContext(
        companyName = "Nexa Demo Distribución",
        workspaceName = "Almacén Principal",
        authorityEpoch = epoch
    )

    private companion object {
        val candidate = ProductCandidate(
            key = "synthetic-candidate",
            productDisplayName = "Queso Gouda Demo",
            brandOrVariant = "Lácteo",
            presentation = "Bloque · 500 g",
            sku = "SKU-DEMO-001"
        )

        fun confirmed(candidate: ProductCandidate, epoch: Long) = ConfirmedSkuProjection(
            candidateKey = candidate.key,
            productDisplayName = candidate.productDisplayName,
            variant = candidate.brandOrVariant,
            presentation = candidate.presentation,
            sku = candidate.sku,
            brand = "Marca Demo",
            unit = "unidad",
            packaging = "Caja de 12",
            coldChain = "Refrigerado",
            context = CatalogOperationsContext(
                "Nexa Demo Distribución",
                "Almacén Principal",
                epoch
            ),
            authorityEpoch = epoch
        )
    }
}
