package com.nexa.mobile.operations.feature.warehouse

import androidx.lifecycle.ViewModel
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
        assertNull(viewModel.state.value.activeContext)
        assertNull(viewModel.state.value.search)
        assertNull(viewModel.state.value.confirmedSku)
    }

    private class FakeWarehouseGateway : WarehouseGateway {
        var searchResult: ProductSearchResult = ProductSearchResult.Page(emptyList(), null)
        var confirmationResult: CandidateConfirmationResult =
            CandidateConfirmationResult.CandidateUnavailable
        var searchDeferred: CompletableDeferred<ProductSearchResult>? = null
        var searchCalls = 0
        var lastQuery: String? = null

        override suspend fun search(
            query: String,
            pageKey: String?,
            authorityEpoch: Long
        ): ProductSearchResult {
            searchCalls++
            lastQuery = query
            return searchDeferred?.await() ?: searchResult
        }

        override suspend fun confirm(
            candidate: ProductCandidate,
            authorityEpoch: Long,
            context: ActiveOperationsContext
        ): CandidateConfirmationResult = confirmationResult
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

        fun confirmed(candidate: ProductCandidate, epoch: Long) = ConfirmedSkuUiState(
            candidateKey = candidate.key,
            productDisplayName = candidate.productDisplayName,
            variant = candidate.brandOrVariant,
            presentation = candidate.presentation,
            sku = candidate.sku,
            brand = "Marca Demo",
            unit = "unidad",
            packaging = "Caja de 12",
            coldChain = "Refrigerado",
            context = ActiveOperationsContext("Nexa Demo Distribución", "Almacén Principal", epoch),
            authorityEpoch = epoch
        )
    }
}
