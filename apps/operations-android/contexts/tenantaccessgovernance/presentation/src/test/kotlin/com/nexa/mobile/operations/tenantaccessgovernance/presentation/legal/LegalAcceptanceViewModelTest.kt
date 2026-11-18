package com.nexa.mobile.operations.tenantaccessgovernance.presentation.legal

import com.nexa.mobile.operations.tenantaccessgovernance.application.legal.LegalAcceptanceGateway
import com.nexa.mobile.operations.tenantaccessgovernance.application.legal.MobileTermsAcceptResult
import com.nexa.mobile.operations.tenantaccessgovernance.application.legal.MobileTermsAcceptance
import com.nexa.mobile.operations.tenantaccessgovernance.application.legal.MobileTermsAcceptanceResult
import com.nexa.mobile.operations.tenantaccessgovernance.application.legal.MobileTermsDocument
import com.nexa.mobile.operations.tenantaccessgovernance.application.legal.MobileTermsDocumentResult
import com.nexa.mobile.operations.tenantaccessgovernance.application.legal.MobileTermsSessionLease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LegalAcceptanceViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val document = MobileTermsDocument(
        documentId = "mobile-terms",
        version = "1",
        content = "Terms text from the server",
        contentDigest = "abc123"
    )
    private lateinit var gateway: FakeLegalAcceptanceGateway
    private lateinit var viewModel: LegalAcceptanceViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        gateway = FakeLegalAcceptanceGateway(document)
        viewModel = LegalAcceptanceViewModel(gateway)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun uncheckedAcceptanceDoesNotPostAndCheckedAcceptanceRequiresServerStatus() =
        runTest(dispatcher) {
            viewModel.activate(authorityEpoch = 4)
            advanceUntilIdle()
            val required = viewModel.state.value as LegalAcceptanceUiState.RequiresAcceptance

            viewModel.accept(required.document, confirmed = false)
            advanceUntilIdle()
            assertEquals(0, gateway.acceptCalls)

            gateway.acceptResult = MobileTermsAcceptResult.Accepted
            gateway.acceptance = MobileTermsAcceptance(
                document,
                accepted = true,
                acceptedAt = "server-time"
            )
            viewModel.accept(required.document, confirmed = true)
            advanceUntilIdle()

            assertEquals(1, gateway.acceptCalls)
            assertEquals(2, gateway.acceptanceReads)
            assertEquals(
                LegalAcceptanceUiState.Accepted(gateway.lease, authorityEpoch = 4),
                viewModel.state.value
            )
        }

    @Test
    fun unknownPostOutcomeReadsServerStatusAndKeepsGateWhenNotAccepted() = runTest(dispatcher) {
        viewModel.activate(authorityEpoch = 7)
        advanceUntilIdle()
        val required = viewModel.state.value as LegalAcceptanceUiState.RequiresAcceptance

        gateway.acceptResult = MobileTermsAcceptResult.UnknownOutcome
        viewModel.accept(required.document, confirmed = true)
        advanceUntilIdle()

        assertEquals(2, gateway.acceptanceReads)
        val result = viewModel.state.value as LegalAcceptanceUiState.RequiresAcceptance
        assertEquals(LegalAcceptanceError.UnknownOutcome, result.error)
    }

    @Test
    fun newerDocumentVersionReplacesDisplayedDocumentAfterConflict() = runTest(dispatcher) {
        viewModel.activate(authorityEpoch = 9)
        advanceUntilIdle()
        val required = viewModel.state.value as LegalAcceptanceUiState.RequiresAcceptance
        val updatedDocument = document.copy(version = "2", contentDigest = "def456")
        gateway.acceptResult = MobileTermsAcceptResult.StaleDocument
        gateway.acceptance = MobileTermsAcceptance(
            updatedDocument,
            accepted = false,
            acceptedAt = null
        )

        viewModel.accept(required.document, confirmed = true)
        advanceUntilIdle()

        val result = viewModel.state.value as LegalAcceptanceUiState.RequiresAcceptance
        assertEquals(updatedDocument, result.document)
        assertEquals(LegalAcceptanceError.StaleDocument, result.error)
    }

    @Test
    fun acceptedStatusSurvivesOfflineRefreshOnlyForSameLease() = runTest(dispatcher) {
        viewModel.activate(authorityEpoch = 9)
        advanceUntilIdle()
        gateway.acceptance = MobileTermsAcceptance(document, accepted = true, acceptedAt = "server")
        viewModel.retryAcceptance()
        advanceUntilIdle()
        assertEquals(
            LegalAcceptanceUiState.Accepted(gateway.lease, authorityEpoch = 9),
            viewModel.state.value
        )

        gateway.offline = true
        viewModel.retryAcceptance()
        advanceUntilIdle()
        assertEquals(
            LegalAcceptanceUiState.Accepted(gateway.lease, authorityEpoch = 9),
            viewModel.state.value
        )

        gateway.lease = MobileTermsSessionLease(epoch = 3, generation = 2)
        viewModel.retryAcceptance()
        advanceUntilIdle()
        assertEquals(LegalAcceptanceUiState.Unavailable, viewModel.state.value)
    }
}

private class FakeLegalAcceptanceGateway(private val document: MobileTermsDocument) :
    LegalAcceptanceGateway {
    var acceptance = MobileTermsAcceptance(document, accepted = false, acceptedAt = null)
    var acceptResult: MobileTermsAcceptResult = MobileTermsAcceptResult.Accepted
    var acceptCalls = 0
    var acceptanceReads = 0
    var offline = false
    var lease = MobileTermsSessionLease(epoch = 3, generation = 1)

    override suspend fun loadPublicTerms() = MobileTermsDocumentResult.Available(document)

    override suspend fun loadAcceptance(): MobileTermsAcceptanceResult {
        acceptanceReads++
        return if (offline) {
            MobileTermsAcceptanceResult.Unavailable(lease, transportFailure = true)
        } else {
            MobileTermsAcceptanceResult.Available(acceptance, lease)
        }
    }

    override suspend fun accept(document: MobileTermsDocument): MobileTermsAcceptResult {
        acceptCalls++
        return acceptResult
    }
}
