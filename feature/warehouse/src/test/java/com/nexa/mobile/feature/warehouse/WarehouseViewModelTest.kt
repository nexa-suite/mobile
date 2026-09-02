package com.nexa.mobile.feature.warehouse

import com.nexa.mobile.core.network.ApiError
import com.nexa.mobile.core.network.ApiErrorCategory
import com.nexa.mobile.core.network.ApiResult
import com.nexa.mobile.core.network.SkuResolution
import com.nexa.mobile.core.storage.SessionMaterial
import com.nexa.mobile.core.storage.SessionStore
import com.nexa.mobile.core.testing.CoroutinesTestRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WarehouseViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = CoroutinesTestRule(dispatcher)

    @Test
    fun missingSessionStaysBlockedAndDoesNotResolve() = runTest(dispatcher.scheduler) {
        var calls = 0
        val viewModel = WarehouseViewModel(
            resolver = SkuIdentifierResolver { _, _ ->
                calls += 1
                ApiResult.Success(resolution())
            },
            sessionStore = FakeSessionStore(material = null),
        )
        viewModel.onIdentifierChanged("SKU-001")
        viewModel.resolveManually()
        advanceUntilIdle()

        assertEquals(WarehouseStatus.Blocked, viewModel.uiState.value.status)
        assertEquals(0, calls)
    }

    @Test
    fun scanResolvesOnlyWithTheStoredAccessToken() = runTest(dispatcher.scheduler) {
        var receivedToken: String? = null
        val viewModel = WarehouseViewModel(
            resolver = SkuIdentifierResolver { _, accessToken ->
                receivedToken = accessToken
                ApiResult.Success(resolution())
            },
            sessionStore = FakeSessionStore(session()),
        )
        viewModel.startScanning()
        viewModel.onBarcodeDetected(" SKU-001 ")
        advanceUntilIdle()

        val status = viewModel.uiState.value.status as WarehouseStatus.Resolved
        assertEquals("access-token", receivedToken)
        assertEquals("sku-1", status.resolution.skuId)
        assertEquals("SKU-001", viewModel.uiState.value.identifier)
    }

    @Test
    fun serverOutcomesAreRenderedWithoutSelectingAClientSku() = runTest(dispatcher.scheduler) {
        val viewModel = WarehouseViewModel(
            resolver = SkuIdentifierResolver { _, _ ->
                ApiResult.Success(
                    resolution().copy(
                        outcome = "AMBIGUOUS",
                        candidateCount = 2,
                        skuId = null,
                    ),
                )
            },
            sessionStore = FakeSessionStore(session()),
        )
        viewModel.onIdentifierChanged("9771234567890")
        viewModel.resolveManually()
        advanceUntilIdle()

        assertEquals(
            WarehouseStatus.Ambiguous("SKU-001", 2),
            viewModel.uiState.value.status,
        )
    }

    @Test
    fun unauthorizedResolutionBecomesSafeFailure() = runTest(dispatcher.scheduler) {
        val viewModel = WarehouseViewModel(
            resolver = SkuIdentifierResolver { _, _ ->
                ApiResult.Failure(ApiError(category = ApiErrorCategory.UNAUTHORIZED, status = 401))
            },
            sessionStore = FakeSessionStore(session()),
        )
        viewModel.onIdentifierChanged("SKU-001")
        viewModel.resolveManually()
        advanceUntilIdle()

        assertEquals(
            WarehouseStatus.Failed(WarehouseFailure.UNAUTHORIZED),
            viewModel.uiState.value.status,
        )
    }

    @Test
    fun blankManualIdentifierNeverCallsResolver() = runTest(dispatcher.scheduler) {
        var calls = 0
        val viewModel = WarehouseViewModel(
            resolver = SkuIdentifierResolver { _, _ ->
                calls += 1
                ApiResult.Success(resolution())
            },
            sessionStore = FakeSessionStore(session()),
        )
        viewModel.resolveManually()
        advanceUntilIdle()

        assertEquals(WarehouseStatus.Failed(WarehouseFailure.VALIDATION), viewModel.uiState.value.status)
        assertEquals(0, calls)
    }

    private fun session() = SessionMaterial(
        accessToken = "access-token",
        refreshToken = "refresh-token",
        accessTokenExpiresAtEpochSeconds = 1_900_000_000,
        surface = "PLATFORM",
    )

    private fun resolution() = SkuResolution(
        outcome = "RESOLVED",
        identifierType = "SKU_CODE",
        normalizedIdentifier = "SKU-001",
        candidateCount = 1,
        skuId = "sku-1",
        skuCode = "SKU-001",
        presentation = "Box",
        unitOfMeasure = "UNIT",
        status = "ACTIVE",
    )

    private class FakeSessionStore(
        private val material: SessionMaterial?,
    ) : SessionStore {
        override suspend fun read(): SessionMaterial? = material

        override suspend fun write(material: SessionMaterial) = Unit

        override suspend fun clear() = Unit
    }
}
