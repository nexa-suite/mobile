package com.nexa.mobile.operations.salescommitment.presentation.commercial

import com.nexa.mobile.operations.salescommitment.application.commercial.CustomerProgressGateway
import com.nexa.mobile.operations.salescommitment.application.commercial.CustomerProgressResult
import com.nexa.mobile.operations.salescommitment.application.commercial.ProgressStatus
import com.nexa.mobile.operations.salescommitment.domain.model.commercial.CustomerCommitment
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CustomerProgressViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    private fun authority() = CommercialAuthority("u", "t", "w", "m", setOf("client.read"), 7)
    private val result =
        CustomerProgressResult(
            "Customer",
            listOf(
                CustomerCommitment(
                    "o",
                    "SO1",
                    "CONFIRMED",
                    "12.50",
                    "PEN",
                    3,
                    "2026-09-30T00:00:00Z"
                )
            ),
            null,
            ProgressStatus.Current,
            ProgressStatus.Unavailable,
            0,
            1
        )

    @Test fun incompleteCreditDoesNotBecomeInventedFinancialDecision() = runTest {
        val model = CustomerProgressViewModel(object : CustomerProgressGateway {
            override suspend fun read(
                authority: CommercialAuthority,
                id: String,
                currency: String,
                page: Int
            ) = result
        })
        model.activate(authority())
        model.customerIdChanged("customer")
        model.refresh()
        runCurrent()
        assertEquals(ProgressStatus.Current, model.state.value.commitmentsStatus)
        assertEquals(ProgressStatus.Unavailable, model.state.value.creditStatus)
        assertNull(model.state.value.credit)
        assertEquals("12.50", model.state.value.commitments.single().total)
        model.currencyChanged("USD")
        assertTrue(model.state.value.commitments.isEmpty())
        assertNull(model.state.value.credit)
    }

    @Test fun lateProgressAfterContextLossCannotRestoreProtectedFacts() = runTest {
        val pending = CompletableDeferred<CustomerProgressResult>()
        val model = CustomerProgressViewModel(object : CustomerProgressGateway {
            override suspend fun read(
                authority: CommercialAuthority,
                id: String,
                currency: String,
                page: Int
            ) = pending.await()
        })
        model.activate(authority())
        model.customerIdChanged("customer")
        model.refresh()
        runCurrent()
        model.deactivate()
        pending.complete(result)
        runCurrent()
        assertTrue(model.state.value.commitments.isEmpty())
        assertNull(model.state.value.customerName)
    }
}
