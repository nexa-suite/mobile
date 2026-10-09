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
    private fun authority(
        permissions: Set<String> = setOf("client.read"),
        workspaceId: String = "w",
        authorityEpoch: Long = 7
    ) = CommercialAuthority(
        "u",
        "t",
        workspaceId,
        "m-$workspaceId",
        permissions,
        authorityEpoch
    )

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

    @Test
    fun invalidatedContextClearsCustomerFactsAndBlocksFurtherSearchActions() = runTest {
        var searchCalls = 0
        var detailCalls = 0
        val model = CustomerSearchViewModel(object : CustomerGateway {
            override suspend fun search(authority: CommercialAuthority, query: String, page: Int) =
                if (++searchCalls == 1) {
                    CustomerResult.Page(listOf(customer), page, 1)
                } else {
                    CustomerResult.ContextInvalidated
                }

            override suspend fun detail(
                authority: CommercialAuthority,
                id: String
            ): CustomerResult {
                detailCalls++
                return CustomerResult.Detail(customer)
            }
        })
        model.activate(authority())
        runCurrent()
        model.selectCustomer("id")
        runCurrent()
        assertEquals(customer, model.state.value.detail)

        model.search()
        runCurrent()

        assertEquals(CustomerSearchStatus.ContextInvalidated, model.state.value.status)
        assertTrue(model.state.value.items.isEmpty())
        assertNull(model.state.value.detail)
        assertNull(model.state.value.receivedAt)
        model.queryChanged("another customer")
        model.search()
        model.nextPage()
        model.previousPage()
        model.selectCustomer("id")
        runCurrent()
        assertEquals(2, searchCalls)
        assertEquals(1, detailCalls)
    }

    @Test
    fun lateInvalidationFromWorkspaceADoesNotClearWorkspaceB() = runTest {
        val responseFromA = CompletableDeferred<CustomerResult>()
        val model = CustomerSearchViewModel(object : CustomerGateway {
            override suspend fun search(authority: CommercialAuthority, query: String, page: Int) =
                if (authority.workspaceId == "workspace-a") {
                    responseFromA.await()
                } else {
                    CustomerResult.Page(listOf(customer), page, 1)
                }

            override suspend fun detail(authority: CommercialAuthority, id: String) =
                CustomerResult.Unavailable
        })

        model.activate(authority(workspaceId = "workspace-a", authorityEpoch = 1))
        runCurrent()
        model.activate(authority(workspaceId = "workspace-b", authorityEpoch = 2))
        runCurrent()
        assertEquals(CustomerSearchStatus.Current, model.state.value.status)
        assertEquals(listOf(customer), model.state.value.items)

        responseFromA.complete(CustomerResult.ContextInvalidated)
        runCurrent()

        assertEquals(CustomerSearchStatus.Current, model.state.value.status)
        assertEquals(listOf(customer), model.state.value.items)
    }
}
