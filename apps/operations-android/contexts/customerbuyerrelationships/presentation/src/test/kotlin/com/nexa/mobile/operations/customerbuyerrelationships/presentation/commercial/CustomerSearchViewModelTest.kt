package com.nexa.mobile.operations.customerbuyerrelationships.presentation.commercial

import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.CustomerGateway
import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.CustomerResult
import com.nexa.mobile.operations.customerbuyerrelationships.domain.model.commercial.CustomerRelationship
import com.nexa.mobile.operations.customerbuyerrelationships.presentation.commercial.CustomerSearchStatus
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
class CustomerSearchViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    private val customer = CustomerRelationship("id", "C1", "Customer", null, false, true, 2)
    private fun authority(permissions: Set<String> = setOf("client.read")) =
        CommercialAuthority("u", "t", "w", "m", permissions, 7)

    @Test fun suspendedRelationshipRemainsExplicitAndHasNoCommercialMutation() = runTest {
        val model = CustomerSearchViewModel(object : CustomerGateway {
            override suspend fun search(authority: CommercialAuthority, query: String, page: Int) =
                CustomerResult.Page(listOf(customer), page, 1)
            override suspend fun detail(authority: CommercialAuthority, id: String) =
                CustomerResult.Detail(customer)
        })
        model.activate(authority())
        runCurrent()
        model.selectCustomer("id")
        runCurrent()
        assertEquals(false, model.state.value.detail?.active)
        assertEquals(2L, model.state.value.detail?.version)
        model.activate(authority(emptySet()))
        assertTrue(model.state.value.items.isEmpty())
        assertNull(model.state.value.detail)
        assertEquals(CustomerSearchStatus.PermissionDenied, model.state.value.status)
    }

    @Test fun lateSearchAfterContextLossCannotRestorePrivateFacts() = runTest {
        val pending = CompletableDeferred<CustomerResult>()
        val model = CustomerSearchViewModel(object : CustomerGateway {
            override suspend fun search(authority: CommercialAuthority, query: String, page: Int) =
                pending.await()
            override suspend fun detail(authority: CommercialAuthority, id: String) =
                CustomerResult.Unavailable
        })
        model.activate(authority())
        runCurrent()
        model.deactivate()
        pending.complete(CustomerResult.Page(listOf(customer), 0, 1))
        runCurrent()
        assertTrue(model.state.value.items.isEmpty())
        assertNull(model.state.value.receivedAt)
    }

    @Test fun changingSearchDiscardsPreviousResultsBeforeNewRequest() = runTest {
        val model = CustomerSearchViewModel(object : CustomerGateway {
            override suspend fun search(authority: CommercialAuthority, query: String, page: Int) =
                CustomerResult.Page(listOf(customer), page, 1)
            override suspend fun detail(authority: CommercialAuthority, id: String) =
                CustomerResult.Unavailable
        })
        model.activate(authority())
        runCurrent()
        model.queryChanged("Other")
        assertTrue(model.state.value.items.isEmpty())
        assertNull(model.state.value.receivedAt)
        assertEquals(CustomerSearchStatus.Idle, model.state.value.status)
    }
}
